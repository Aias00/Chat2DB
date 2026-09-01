package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.result.DbExplainCapability;
import ai.chat2db.community.domain.api.model.result.DbExplainResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbExplainServiceImplTest {

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
    }

    @Test
    void acceptsSingleSelectStatementsIncludingCommentsAndCtes() {
        assertTrue(DbExplainServiceImpl.isSingleSelectStatement("SELECT * FROM orders"));
        assertTrue(DbExplainServiceImpl.isSingleSelectStatement("/* dashboard query */ SELECT * FROM orders"));
        assertTrue(DbExplainServiceImpl.isSingleSelectStatement("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent"));
    }

    @Test
    void rejectsWritesAndMultipleStatements() {
        assertFalse(DbExplainServiceImpl.isSingleSelectStatement("UPDATE orders SET status = 'done'"));
        assertFalse(DbExplainServiceImpl.isSingleSelectStatement("SELECT * FROM orders; DELETE FROM orders"));
        assertFalse(DbExplainServiceImpl.isSingleSelectStatement("SELECT * FROM orders; SELECT * FROM users"));
    }

    @Test
    void checksMySqlExplainVersionBoundaries() {
        assertFalse(DbExplainServiceImpl.supportsExplainJson("5.6.51"));
        assertTrue(DbExplainServiceImpl.supportsExplainJson("5.7.44"));
        assertFalse(DbExplainServiceImpl.supportsExplainAnalyze("8.0.17"));
        assertTrue(DbExplainServiceImpl.supportsExplainAnalyze("8.0.18"));
        assertTrue(DbExplainServiceImpl.supportsExplainAnalyze("8.0.36"));
    }

    @Test
    void reportsCurrentMySqlExplainCapabilities() {
        bindMysql("8.0.17", null);
        DbExplainCapability capability = new DbExplainServiceImpl().capability();

        assertEquals(DatabaseTypeEnum.MYSQL.name(), capability.getDatabaseType());
        assertEquals("8.0.17", capability.getServerVersion());
        assertTrue(capability.isExplainJsonSupported());
        assertFalse(capability.isExplainAnalyzeSupported());
    }

    @Test
    void explainJsonReturnsTypedResultAndOnlyExecutesExplainSelect() {
        JdbcPlanFixture jdbc = new JdbcPlanFixture("{\"query_block\":{\"select_id\":1}}");
        bindMysql("8.0.36", jdbc.connection());

        DbExplainResult result = new DbExplainServiceImpl().explainJson(
                "/* dashboard */ SELECT * FROM obj002_orders WHERE user_id = 1", "req-json-1");

        assertEquals("req-json-1", result.getRequestId());
        assertEquals("json", result.getMode());
        assertEquals("{\"query_block\":{\"select_id\":1}}", result.getRawPlan());
        assertTrue(result.getCapability().isExplainAnalyzeSupported());
        assertTrue(jdbc.executedSql().startsWith("EXPLAIN FORMAT=JSON"));
        assertTrue(jdbc.executedSql().toUpperCase().contains("SELECT"));
        assertFalse(jdbc.executedSql().toUpperCase().contains("DELETE"));
        assertNotNull(result.getNormalizedSql());
    }

    @Test
    void explainRejectsDmlBeforeJdbcExecution() {
        JdbcPlanFixture jdbc = new JdbcPlanFixture("{}");
        bindMysql("8.0.36", jdbc.connection());

        assertThrows(BusinessException.class,
                () -> new DbExplainServiceImpl().explainJson("UPDATE obj002_orders SET amount = 0", "req-dml"));
        assertEquals(null, jdbc.executedSql());
    }

    @Test
    void cancelInterruptsActiveExplainStatement() throws Exception {
        DbExplainServiceImpl service = new DbExplainServiceImpl();
        JdbcPlanFixture jdbc = new JdbcPlanFixture("cancelled");
        jdbc.blockExecute();
        var executor = Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> {
            bindMysql("8.0.36", jdbc.connection(), 7L, 11L, "alice");
            try {
                return service.executeExplain(
                        jdbc.connection(), "EXPLAIN ANALYZE SELECT SLEEP(10)", "req-cancel");
            } finally {
                Chat2DBContext.removeContext();
            }
        });

        assertTrue(jdbc.awaitExecute(), "test statement should enter execute before cancellation");
        bindMysql("8.0.36", jdbc.connection(), 8L, 11L, "alice");
        assertFalse(service.cancel("req-cancel"), "another datasource must not cancel the request");
        bindMysql("8.0.36", jdbc.connection(), 7L, 12L, "alice");
        assertFalse(service.cancel("req-cancel"), "another console must not cancel the request");
        bindMysql("8.0.36", jdbc.connection(), 7L, 11L, "bob");
        assertFalse(service.cancel("req-cancel"), "another login user must not cancel the request");
        bindMysql("8.0.36", jdbc.connection(), 7L, 11L, "alice");
        assertTrue(service.cancel("req-cancel"));
        jdbc.releaseExecute();

        assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(jdbc.cancelled());
        executor.shutdownNow();
    }

    private static void bindMysql(String version, Connection connection) {
        bindMysql(version, connection, 1L, 1L, "test-user");
    }

    private static void bindMysql(String version, Connection connection, Long dataSourceId, Long consoleId,
            String loginUser) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DatabaseTypeEnum.MYSQL.name());
        connectInfo.setDbVersion(version);
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setConsoleId(consoleId);
        connectInfo.setLoginUser(loginUser);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private static final class JdbcPlanFixture {
        private final String explainValue;
        private final AtomicReference<String> sql = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CountDownLatch executeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseExecute = new CountDownLatch(1);
        private boolean blockExecute;

        private JdbcPlanFixture(String explainValue) {
            this.explainValue = explainValue;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    sql.set((String) args[0]);
                    yield statement();
                }
                case "isClosed" -> false;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> execute();
                case "getResultSet" -> resultSet();
                case "cancel" -> {
                    cancelled.set(true);
                    releaseExecute.countDown();
                    yield null;
                }
                case "isClosed" -> false;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private Object resultSet() {
            AtomicBoolean read = new AtomicBoolean(false);
            return proxy(java.sql.ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> read.compareAndSet(false, true);
                case "getString" -> explainValue;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private boolean execute() throws Exception {
            executeEntered.countDown();
            if (blockExecute) {
                assertTrue(releaseExecute.await(2, TimeUnit.SECONDS));
            }
            if (cancelled.get()) {
                throw new SQLException("SQL execution canceled");
            }
            return true;
        }

        private void blockExecute() {
            blockExecute = true;
        }

        private boolean awaitExecute() throws InterruptedException {
            return executeEntered.await(2, TimeUnit.SECONDS);
        }

        private void releaseExecute() {
            releaseExecute.countDown();
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private String executedSql() {
            return sql.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
