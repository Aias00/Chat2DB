package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.SqlUtils;
import com.alibaba.druid.DbType;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MysqlTransactionPolicy {

    private static final Set<String> IMPLICIT_COMMIT_TYPES = Set.of(
            "CREATE", "ALTER", "DROP", "RENAME_TABLE", "TRUNCATE_TABLE",
            "CREATE_TABLE", "CREATE_VIEW", "CREATE_DATABASE", "CREATE_SCHEMA",
            "CREATE_FUNCTION", "CREATE_PROCEDURE", "CREATE_USER", "CREATE_EVENT",
            "CREATE_INDEX", "CREATE_TRIGGER", "CREATE_ROLE", "CREATE_CONSTRAINT",
            "CREATE_TABLESPACE", "CREATE_SERVER", "CREATE_LOGFILE_GROUP", "CREATE_UDF",
            "ALTER_TABLE", "ALTER_DATABASE", "ALTER_EVENT", "ALTER_FUNCTION",
            "ALTER_INSTANCE", "ALTER_LOGFILE_GROUP", "ALTER_PROCEDURE", "ALTER_SERVER",
            "ALTER_TABLESPACE", "ALTER_VIEW", "ALTER_USER",
            "DROP_DATABASE", "DROP_TABLE", "DROP_VIEW", "DROP_FUNCTION", "DROP_PROCEDURE",
            "DROP_USER", "DROP_ROLE", "DROP_EVENT", "DROP_INDEX", "DROP_TRIGGER",
            "DROP_CONSTRAINT", "DROP_SCHEMA", "DROP_LOGFILE_GROUP", "DROP_SERVER",
            "DROP_TABLESPACE", "GRANT", "REVOKE", "RENAME_USER", "SET_PASSWORD",
            "START_TRANSACTION", "BEGIN_WORK", "LOCK_TABLES", "UNLOCK_TABLES",
            "ANALYZE", "CHECK_TABLE", "OPTIMIZE_TABLE", "REPAIR_TABLE", "CACHE_INDEX",
            "FLUSH", "LOAD_INDEX", "RESET", "INSTALL_PLUGIN", "UNINSTALL_PLUGIN"
    );

    public void beforeExecute(SqlExecutionPlan plan) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null
                || !"MYSQL".equalsIgnoreCase(connectInfo.getDbType())
                || !Boolean.TRUE.equals(connectInfo.getConsoleOwn())) {
            return;
        }
        List<SimpleSqlStatement> statements = SqlUtils.parseStatements(plan.getSql(), DbType.mysql, "MYSQL");
        boolean implicitCommit = statements.stream().anyMatch(statement ->
                isImplicitCommitStatement(statement.getSqlType(), statement.getSql()));
        if (implicitCommit) {
            throw new BusinessException("transaction.implicitCommit.blocked");
        }
    }

    static boolean isImplicitCommitStatement(String sqlType, String sql) {
        if (sqlType == null) {
            return false;
        }
        String normalizedType = sqlType.toUpperCase(Locale.ROOT);
        if (IMPLICIT_COMMIT_TYPES.contains(normalizedType)) {
            return true;
        }
        if (!"SET_AUTOCOMMIT".equals(normalizedType)) {
            return false;
        }
        String normalizedSql = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return normalizedSql.matches("(?i)^SET(?:\\s+SESSION)?\\s+AUTOCOMMIT\\s*=\\s*(?:1|ON)\\s*;?$");
    }
}
