package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbEventServiceImplTest {

    private static final String TEST_DB_TYPE = "event-service-test";

    @AfterEach
    void tearDown() {
        Chat2DBContext.close();
        Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
    }

    @Test
    void schedulerStatusCountsEventsForSelectedDatabase() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());

        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(jdbc.connection());
        Chat2DBContext.putContext(connectInfo);

        Map<String, Object> status = new DbEventServiceImpl().schedulerStatus("analytics");

        assertEquals(Boolean.TRUE, status.get("schedulerEnabled"));
        assertEquals(3L, status.get("eventCount"));
        assertTrue(jdbc.sql().contains("SHOW VARIABLES LIKE 'event_scheduler'"));
        assertTrue(jdbc.sql().stream().anyMatch(sql ->
                sql.equals("SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = 'analytics'")));
    }

    private static IPlugin plugin() {
        return new IPlugin() {
            private final IDbMetaData metaData = new DefaultMetaService();

            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(TEST_DB_TYPE);
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return metaData;
            }
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class || returnType == long.class || returnType == short.class
                || returnType == byte.class) {
            return 0;
        }
        if (returnType == float.class || returnType == double.class) {
            return 0.0;
        }
        return null;
    }

    private static final class RecordingJdbc {
        private final List<String> sql = new ArrayList<>();

        private Connection connection() {
            return proxy(Connection.class, (target, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    String statementSql = (String) args[0];
                    sql.add(statementSql);
                    yield preparedStatement(statementSql);
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private List<String> sql() {
            return sql;
        }

        private PreparedStatement preparedStatement(String statementSql) {
            return proxy(PreparedStatement.class, (target, method, args) -> switch (method.getName()) {
                case "execute" -> true;
                case "getResultSet" -> resultSet(statementSql);
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet(String statementSql) {
            List<Object[]> rows = new ArrayList<>();
            rows.add(statementSql.startsWith("SHOW VARIABLES")
                    ? new Object[] {"event_scheduler", "ON"}
                    : new Object[] {3L});
            return proxy(ResultSet.class, new ResultSetHandler(rows));
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final List<Object[]> rows;
        private int index = -1;

        private ResultSetHandler(List<Object[]> rows) {
            this.rows = rows;
        }

        @Override
        public Object invoke(Object target, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> ++index < rows.size();
                case "getString" -> String.valueOf(value(args[0]));
                case "getLong" -> ((Number) value(args[0])).longValue();
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object value(Object column) {
            if (column instanceof Integer columnIndex) {
                return rows.get(index)[columnIndex - 1];
            }
            throw new UnsupportedOperationException(String.valueOf(column));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
