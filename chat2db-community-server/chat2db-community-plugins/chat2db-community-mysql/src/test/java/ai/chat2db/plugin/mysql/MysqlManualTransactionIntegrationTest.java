package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.runtime.TransactionIsolationLevel;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.community.tools.util.I18nUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MysqlManualTransactionIntegrationTest {

    private static final AtomicLong CONSOLE_IDS = new AtomicLong(20_000L);
    private static IPlugin previousMysqlPlugin;
    private static Field messageSourceField;
    private static MessageSource previousMessageSource;

    @BeforeAll
    static void registerPlugin() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        previousMysqlPlugin = Chat2DBContext.PLUGIN_MAP.put("MYSQL", new MysqlPlugin());
        messageSourceField = I18nUtils.class.getDeclaredField("messageSourceStatic");
        messageSourceField.setAccessible(true);
        previousMessageSource = (MessageSource) messageSourceField.get(null);
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("sqlResult.success", Locale.US, "Success");
        messageSourceField.set(null, messageSource);
    }

    @AfterAll
    static void cleanup() {
        ConnectionPool.releaseAll(true);
        Chat2DBContext.removeContext();
        if (previousMysqlPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove("MYSQL");
        } else {
            Chat2DBContext.PLUGIN_MAP.put("MYSQL", previousMysqlPlugin);
        }
        if (messageSourceField != null) {
            try {
                messageSourceField.set(null, previousMessageSource);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @ParameterizedTest(name = "MySQL transaction commit/rollback on port {0}")
    @ValueSource(ints = {3357, 3380})
    void commitAndRollbackControlVisibility(int port) throws Exception {
        assumeMysqlAvailable(port);
        prepareSchema(port);
        try (Connection observer = open(port)) {
            long commitConsole = nextConsoleId();
            ConnectInfo committing = registerBound(port, commitConsole);
            execute(committing.getConnection(), "INSERT INTO tx_innodb(val) VALUES ('committed')");
            assertEquals(0, count(observer, "tx_innodb"));
            assertEquals(ConnectionPool.TransactionOutcome.COMMITTED, ConnectionPool.commit(commitConsole));
            assertEquals(1, count(observer, "tx_innodb"));

            long rollbackConsole = nextConsoleId();
            ConnectInfo rollingBack = registerBound(port, rollbackConsole);
            execute(rollingBack.getConnection(), "INSERT INTO tx_innodb(val) VALUES ('rolled-back')");
            assertEquals(1, count(observer, "tx_innodb"));
            assertEquals(ConnectionPool.TransactionOutcome.ROLLED_BACK, ConnectionPool.rollback(rollbackConsole));
            assertEquals(1, count(observer, "tx_innodb"));
        } finally {
            ConnectionPool.releaseAll(true);
        }
    }

    @ParameterizedTest(name = "MySQL MyISAM rollback semantics on port {0}")
    @ValueSource(ints = {3357, 3380})
    void myIsamChangesRemainVisibleAfterRollback(int port) throws Exception {
        assumeMysqlAvailable(port);
        prepareSchema(port);
        long consoleId = nextConsoleId();
        try (Connection observer = open(port)) {
            ConnectInfo bound = registerBound(port, consoleId);
            execute(bound.getConnection(), "INSERT INTO tx_myisam(val) VALUES ('not-transactional')");
            assertEquals(ConnectionPool.TransactionOutcome.ROLLED_BACK, ConnectionPool.rollback(consoleId));
            assertEquals(1, count(observer, "tx_myisam"));
        } finally {
            ConnectionPool.release(consoleId, true);
        }
    }

    @ParameterizedTest(name = "MySQL DDL warning ordering on port {0}")
    @ValueSource(ints = {3357, 3380})
    void warnsBeforeDdlImplicitlyCommitsPendingDml(int port) throws Exception {
        assumeMysqlAvailable(port);
        prepareSchema(port);
        long consoleId = nextConsoleId();
        List<String> events = new ArrayList<>();
        try (Connection observer = open(port)) {
            ConnectInfo bound = registerBound(port, consoleId);
            execute(bound.getConnection(), "INSERT INTO tx_innodb(val) VALUES ('before-ddl')");
            assertEquals(0, count(observer, "tx_innodb"));
            Chat2DBContext.putContext(bound);

            SqlExecuteRequest request = request(consoleId,
                    "CREATE TABLE tx_ddl_marker(id INT PRIMARY KEY) ENGINE=InnoDB");
            DefaultSQLExecutor.getInstance().executeStreaming(
                    request,
                    noOpConsumer(),
                    warningListener(() -> {
                        events.add("warning");
                        assertEquals(0, count(observer, "tx_innodb"));
                        assertFalse(tableExists(observer, "tx_ddl_marker"));
                    }),
                    () -> false
            );

            assertEquals(List.of("warning"), events);
            assertTrue(tableExists(observer, "tx_ddl_marker"));
            assertEquals(1, count(observer, "tx_innodb"));
        } finally {
            Chat2DBContext.removeContext();
            ConnectionPool.release(consoleId, true);
        }
    }

    private static ConnectInfo registerBound(int port, long consoleId) throws SQLException {
        Connection connection = open(port);
        connection.setAutoCommit(false);
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConsoleId(consoleId);
        connectInfo.setDbType("MYSQL");
        connectInfo.setDatabaseName("c2d_tx_test");
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        assertTrue(ConnectionPool.registerIfAbsent(
                consoleId,
                connectInfo,
                TransactionIsolationLevel.DEFAULT,
                List.of(TransactionIsolationLevel.DEFAULT)
        ));
        return connectInfo;
    }

    private static SqlExecuteRequest request(long consoleId, String sql) {
        SqlExecuteRequest request = new SqlExecuteRequest();
        request.setScript(sql);
        request.setConsoleId(consoleId);
        request.setDataSourceId(1L);
        request.setDatabaseName("c2d_tx_test");
        request.setPageNo(1);
        request.setPageSize(100);
        return request;
    }

    private static ISqlExecutionStatementListener warningListener(SqlRunnable assertion) {
        return new ISqlExecutionStatementListener() {
            @Override
            public void onStatementCreated(Statement statement) {
            }

            @Override
            public void onStatementClosed(Statement statement) {
            }

            @Override
            public void onImplicitCommitWarning(String sql) {
                try {
                    assertion.run();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        };
    }

    private static ISqlExecutionResultConsumer noOpConsumer() {
        return new ISqlExecutionResultConsumer() {
            @Override
            public void statementStarted(String sql, String originalSql, String comment) {
            }

            @Override
            public void resultStarted(ExecuteResponse result) {
            }

            @Override
            public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
            }

            @Override
            public void resultFinished(ExecuteResponse result) {
            }

            @Override
            public void updateCount(ExecuteResponse result) {
            }

            @Override
            public void statementFinished(String sql, long duration) {
            }
        };
    }

    private static void prepareSchema(int port) throws SQLException {
        try (Connection connection = open(port); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tx_ddl_marker");
            statement.execute("CREATE TABLE IF NOT EXISTS tx_innodb "
                    + "(id BIGINT AUTO_INCREMENT PRIMARY KEY, val VARCHAR(255)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS tx_myisam "
                    + "(id BIGINT AUTO_INCREMENT PRIMARY KEY, val VARCHAR(255)) ENGINE=MyISAM");
            statement.execute("TRUNCATE TABLE tx_innodb");
            statement.execute("TRUNCATE TABLE tx_myisam");
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(
                "c2d_tx_test", null, table, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private static Connection open(int port) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + port
                        + "/c2d_tx_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "root",
                "chat2db"
        );
    }

    private static void assumeMysqlAvailable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        } catch (Exception e) {
            assumeTrue(false, "MySQL test fixture is not running on port " + port);
        }
    }

    private static long nextConsoleId() {
        return CONSOLE_IDS.incrementAndGet();
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }
}
