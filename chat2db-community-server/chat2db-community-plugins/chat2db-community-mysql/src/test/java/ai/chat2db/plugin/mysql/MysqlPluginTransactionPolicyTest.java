package ai.chat2db.plugin.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlPluginTransactionPolicyTest {

    private final MysqlPlugin plugin = new MysqlPlugin();

    @Test
    void declaresManualTransactionSupport() {
        assertTrue(plugin.supportsManualTransactions());
    }

    @Test
    void detectsStatementsThatImplicitlyCommit() {
        assertTrue(plugin.isImplicitCommitStatement("CREATE_TABLE", "CREATE TABLE t(id INT)"));
        assertTrue(plugin.isImplicitCommitStatement("ALTER_TABLE", "ALTER TABLE t ADD c INT"));
        assertTrue(plugin.isImplicitCommitStatement("TRUNCATE_TABLE", "TRUNCATE TABLE t"));
        assertTrue(plugin.isImplicitCommitStatement("SET_AUTOCOMMIT", "SET SESSION autocommit = ON"));
    }

    @Test
    void leavesTransactionalStatementsAlone() {
        assertFalse(plugin.isImplicitCommitStatement("INSERT", "INSERT INTO t VALUES (1)"));
        assertFalse(plugin.isImplicitCommitStatement("SET_AUTOCOMMIT", "SET autocommit = 0"));
        assertFalse(plugin.isImplicitCommitStatement(null, "CREATE TABLE t(id INT)"));
    }
}
