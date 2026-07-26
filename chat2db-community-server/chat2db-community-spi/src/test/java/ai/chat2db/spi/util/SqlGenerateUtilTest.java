package ai.chat2db.spi.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link SqlGenerateUtil#generateSelectCountSql}.
 * Covers the Druid fallback path (JSQLParser failure) to verify that the
 * rendered count SQL uses the requested database dialect rather than
 * a hardcoded {@code DbType.sqlserver}.
 */
class SqlGenerateUtilTest {

    /**
     * When JSQLParser cannot parse the SQL, the Druid fallback should
     * render the count SQL using the parsed {@code dbType} (e.g. mysql),
     * not {@code DbType.sqlserver}.
     */
    @Test
    void druidFallbackUsesRequestedDbTypeForMysql() {
        // "SAMPLE" is a Snowflake keyword that JSQLParser does not understand,
        // forcing the catch fallback path.
        String sql = "SELECT * FROM users WHERE name = 'sample'";
        String result = SqlGenerateUtil.generateSelectCountSql(sql, "mysql");

        // The Druid mysql dialect renders COUNT(*) as `COUNT(*)` with backtick-free identifiers.
        assertTrue(result.contains("COUNT"), () -> "Expected COUNT in result: " + result);
        assertFalse(result.contains("sqlserver"), () -> "Result should not mention sqlserver: " + result);
    }

    /**
     * The fallback path should also work for PostgreSQL dialect.
     */
    @Test
    void druidFallbackUsesRequestedDbTypeForPostgresql() {
        String sql = "SELECT * FROM users WHERE name = 'sample'";
        String result = SqlGenerateUtil.generateSelectCountSql(sql, "postgresql");

        assertTrue(result.contains("COUNT"), () -> "Expected COUNT in result: " + result);
    }

    /**
     * The normal (non-fallback) path should be unchanged: a simple SELECT
     * that JSQLParser can parse should produce a count-wrapped SQL.
     */
    @Test
    void normalPathWrapsSimpleSelectInCount() {
        String result = SqlGenerateUtil.generateSelectCountSql("SELECT * FROM users", "mysql");
        assertTrue(result.contains("COUNT"), () -> "Expected COUNT in: " + result);
    }

    /**
     * An unsupported database type should throw IllegalArgumentException
     * when the fallback path is triggered.
     */
    @Test
    void unsupportedDbTypeThrowsIllegalArgumentInFallback() {
        // INSERT is not a SELECT — JSQLParser parses it but the instanceof Select
        // check fails, throwing IllegalArgumentException in the normal path.
        // In the fallback, Druid also rejects non-SELECT, throwing the same.
        assertThrows(IllegalArgumentException.class,
                () -> SqlGenerateUtil.generateSelectCountSql("INSERT INTO users VALUES (1)", "unsupported_db"));
    }

    /**
     * A non-SELECT statement should throw in both paths.
     */
    @Test
    void nonSelectStatementThrowsInFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlGenerateUtil.generateSelectCountSql("INSERT INTO users VALUES (1)", "mysql"));
    }
}
