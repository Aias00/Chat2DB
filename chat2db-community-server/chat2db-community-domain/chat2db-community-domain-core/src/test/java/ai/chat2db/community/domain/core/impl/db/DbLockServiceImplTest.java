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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertTrue(errors.stream().noneMatch(error -> String.valueOf(error.get("message")).contains("SELECT command")));
    }

    @Test
    void fallsBackOnlyWhenPerformanceSchemaLockTableIsMissing() {
        assertTrue(DbLockServiceImpl.shouldFallbackToLegacyLocks(
                new SQLException("Unknown table 'performance_schema.data_locks'", "42S02", 1146)));
        assertFalse(DbLockServiceImpl.shouldFallbackToLegacyLocks(
                new SQLException("SELECT command denied for data_locks", "42000", 1142)));
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

    @Test
    void buildsPerformanceSchemaGraphForMultipleRootsCyclesDisconnectedAndStaleSessions() {
        bindContext(41L);
        dbManager.sqlRows = Map.of(
                "performance_schema.data_locks", List.of(
                        row("ENGINE_LOCK_ID", "l-100", "ENGINE_TRANSACTION_ID", "100", "THREAD_ID", "10"),
                        row("ENGINE_LOCK_ID", "l-200", "ENGINE_TRANSACTION_ID", "200", "THREAD_ID", "20"),
                        row("ENGINE_LOCK_ID", "l-300", "ENGINE_TRANSACTION_ID", "300", "THREAD_ID", "30"),
                        row("ENGINE_LOCK_ID", "l-400", "ENGINE_TRANSACTION_ID", "400", "THREAD_ID", "40"),
                        row("ENGINE_LOCK_ID", "l-500", "ENGINE_TRANSACTION_ID", "500", "THREAD_ID", "50"),
                        row("ENGINE_LOCK_ID", "l-600", "ENGINE_TRANSACTION_ID", "600", "THREAD_ID", "60"),
                        row("ENGINE_LOCK_ID", "l-800", "ENGINE_TRANSACTION_ID", "800", "THREAD_ID", "80"),
                        row("ENGINE_LOCK_ID", "l-900", "ENGINE_TRANSACTION_ID", "900", "THREAD_ID", "90")
                ),
                "performance_schema.data_lock_waits", List.of(
                        row("REQUESTING_ENGINE_LOCK_ID", "l-100", "REQUESTING_ENGINE_TRANSACTION_ID", "100",
                                "REQUESTING_THREAD_ID", "10", "BLOCKING_ENGINE_LOCK_ID", "l-200",
                                "BLOCKING_ENGINE_TRANSACTION_ID", "200", "BLOCKING_THREAD_ID", "20"),
                        row("REQUESTING_ENGINE_LOCK_ID", "l-100", "REQUESTING_ENGINE_TRANSACTION_ID", "100",
                                "REQUESTING_THREAD_ID", "10", "BLOCKING_ENGINE_LOCK_ID", "l-300",
                                "BLOCKING_ENGINE_TRANSACTION_ID", "300", "BLOCKING_THREAD_ID", "30"),
                        row("REQUESTING_ENGINE_LOCK_ID", "l-200", "REQUESTING_ENGINE_TRANSACTION_ID", "200",
                                "REQUESTING_THREAD_ID", "20", "BLOCKING_ENGINE_LOCK_ID", "l-400",
                                "BLOCKING_ENGINE_TRANSACTION_ID", "400", "BLOCKING_THREAD_ID", "40"),
                        row("REQUESTING_ENGINE_LOCK_ID", "l-500", "REQUESTING_ENGINE_TRANSACTION_ID", "500",
                                "REQUESTING_THREAD_ID", "50", "BLOCKING_ENGINE_LOCK_ID", "l-600",
                                "BLOCKING_ENGINE_TRANSACTION_ID", "600", "BLOCKING_THREAD_ID", "60"),
                        row("REQUESTING_ENGINE_LOCK_ID", "l-600", "REQUESTING_ENGINE_TRANSACTION_ID", "600",
                                "REQUESTING_THREAD_ID", "60", "BLOCKING_ENGINE_LOCK_ID", "l-500",
                                "BLOCKING_ENGINE_TRANSACTION_ID", "500", "BLOCKING_THREAD_ID", "50"),
                        row("REQUESTING_ENGINE_LOCK_ID", "l-800", "REQUESTING_ENGINE_TRANSACTION_ID", "800",
                                "REQUESTING_THREAD_ID", "80", "BLOCKING_ENGINE_LOCK_ID", "l-900",
                                "BLOCKING_ENGINE_TRANSACTION_ID", "900", "BLOCKING_THREAD_ID", "90")
                ),
                "performance_schema.metadata_locks", List.of(
                        row("OBJECT_SCHEMA", "app", "OBJECT_NAME", "orders", "LOCK_TYPE", "SHARED_READ",
                                "LOCK_DURATION", "TRANSACTION", "LOCK_STATUS", "GRANTED", "OWNER_THREAD_ID", "30"),
                        row("OBJECT_SCHEMA", "app", "OBJECT_NAME", "customers", "LOCK_TYPE", "EXCLUSIVE",
                                "LOCK_DURATION", "TRANSACTION", "LOCK_STATUS", "PENDING", "OWNER_THREAD_ID", "40")
                ),
                "performance_schema.threads", List.of(
                        session("10", "101", "100", "alice", "client-a", "app", "LOCK WAIT", "wait 100"),
                        session("20", "102", "200", "bob", "client-b", "app", "LOCK WAIT", "wait 200"),
                        session("30", "103", "300", "carol", "client-c", "app", "executing", "update root a"),
                        session("40", "104", "400", "dave", "client-d", "app", "executing", "update root b"),
                        session("50", "105", "500", "erin", "client-e", "app", "LOCK WAIT", "cycle a"),
                        session("60", "106", "600", "frank", "client-f", "app", "LOCK WAIT", "cycle b"),
                        session("80", "108", "800", "gina", "client-g", "app", "LOCK WAIT", "wait stale")
                )
        );
        DbLockServiceImpl service = new DbLockServiceImpl();

        Map<String, Object> view = service.lockView(41L);

        assertEquals("performance_schema", view.get("source"));
        assertEquals(8, rows(view, "dataLocks").size());
        assertEquals(7, rows(view, "sessions").size());
        List<Map<String, Object>> chains = rows(view, "waitChains");
        assertEquals(6, chains.size());
        Map<String, Object> firstHop = chain(chains, "100", "200");
        assertNotNull(firstHop);
        assertFalse((Boolean) firstHop.get("rootBlocker"));
        assertFalse((Boolean) firstHop.get("cycle"));
        assertEquals("102", firstHop.get("blockerThreadId"));
        Map<String, Object> directRoot = chain(chains, "100", "300");
        assertTrue((Boolean) directRoot.get("rootBlocker"));
        assertEquals(1, directRoot.get("blockerMetadataLockCount"));
        Map<String, Object> secondRoot = chain(chains, "200", "400");
        assertTrue((Boolean) secondRoot.get("rootBlocker"));
        assertEquals(1, secondRoot.get("blockerMetadataLockCount"));
        assertEquals(List.of("GRANTED", "PENDING"), rows(view, "metaLocks").stream()
                .map(row -> row.get("LOCK_STATUS"))
                .toList());
        Map<String, Object> cycle = chain(chains, "500", "600");
        assertTrue((Boolean) cycle.get("cycle"));
        assertFalse((Boolean) cycle.get("rootBlocker"));
        Map<String, Object> stale = chain(chains, "800", "900");
        assertFalse((Boolean) stale.get("blockerSessionAvailable"));
        assertEquals("90", stale.get("blockerThreadId"));
        assertEquals(41L, stale.get("dataSourceId"));
    }

    @Test
    void fallsBackToLegacyInnoDbWaitsWhileKeepingThreadMetadataCorrelation() {
        bindContext(42L);
        dbManager.sqlFailures = Map.of("SELECT 1 FROM performance_schema.data_locks", new SQLException("Unknown table"));
        dbManager.sqlRows = Map.of(
                "information_schema.innodb_locks", List.of(
                        row("lock_id", "legacy-waiter", "lock_trx_id", "trx-a"),
                        row("lock_id", "legacy-blocker", "lock_trx_id", "trx-b")
                ),
                "information_schema.innodb_lock_waits", List.of(
                        row("requesting_trx_id", "trx-a", "requested_lock_id", "legacy-waiter",
                                "blocking_trx_id", "trx-b", "blocking_lock_id", "legacy-blocker")
                ),
                "performance_schema.metadata_locks", List.of(
                        row("OBJECT_SCHEMA", "legacy", "OBJECT_NAME", "orders", "LOCK_TYPE", "EXCLUSIVE",
                                "LOCK_DURATION", "TRANSACTION", "OWNER_THREAD_ID", "70")
                ),
                "performance_schema.threads", List.of(
                        session("60", "601", "trx-a", "waiter", "host-a", "legacy", "LOCK WAIT", "wait legacy"),
                        session("70", "701", "trx-b", "blocker", "host-b", "legacy", "executing", "root legacy")
                )
        );
        DbLockServiceImpl service = new DbLockServiceImpl();

        Map<String, Object> view = service.lockView(42L);

        assertEquals("information_schema", view.get("source"));
        Map<String, Object> chain = rows(view, "waitChains").get(0);
        assertEquals("701", chain.get("blockerThreadId"));
        assertEquals("blocker", chain.get("blockerUser"));
        assertEquals(1, chain.get("blockerMetadataLockCount"));
        assertTrue((Boolean) chain.get("rootBlocker"));
        assertTrue((Boolean) rows(view, "errors").isEmpty());
        assertEquals("701", rows(view, "metaLocks").get(0).get("OWNER_PROCESSLIST_ID"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("errors");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> view, String section) {
        return (List<Map<String, Object>>) view.get(section);
    }

    private static Map<String, Object> chain(List<Map<String, Object>> chains, String waiterTrx, String blockerTrx) {
        return chains.stream()
                .filter(row -> waiterTrx.equals(row.get("waiterTransactionId"))
                        && blockerTrx.equals(row.get("blockerTransactionId")))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> session(String threadId, String processlistId, String trxId, String user,
            String host, String db, String state, String query) {
        return row("THREAD_ID", threadId, "PROCESSLIST_ID", processlistId, "PROCESSLIST_USER", user,
                "PROCESSLIST_HOST", host, "PROCESSLIST_DB", db, "PROCESSLIST_STATE", state, "PROCESSLIST_INFO", query,
                "trx_id", trxId, "trx_mysql_thread_id", processlistId, "trx_state", state, "trx_query", query);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put((String) values[i], values[i + 1]);
        }
        return row;
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

        private Map<String, List<Map<String, Object>>> sqlRows = Map.of();

        @Override
        public Connection getConnection(ConnectInfo connectInfo) {
            connectionRequests++;
            return connection(sqlFailures, sqlRows);
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

    private static Connection connection(Map<String, SQLException> sqlFailures, Map<String, List<Map<String, Object>>> sqlRows) {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "isClosed" -> false;
            case "prepareStatement" -> statement((String) args[0], sqlFailures, sqlRows);
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static PreparedStatement statement(String sql, Map<String, SQLException> sqlFailures,
            Map<String, List<Map<String, Object>>> sqlRows) {
        ResultSet[] resultSet = new ResultSet[1];
        return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "execute" -> {
                resultSet[0] = resultSet(sql, sqlFailures, sqlRows);
                yield true;
            }
            case "getResultSet" -> resultSet[0];
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(String sql, Map<String, SQLException> sqlFailures,
            Map<String, List<Map<String, Object>>> sqlRows) throws SQLException {
        for (Map.Entry<String, SQLException> entry : sqlFailures.entrySet()) {
            if (sql.contains(entry.getKey())) {
                throw entry.getValue();
            }
        }
        List<Map<String, Object>> rows = rowsForSql(sql, sqlRows);
        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        int[] current = {-1};
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++current[0] < rows.size();
            case "getMetaData" -> resultSetMetaData(columns);
            case "getObject" -> rows.get(current[0]).get(columns.get((Integer) args[0] - 1));
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static List<Map<String, Object>> rowsForSql(String sql, Map<String, List<Map<String, Object>>> sqlRows) {
        return sqlRows.entrySet().stream()
                .filter(entry -> sql.contains(entry.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(List.of());
    }

    private static ResultSetMetaData resultSetMetaData(List<String> columns) {
        return proxy(ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> columns.size();
            case "getColumnLabel" -> columns.get((Integer) args[0] - 1);
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
