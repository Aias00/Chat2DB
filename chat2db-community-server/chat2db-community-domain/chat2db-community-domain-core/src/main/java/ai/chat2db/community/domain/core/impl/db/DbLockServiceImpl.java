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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lock inspection using Performance Schema on MySQL 8.0 and the legacy InnoDB
 * information_schema tables on 5.7, plus metadata locks when instrumented.
 */
@Slf4j
@Service
public class DbLockServiceImpl implements IDbLockService {

    private static final String SQL_DATA_LOCKS_8 =
            "SELECT ENGINE_LOCK_ID, ENGINE_TRANSACTION_ID, THREAD_ID, OBJECT_SCHEMA, OBJECT_NAME, "
                    + "INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA "
                    + "FROM performance_schema.data_locks ORDER BY ENGINE_LOCK_ID";
    private static final String SQL_DATA_LOCK_WAITS_8 =
            "SELECT REQUESTING_ENGINE_LOCK_ID, REQUESTING_ENGINE_TRANSACTION_ID, "
                    + "BLOCKING_ENGINE_LOCK_ID, BLOCKING_ENGINE_TRANSACTION_ID "
                    + "FROM performance_schema.data_lock_waits";
    private static final String SQL_DATA_LOCKS_57 =
            "SELECT lock_id, lock_trx_id, lock_mode, lock_type, lock_table, lock_index, "
                    + "lock_space, lock_page, lock_rec, lock_data "
                    + "FROM information_schema.innodb_locks ORDER BY lock_id";
    private static final String SQL_DATA_LOCK_WAITS_57 =
            "SELECT requesting_trx_id, requested_lock_id, blocking_trx_id, blocking_lock_id "
                    + "FROM information_schema.innodb_lock_waits";
    private static final String SQL_METADATA_LOCKS =
            "SELECT OBJECT_SCHEMA, OBJECT_NAME, LOCK_TYPE, LOCK_DURATION, OWNER_THREAD_ID, OWNER_EVENT_ID "
                    + "FROM performance_schema.metadata_locks ORDER BY OBJECT_SCHEMA, OBJECT_NAME";
    private static final String SQL_SESSION_INFO =
            "SELECT t.trx_id, t.trx_mysql_thread_id, t.trx_state, p.USER, p.HOST, p.DB, t.trx_query "
                    + "FROM information_schema.innodb_trx t "
                    + "LEFT JOIN information_schema.processlist p ON t.trx_mysql_thread_id = p.ID";

    @Override
    public Map<String, Object> lockView(Long dataSourceId) {
        requireDatasourceContext(dataSourceId);
        Connection connection = Chat2DBContext.getConnection();
        boolean ps = performanceSchemaLocksAvailable(connection);
        List<Map<String, Object>> errors = new ArrayList<>();

        List<Map<String, Object>> dataLocks = queryRows(connection, ps ? SQL_DATA_LOCKS_8 : SQL_DATA_LOCKS_57,
                "dataLocks", errors);
        List<Map<String, Object>> waits = queryRows(connection, ps ? SQL_DATA_LOCK_WAITS_8 : SQL_DATA_LOCK_WAITS_57,
                "waits", errors);
        List<Map<String, Object>> metaLocks = queryRows(connection, SQL_METADATA_LOCKS, "metaLocks", errors);
        List<Map<String, Object>> sessions = queryRows(connection, SQL_SESSION_INFO, "sessions", errors);
        List<Map<String, Object>> waitChains = buildWaitChains(waits, sessions, ps);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("dataSourceId", dataSourceId);
        view.put("source", lockSource(ps, errors));
        view.put("dataLocks", dataLocks);
        view.put("waits", waits);
        view.put("metaLocks", metaLocks);
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
    private static boolean performanceSchemaLocksAvailable(Connection connection) {
        try {
            return DefaultSQLExecutor.getInstance().execute(connection, "SELECT 1 FROM performance_schema.data_locks LIMIT 1",
                    resultSet -> Boolean.TRUE);
        } catch (RuntimeException e) {
            return false;
        }
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
        error.put("code", isPrivilegeError(cause) ? "privilege_required" : "unavailable");
        error.put("message", cause.getMessage());
        return error;
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
     * Builds waiter -> blocker pairs, resolving users/hosts/queries from the transaction
     * session map and flagging the root blocker (the blocker is not itself waiting).
     * Rows whose lock or session disappeared mid-refresh are skipped rather than failing.
     */
    private static List<Map<String, Object>> buildWaitChains(List<Map<String, Object>> waits,
                                                             List<Map<String, Object>> sessions, boolean ps) {
        Map<String, Map<String, Object>> byTrx = new HashMap<>();
        for (Map<String, Object> session : sessions) {
            byTrx.put(String.valueOf(session.get("trx_id")), session);
        }
        Map<String, Map<String, Object>> byLock = new HashMap<>();
        for (Map<String, Object> lock : waits) {
            String waiterLock = String.valueOf(lock.get(ps ? "REQUESTING_ENGINE_LOCK_ID" : "requested_lock_id"));
            byLock.put(waiterLock, lock);
        }

        List<Map<String, Object>> chains = new ArrayList<>();
        for (Map<String, Object> wait : waits) {
            String waiterLockId = String.valueOf(wait.get(ps ? "REQUESTING_ENGINE_LOCK_ID" : "requested_lock_id"));
            String blockerLockId = String.valueOf(wait.get(ps ? "BLOCKING_ENGINE_LOCK_ID" : "blocking_lock_id"));
            String waiterTrx = String.valueOf(wait.get(ps ? "REQUESTING_ENGINE_TRANSACTION_ID" : "requesting_trx_id"));
            String blockerTrx = String.valueOf(wait.get(ps ? "BLOCKING_ENGINE_TRANSACTION_ID" : "blocking_trx_id"));
            if ("null".equals(waiterTrx) || "null".equals(blockerTrx)) {
                continue;
            }
            Map<String, Object> waiter = byTrx.get(waiterTrx);
            Map<String, Object> blocker = byTrx.get(blockerTrx);
            if (waiter == null || blocker == null) {
                continue;
            }
            // A blocker is the root blocker when its lock never appears as a waiter lock.
            boolean blockerIsRoot = !byLock.containsKey(blockerLockId);
            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("waiterThreadId", waiter.get("trx_mysql_thread_id"));
            chain.put("waiterState", waiter.get("trx_state"));
            chain.put("waiterUser", waiter.get("USER"));
            chain.put("waiterHost", waiter.get("HOST"));
            chain.put("waiterQuery", waiter.get("trx_query"));
            chain.put("blockerThreadId", blocker.get("trx_mysql_thread_id"));
            chain.put("blockerState", blocker.get("trx_state"));
            chain.put("blockerUser", blocker.get("USER"));
            chain.put("blockerHost", blocker.get("HOST"));
            chain.put("blockerQuery", blocker.get("trx_query"));
            chain.put("rootBlocker", blockerIsRoot);
            chains.add(chain);
        }
        return chains;
    }
}
