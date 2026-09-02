package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbLockService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lock inspection using Performance Schema on MySQL 8.0 and the legacy InnoDB
 * information_schema tables on 5.7, plus metadata locks when instrumented.
 */
@Slf4j
@Service
public class DbLockServiceImpl implements IDbLockService {

    private static final String SQL_DATA_LOCKS_8 =
            "SELECT ENGINE_LOCK_ID, ENGINE_TRANSACTION_ID, THREAD_ID, EVENT_ID, OBJECT_SCHEMA, OBJECT_NAME, "
                    + "INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA "
                    + "FROM performance_schema.data_locks ORDER BY ENGINE_LOCK_ID";
    private static final String SQL_DATA_LOCK_WAITS_8 =
            "SELECT REQUESTING_ENGINE_LOCK_ID, REQUESTING_ENGINE_TRANSACTION_ID, "
                    + "REQUESTING_THREAD_ID, REQUESTING_EVENT_ID, BLOCKING_ENGINE_LOCK_ID, "
                    + "BLOCKING_ENGINE_TRANSACTION_ID, BLOCKING_THREAD_ID, BLOCKING_EVENT_ID "
                    + "FROM performance_schema.data_lock_waits";
    private static final String SQL_DATA_LOCKS_57 =
            "SELECT lock_id, lock_trx_id, lock_mode, lock_type, lock_table, lock_index, "
                    + "lock_space, lock_page, lock_rec, lock_data "
                    + "FROM information_schema.innodb_locks ORDER BY lock_id";
    private static final String SQL_DATA_LOCK_WAITS_57 =
            "SELECT requesting_trx_id, requested_lock_id, blocking_trx_id, blocking_lock_id "
                    + "FROM information_schema.innodb_lock_waits";
    private static final String SQL_METADATA_LOCKS =
            "SELECT OBJECT_SCHEMA, OBJECT_NAME, LOCK_TYPE, LOCK_DURATION, LOCK_STATUS, OWNER_THREAD_ID, OWNER_EVENT_ID "
                    + "FROM performance_schema.metadata_locks ORDER BY OBJECT_SCHEMA, OBJECT_NAME";
    private static final String SQL_SESSION_INFO_PS =
            "SELECT th.THREAD_ID, th.PROCESSLIST_ID, th.PROCESSLIST_USER, th.PROCESSLIST_HOST, "
                    + "th.PROCESSLIST_DB, th.PROCESSLIST_COMMAND, th.PROCESSLIST_TIME, "
                    + "th.PROCESSLIST_STATE, th.PROCESSLIST_INFO, t.trx_id, t.trx_mysql_thread_id, "
                    + "t.trx_state, t.trx_query "
                    + "FROM performance_schema.threads th "
                    + "LEFT JOIN information_schema.innodb_trx t ON t.trx_mysql_thread_id = th.PROCESSLIST_ID "
                    + "WHERE th.PROCESSLIST_ID IS NOT NULL";
    private static final String SQL_SESSION_INFO_57 =
            "SELECT t.trx_id, t.trx_mysql_thread_id, t.trx_state, p.USER, p.HOST, p.DB, t.trx_query "
                    + "FROM information_schema.innodb_trx t "
                    + "LEFT JOIN information_schema.processlist p ON t.trx_mysql_thread_id = p.ID";

    @Override
    public Map<String, Object> lockView(Long dataSourceId) {
        requireDatasourceContext(dataSourceId);
        Connection connection = Chat2DBContext.getConnection();
        List<Map<String, Object>> errors = new ArrayList<>();
        LockSourceProbe probe = probeLockSource(connection);
        boolean ps = probe.performanceSchema();

        List<Map<String, Object>> dataLocks;
        List<Map<String, Object>> waits;
        if (probe.failure() == null) {
            dataLocks = queryRows(connection, ps ? SQL_DATA_LOCKS_8 : SQL_DATA_LOCKS_57, "dataLocks", errors);
            waits = queryRows(connection, ps ? SQL_DATA_LOCK_WAITS_8 : SQL_DATA_LOCK_WAITS_57, "waits", errors);
        } else {
            errors.add(error("dataLocks", probe.failure()));
            errors.add(error("waits", probe.failure()));
            dataLocks = List.of();
            waits = List.of();
        }
        List<Map<String, Object>> metaLocks = queryRows(connection, SQL_METADATA_LOCKS, "metaLocks", errors);
        List<Map<String, Object>> sessions = querySessionRows(connection, errors);
        List<Map<String, Object>> enrichedMetaLocks = enrichMetadataLocks(metaLocks, sessions);
        List<Map<String, Object>> waitChains = buildWaitChains(waits, dataLocks, enrichedMetaLocks, sessions, ps,
                dataSourceId);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("dataSourceId", dataSourceId);
        view.put("source", lockSource(ps, errors));
        view.put("dataLocks", dataLocks);
        view.put("waits", waits);
        view.put("metaLocks", enrichedMetaLocks);
        view.put("sessions", sessions);
        view.put("waitChains", waitChains);
        view.put("errors", errors);
        return view;
    }

    private static void requireDatasourceContext(Long dataSourceId) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null || connectInfo.getDataSourceId() == null) {
            throw new BusinessException("datasource.context.required");
        }
        if (!connectInfo.getDataSourceId().equals(dataSourceId)) {
            throw new BusinessException("datasource.context.mismatch");
        }
    }

    private static String lockSource(boolean performanceSchema, List<Map<String, Object>> errors) {
        boolean lockTablesUnavailable = errors.stream()
                .map(error -> error.get("section"))
                .anyMatch(section -> "dataLocks".equals(section) || "waits".equals(section));
        if (lockTablesUnavailable) {
            return "unavailable";
        }
        return performanceSchema ? "performance_schema" : "information_schema";
    }

    /**
     * Detects whether performance_schema.data_locks is queryable (MySQL 8.0 with the
     * data_locks instrumentation enabled); 5.7 falls back to innodb_locks.
     */
    private static LockSourceProbe probeLockSource(Connection connection) {
        try {
            DefaultSQLExecutor.getInstance().execute(connection,
                    "SELECT 1 FROM performance_schema.data_locks LIMIT 1", resultSet -> Boolean.TRUE);
            return new LockSourceProbe(true, null);
        } catch (RuntimeException e) {
            return shouldFallbackToLegacyLocks(e) ? new LockSourceProbe(false, null) : new LockSourceProbe(true, e);
        }
    }

    static boolean shouldFallbackToLegacyLocks(Throwable throwable) {
        Throwable cause = rootCause(throwable);
        if (cause instanceof SQLException sqlException) {
            if (sqlException.getErrorCode() == 1146 || "42S02".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        String message = cause.getMessage();
        return message != null && (message.toLowerCase().contains("unknown table")
                || message.toLowerCase().contains("doesn't exist")
                || message.toLowerCase().contains("does not exist"));
    }

    private static List<Map<String, Object>> queryRows(Connection connection, String sql, String section,
            List<Map<String, Object>> errors) {
        try {
            return queryRows(connection, sql);
        } catch (RuntimeException e) {
            errors.add(error(section, e));
            return List.of();
        }
    }

    private static Map<String, Object> error(String section, RuntimeException exception) {
        Throwable cause = rootCause(exception);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("section", section);
        boolean privilegeError = isPrivilegeError(cause);
        error.put("code", privilegeError ? "privilege_required" : "unavailable");
        error.put("message", privilegeError
                ? "Lock metadata access was denied for the current database account."
                : "Lock metadata is unavailable for this server or database account.");
        return error;
    }

    private static List<Map<String, Object>> querySessionRows(Connection connection, List<Map<String, Object>> errors) {
        try {
            return queryRows(connection, SQL_SESSION_INFO_PS);
        } catch (RuntimeException performanceSchemaException) {
            try {
                return queryRows(connection, SQL_SESSION_INFO_57);
            } catch (RuntimeException informationSchemaException) {
                errors.add(error("sessions", informationSchemaException));
                return List.of();
            }
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isPrivilegeError(Throwable throwable) {
        if (throwable instanceof SQLException sqlException) {
            String sqlState = sqlException.getSQLState();
            if ("42000".equals(sqlState) || "28000".equals(sqlState)) {
                return true;
            }
        }
        String message = throwable.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("denied") || lower.contains("permission") || lower.contains("privilege");
    }

    private record LockSourceProbe(boolean performanceSchema, RuntimeException failure) {
    }

    private static List<Map<String, Object>> queryRows(Connection connection, String sql) {
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    String label = resultSet.getMetaData().getColumnLabel(i);
                    Object value = resultSet.getObject(i);
                    row.put(label, value == null ? null : String.valueOf(value));
                }
                rows.add(row);
            }
            return rows;
        });
    }

    /**
     * Builds waiter -> blocker pairs from the wait graph. Root blockers are graph
     * leaves, so multiple blockers and disconnected components are handled naturally.
     * Cycles have no root; rows whose sessions disappeared mid-refresh remain visible
     * with session availability flags.
     */
    private static List<Map<String, Object>> buildWaitChains(List<Map<String, Object>> waits,
                                                             List<Map<String, Object>> dataLocks,
                                                             List<Map<String, Object>> metaLocks,
                                                             List<Map<String, Object>> sessions, boolean ps,
                                                             Long dataSourceId) {
        SessionIndex sessionIndex = new SessionIndex(sessions);
        Map<String, Map<String, Object>> dataLocksById = indexBy(dataLocks, ps ? "ENGINE_LOCK_ID" : "lock_id");
        Map<String, Integer> metadataLockCountsByThread = metadataLockCountsByThread(metaLocks);
        List<WaitEdge> edges = new ArrayList<>();
        Map<String, Set<String>> outgoing = new HashMap<>();
        for (Map<String, Object> wait : waits) {
            WaitEdge edge = waitEdge(wait, dataLocksById, ps);
            if (edge == null) {
                continue;
            }
            edges.add(edge);
            outgoing.computeIfAbsent(edge.waiterKey(), ignored -> new LinkedHashSet<>()).add(edge.blockerKey());
        }

        List<Map<String, Object>> chains = new ArrayList<>();
        for (WaitEdge edge : edges) {
            Map<String, Object> waiter = sessionIndex.find(edge.waiterTrx(), edge.waiterThread());
            Map<String, Object> blocker = sessionIndex.find(edge.blockerTrx(), edge.blockerThread());
            boolean cycle = reaches(edge.blockerKey(), edge.waiterKey(), outgoing, new HashSet<>());
            boolean blockerIsRoot = !cycle && !outgoing.containsKey(edge.blockerKey());
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("dataSourceId", dataSourceId);
            chain.put("waiterTransactionId", edge.waiterTrx());
            chain.put("waiterLockId", edge.waiterLockId());
            chain.put("waiterThreadId", displayThreadId(waiter, edge.waiterThread()));
            chain.put("waiterEngineThreadId", edge.waiterThread());
            chain.put("waiterState", firstValue(waiter, "trx_state", "PROCESSLIST_STATE"));
            chain.put("waiterUser", firstValue(waiter, "PROCESSLIST_USER", "USER"));
            chain.put("waiterHost", firstValue(waiter, "PROCESSLIST_HOST", "HOST"));
            chain.put("waiterDatabase", firstValue(waiter, "PROCESSLIST_DB", "DB"));
            chain.put("waiterQuery", firstValue(waiter, "trx_query", "PROCESSLIST_INFO"));
            chain.put("waiterSessionAvailable", waiter != null);
            chain.put("waiterMetadataLockCount", metadataLockCount(waiter, edge.waiterThread(),
                    metadataLockCountsByThread));
            chain.put("blockerTransactionId", edge.blockerTrx());
            chain.put("blockerLockId", edge.blockerLockId());
            chain.put("blockerThreadId", displayThreadId(blocker, edge.blockerThread()));
            chain.put("blockerEngineThreadId", edge.blockerThread());
            chain.put("blockerState", firstValue(blocker, "trx_state", "PROCESSLIST_STATE"));
            chain.put("blockerUser", firstValue(blocker, "PROCESSLIST_USER", "USER"));
            chain.put("blockerHost", firstValue(blocker, "PROCESSLIST_HOST", "HOST"));
            chain.put("blockerDatabase", firstValue(blocker, "PROCESSLIST_DB", "DB"));
            chain.put("blockerQuery", firstValue(blocker, "trx_query", "PROCESSLIST_INFO"));
            chain.put("blockerSessionAvailable", blocker != null);
            chain.put("blockerMetadataLockCount", metadataLockCount(blocker, edge.blockerThread(),
                    metadataLockCountsByThread));
            chain.put("rootBlocker", blockerIsRoot);
            chain.put("cycle", cycle);
            chains.add(chain);
        }
        return chains;
    }

    private static WaitEdge waitEdge(Map<String, Object> wait, Map<String, Map<String, Object>> dataLocksById,
            boolean ps) {
        String waiterLockId = stringValue(wait, ps ? "REQUESTING_ENGINE_LOCK_ID" : "requested_lock_id");
        String blockerLockId = stringValue(wait, ps ? "BLOCKING_ENGINE_LOCK_ID" : "blocking_lock_id");
        Map<String, Object> waiterLock = dataLocksById.get(waiterLockId);
        Map<String, Object> blockerLock = dataLocksById.get(blockerLockId);
        String waiterTrx = firstStringValue(wait, ps ? "REQUESTING_ENGINE_TRANSACTION_ID" : "requesting_trx_id",
                waiterLock, ps ? "ENGINE_TRANSACTION_ID" : "lock_trx_id");
        String blockerTrx = firstStringValue(wait, ps ? "BLOCKING_ENGINE_TRANSACTION_ID" : "blocking_trx_id",
                blockerLock, ps ? "ENGINE_TRANSACTION_ID" : "lock_trx_id");
        String waiterThread = firstStringValue(wait, "REQUESTING_THREAD_ID", waiterLock, "THREAD_ID");
        String blockerThread = firstStringValue(wait, "BLOCKING_THREAD_ID", blockerLock, "THREAD_ID");
        String waiterKey = identityKey(waiterTrx, waiterThread, waiterLockId);
        String blockerKey = identityKey(blockerTrx, blockerThread, blockerLockId);
        if (waiterKey == null || blockerKey == null) {
            return null;
        }
        return new WaitEdge(waiterKey, blockerKey, waiterTrx, blockerTrx, waiterThread, blockerThread, waiterLockId,
                blockerLockId);
    }

    private static String identityKey(String trxId, String threadId, String lockId) {
        if (trxId != null) {
            return "trx:" + trxId;
        }
        if (threadId != null) {
            return "thread:" + threadId;
        }
        if (lockId != null) {
            return "lock:" + lockId;
        }
        return null;
    }

    private static Map<String, Map<String, Object>> indexBy(List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> index = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String value = stringValue(row, key);
            if (value != null) {
                index.put(value, row);
            }
        }
        return index;
    }

    private static Map<String, Integer> metadataLockCountsByThread(List<Map<String, Object>> metaLocks) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> metaLock : metaLocks) {
            String threadId = stringValue(metaLock, "OWNER_THREAD_ID");
            if (threadId != null) {
                counts.merge(threadId, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int metadataLockCount(Map<String, Object> session, String fallbackThreadId,
            Map<String, Integer> metadataLockCountsByThread) {
        String threadId = session == null ? fallbackThreadId : firstValue(session, "THREAD_ID", "OWNER_THREAD_ID");
        if (threadId == null) {
            return 0;
        }
        return metadataLockCountsByThread.getOrDefault(threadId, 0);
    }

    private static List<Map<String, Object>> enrichMetadataLocks(List<Map<String, Object>> metaLocks,
            List<Map<String, Object>> sessions) {
        SessionIndex sessionIndex = new SessionIndex(sessions);
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Map<String, Object> metaLock : metaLocks) {
            Map<String, Object> row = new LinkedHashMap<>(metaLock);
            Map<String, Object> session = sessionIndex.find(null, stringValue(metaLock, "OWNER_THREAD_ID"));
            row.put("OWNER_PROCESSLIST_ID", firstValue(session, "PROCESSLIST_ID", "trx_mysql_thread_id"));
            row.put("OWNER_USER", firstValue(session, "PROCESSLIST_USER", "USER"));
            row.put("OWNER_HOST", firstValue(session, "PROCESSLIST_HOST", "HOST"));
            row.put("OWNER_DB", firstValue(session, "PROCESSLIST_DB", "DB"));
            row.put("OWNER_STATE", firstValue(session, "trx_state", "PROCESSLIST_STATE"));
            row.put("OWNER_QUERY", firstValue(session, "trx_query", "PROCESSLIST_INFO"));
            row.put("OWNER_SESSION_AVAILABLE", session != null);
            enriched.add(row);
        }
        return enriched;
    }

    private static boolean reaches(String from, String target, Map<String, Set<String>> outgoing, Set<String> seen) {
        if (from.equals(target)) {
            return true;
        }
        if (!seen.add(from)) {
            return false;
        }
        for (String next : outgoing.getOrDefault(from, Set.of())) {
            if (reaches(next, target, outgoing, seen)) {
                return true;
            }
        }
        return false;
    }

    private static String displayThreadId(Map<String, Object> session, String fallbackThreadId) {
        String processlistId = firstValue(session, "PROCESSLIST_ID", "trx_mysql_thread_id");
        return processlistId == null ? fallbackThreadId : processlistId;
    }

    private static String firstValue(Map<String, Object> row, String... keys) {
        if (row == null) {
            return null;
        }
        for (String key : keys) {
            String value = stringValue(row, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstStringValue(Map<String, Object> primaryRow, String primaryKey,
            Map<String, Object> fallbackRow, String fallbackKey) {
        String primary = stringValue(primaryRow, primaryKey);
        return primary == null ? stringValue(fallbackRow, fallbackKey) : primary;
    }

    private static String stringValue(Map<String, Object> row, String key) {
        if (row == null) {
            return null;
        }
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private record WaitEdge(String waiterKey, String blockerKey, String waiterTrx, String blockerTrx,
                            String waiterThread, String blockerThread, String waiterLockId, String blockerLockId) {
    }

    private static final class SessionIndex {

        private final Map<String, Map<String, Object>> byTrx = new HashMap<>();

        private final Map<String, Map<String, Object>> byThread = new HashMap<>();

        private final Map<String, Map<String, Object>> byProcesslist = new HashMap<>();

        private SessionIndex(List<Map<String, Object>> sessions) {
            for (Map<String, Object> session : sessions) {
                put(byTrx, firstValue(session, "trx_id"), session);
                put(byThread, firstValue(session, "THREAD_ID"), session);
                put(byProcesslist, firstValue(session, "PROCESSLIST_ID", "trx_mysql_thread_id"), session);
            }
        }

        private Map<String, Object> find(String trxId, String threadId) {
            Map<String, Object> session = trxId == null ? null : byTrx.get(trxId);
            if (session == null && threadId != null) {
                session = byThread.get(threadId);
            }
            if (session == null && threadId != null) {
                session = byProcesslist.get(threadId);
            }
            return session;
        }

        private static void put(Map<String, Map<String, Object>> index, String key, Map<String, Object> row) {
            if (key != null) {
                index.put(key, row);
            }
        }
    }
}
