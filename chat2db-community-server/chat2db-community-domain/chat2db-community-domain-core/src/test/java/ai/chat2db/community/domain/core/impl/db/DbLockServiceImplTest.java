package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.lock.LockView;
import ai.chat2db.community.domain.api.model.lock.LockView.DataLock;
import ai.chat2db.community.domain.api.model.lock.LockView.ErrorCode;
import ai.chat2db.community.domain.api.model.lock.LockView.ErrorSection;
import ai.chat2db.community.domain.api.model.lock.LockView.LockWait;
import ai.chat2db.community.domain.api.model.lock.LockView.MetadataLock;
import ai.chat2db.community.domain.api.model.lock.LockView.Source;
import ai.chat2db.community.domain.api.model.lock.LockView.WaitChain;
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
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

        LockView view = service.lockView(21L);

        assertEquals(21L, view.getDataSourceId());
        assertEquals(Source.UNAVAILABLE, view.getSource());
        assertEquals(List.of(), view.getDataLocks());
        assertEquals(List.of(), view.getWaits());
        assertEquals(List.of(), view.getMetaLocks());
        assertEquals(List.of(), view.getWaitChains());
        assertEquals(List.of(ErrorSection.DATA_LOCKS, ErrorSection.WAITS,
                        ErrorSection.METADATA_LOCKS, ErrorSection.SESSIONS),
                view.getErrors().stream().map(LockView.ViewError::getSection).toList());
        assertTrue(view.getErrors().stream().allMatch(error -> error.getCode() == ErrorCode.PRIVILEGE_REQUIRED));
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

        LockView first = service.lockView(31L);
        Chat2DBContext.removeContext();
        bindContext(32L);
        LockView second = service.lockView(32L);

        assertEquals(31L, first.getDataSourceId());
        assertEquals(32L, second.getDataSourceId());
        assertEquals(2, dbManager.connectionRequests);
    }

    @Test
    void buildsPerformanceSchemaGraphForMultipleRootsCyclesDisconnectedAndStaleSessions() {
        bindContext(41L);
        dbManager.sqlRows = Map.of(
                "performance_schema.data_locks", List.of(
                        row("ENGINE_LOCK_ID", "l-100", "ENGINE_TRANSACTION_ID", "100", "THREAD_ID", "10",
                                "EVENT_ID", "1", "OBJECT_SCHEMA", "app", "OBJECT_NAME", "orders",
                                "INDEX_NAME", "PRIMARY", "LOCK_TYPE", "RECORD", "LOCK_MODE", "X",
                                "LOCK_STATUS", "GRANTED", "LOCK_DATA", "1"),
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
                                "REQUESTING_THREAD_ID", "10", "REQUESTING_EVENT_ID", "1",
                                "BLOCKING_ENGINE_LOCK_ID", "l-200", "BLOCKING_ENGINE_TRANSACTION_ID", "200",
                                "BLOCKING_THREAD_ID", "20", "BLOCKING_EVENT_ID", "2"),
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
                        row("OBJECT_TYPE", "TABLE", "OBJECT_SCHEMA", "app", "OBJECT_NAME", "orders",
                                "OBJECT_INSTANCE_BEGIN", "1001", "LOCK_TYPE", "SHARED_READ",
                                "LOCK_DURATION", "TRANSACTION", "LOCK_STATUS", "GRANTED", "OWNER_THREAD_ID", "30"),
                        row("OBJECT_TYPE", "TABLE", "OBJECT_SCHEMA", "app", "OBJECT_NAME", "customers",
                                "OBJECT_INSTANCE_BEGIN", "1002", "LOCK_TYPE", "EXCLUSIVE",
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

        LockView view = service.lockView(41L);

        assertEquals(Source.PERFORMANCE_SCHEMA, view.getSource());
        assertEquals(8, view.getDataLocks().size());
        assertEquals(7, view.getSessions().size());
        DataLock dataLock = view.getDataLocks().get(0);
        assertEquals("app", dataLock.getObjectSchema());
        assertEquals("orders", dataLock.getObjectName());
        assertEquals("PRIMARY", dataLock.getIndexName());
        assertEquals("RECORD", dataLock.getLockType());
        assertEquals("X", dataLock.getLockMode());
        assertEquals("GRANTED", dataLock.getLockStatus());
        assertEquals("1", dataLock.getLockData());
        LockWait wait = view.getWaits().get(0);
        assertEquals("1", wait.getWaiterEventId());
        assertEquals("2", wait.getBlockerEventId());
        List<WaitChain> chains = view.getWaitChains();
        assertEquals(6, chains.size());
        WaitChain firstHop = chain(chains, "100", "200");
        assertNotNull(firstHop);
        assertFalse(firstHop.isRootBlocker());
        assertFalse(firstHop.isCycle());
        assertEquals("102", firstHop.getBlockerThreadId());
        WaitChain directRoot = chain(chains, "100", "300");
        assertTrue(directRoot.isRootBlocker());
        assertEquals(1, directRoot.getBlockerMetadataLockCount());
        WaitChain secondRoot = chain(chains, "200", "400");
        assertTrue(secondRoot.isRootBlocker());
        assertEquals(1, secondRoot.getBlockerMetadataLockCount());
        assertEquals(List.of("GRANTED", "PENDING"), view.getMetaLocks().stream()
                .map(MetadataLock::getLockStatus)
                .toList());
        assertEquals(List.of("1001", "1002"), view.getMetaLocks().stream()
                .map(MetadataLock::getObjectInstanceId)
                .toList());
        WaitChain cycle = chain(chains, "500", "600");
        assertTrue(cycle.isCycle());
        assertFalse(cycle.isRootBlocker());
        WaitChain stale = chain(chains, "800", "900");
        assertFalse(stale.isBlockerSessionAvailable());
        assertEquals("90", stale.getBlockerThreadId());
        assertEquals(41L, stale.getDataSourceId());
    }

    @Test
    void fallsBackToLegacyInnoDbWaitsWhileKeepingThreadMetadataCorrelation() {
        bindContext(42L);
        dbManager.sqlFailures = Map.of("SELECT 1 FROM performance_schema.data_locks", new SQLException("Unknown table"));
        dbManager.sqlRows = Map.of(
                "information_schema.innodb_locks", List.of(
                        row("lock_id", "legacy-waiter", "lock_trx_id", "trx-a", "lock_mode", "X",
                                "lock_type", "RECORD", "lock_table", "`legacy`.`orders`",
                                "lock_index", "PRIMARY", "lock_space", "7", "lock_page", "8",
                                "lock_rec", "9", "lock_data", "1"),
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

        LockView view = service.lockView(42L);

        assertEquals(Source.INFORMATION_SCHEMA, view.getSource());
        DataLock dataLock = view.getDataLocks().get(0);
        assertEquals("`legacy`.`orders`", dataLock.getObjectName());
        assertEquals("PRIMARY", dataLock.getIndexName());
        assertEquals("7", dataLock.getSpaceId());
        assertEquals("8", dataLock.getPageId());
        assertEquals("9", dataLock.getRecordId());
        WaitChain chain = view.getWaitChains().get(0);
        assertEquals("701", chain.getBlockerThreadId());
        assertEquals("blocker", chain.getBlockerUser());
        assertEquals(1, chain.getBlockerMetadataLockCount());
        assertTrue(chain.isRootBlocker());
        assertTrue(view.getErrors().isEmpty());
        assertEquals("701", view.getMetaLocks().get(0).getOwnerSessionId());
    }

    private static WaitChain chain(List<WaitChain> chains, String waiterTrx, String blockerTrx) {
        return chains.stream()
                .filter(row -> waiterTrx.equals(row.getWaiterTransactionId())
                        && blockerTrx.equals(row.getBlockerTransactionId()))
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
        Set<String> selectedColumns = selectedColumns(sql);
        int[] current = {-1};
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++current[0] < rows.size();
            case "getString" -> {
                String column = String.valueOf(args[0]);
                if (!selectedColumns.contains(column)) {
                    throw new SQLException("Column was not selected: " + column);
                }
                Object value = rows.get(current[0]).get(column);
                yield value == null ? null : String.valueOf(value);
            }
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

    private static Set<String> selectedColumns(String sql) {
        String upperSql = sql.toUpperCase();
        int projectionStart = upperSql.indexOf("SELECT ") + "SELECT ".length();
        int projectionEnd = upperSql.indexOf(" FROM ", projectionStart);
        return Arrays.stream(sql.substring(projectionStart, projectionEnd).split(","))
                .map(String::trim)
                .map(DbLockServiceImplTest::columnLabel)
                .collect(Collectors.toSet());
    }

    private static String columnLabel(String expression) {
        int aliasIndex = expression.toUpperCase().lastIndexOf(" AS ");
        if (aliasIndex >= 0) {
            return expression.substring(aliasIndex + " AS ".length()).trim();
        }
        int qualifierIndex = expression.lastIndexOf('.');
        return expression.substring(qualifierIndex + 1).trim();
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
