package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbLockServiceImplTest {

    private static final String TEST_DB_TYPE = "LOCK_VIEW_TEST";

    private IPlugin previousPlugin;

    private RecordingDbManager dbManager;

    @BeforeEach
    void setUp() {
        dbManager = new RecordingDbManager();
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin(dbManager));
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void rejectsMissingDatasourceContextBeforeQueryingJdbc() {
        DbLockServiceImpl service = new DbLockServiceImpl();

        BusinessException exception = assertThrows(BusinessException.class, () -> service.lockView(11L));

        assertEquals("datasource.context.required", exception.getCode());
        assertEquals(0, dbManager.connectionRequests);
    }

    @Test
    void rejectsMismatchedDatasourceContextBeforeQueryingJdbc() {
        bindContext(11L);
        DbLockServiceImpl service = new DbLockServiceImpl();

        BusinessException exception = assertThrows(BusinessException.class, () -> service.lockView(12L));

        assertEquals("datasource.context.mismatch", exception.getCode());
        assertEquals(0, dbManager.connectionRequests);
    }

    @Test
    void returnsDeterministicEmptyViewWhenLockTablesAreUnavailable() {
        bindContext(21L);
        dbManager.sqlFailures = Map.of(
                "performance_schema.data_locks", new SQLException("SELECT command denied"),
                "information_schema.innodb_locks", new SQLException("Access denied"),
                "information_schema.innodb_lock_waits", new SQLException("Access denied"),
                "performance_schema.metadata_locks", new SQLException("Access denied"),
                "information_schema.innodb_trx", new SQLException("Access denied")
        );
        DbLockServiceImpl service = new DbLockServiceImpl();

        Map<String, Object> view = service.lockView(21L);

        assertEquals(21L, view.get("dataSourceId"));
        assertEquals("unavailable", view.get("source"));
        assertEquals(List.of(), view.get("dataLocks"));
        assertEquals(List.of(), view.get("waits"));
        assertEquals(List.of(), view.get("metaLocks"));
        assertEquals(List.of(), view.get("waitChains"));
        List<Map<String, Object>> errors = errors(view);
        assertEquals(List.of("dataLocks", "waits", "metaLocks", "sessions"),
                errors.stream().map(error -> error.get("section")).toList());
        assertTrue(errors.stream().allMatch(error -> "privilege_required".equals(error.get("code"))));
    }

    @Test
    void allowsSequentialDatasourcesOnlyWhenEachMatchesBoundContext() {
        DbLockServiceImpl service = new DbLockServiceImpl();
        bindContext(31L);

        Map<String, Object> first = service.lockView(31L);
        Chat2DBContext.removeContext();
        bindContext(32L);
        Map<String, Object> second = service.lockView(32L);

        assertEquals(31L, first.get("dataSourceId"));
        assertEquals(32L, second.get("dataSourceId"));
        assertEquals(2, dbManager.connectionRequests);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("errors");
    }

    private static void bindContext(Long dataSourceId) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    private static final class RecordingDbManager extends DefaultDBManager {

        private int connectionRequests;

        private Map<String, SQLException> sqlFailures = Map.of();

        @Override
        public Connection getConnection(ConnectInfo connectInfo) {
            connectionRequests++;
            return connection(sqlFailures);
        }
    }

    private static final class TestPlugin implements IPlugin {

        private final IDbManager dbManager;

        private TestPlugin(IDbManager dbManager) {
            this.dbManager = dbManager;
        }

        @Override
        public DBConfig getDBConfig() {
            DBConfig config = new DBConfig();
            config.setDbType(TEST_DB_TYPE);
            config.setDefaultDriverConfig(new DriverConfig());
            return config;
        }

        @Override
        public IDbManager getDbManager() {
            return dbManager;
        }
    }

    private static Connection connection(Map<String, SQLException> sqlFailures) {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "isClosed" -> false;
            case "prepareStatement" -> statement((String) args[0], sqlFailures);
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static PreparedStatement statement(String sql, Map<String, SQLException> sqlFailures) {
        return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "execute" -> {
                resultSet(sql, sqlFailures);
                yield true;
            }
            case "getResultSet" -> resultSet(sql, Map.of());
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(String sql, Map<String, SQLException> sqlFailures) throws SQLException {
        for (Map.Entry<String, SQLException> entry : sqlFailures.entrySet()) {
            if (sql.contains(entry.getKey())) {
                throw entry.getValue();
            }
        }
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> false;
            case "getMetaData" -> resultSetMetaData();
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSetMetaData resultSetMetaData() {
        return proxy(ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> 0;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(DbLockServiceImplTest.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
