package ai.chat2db.community.web.api.adapter.db.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExecutionJobTest {

    @Test
    void implicitCommitDetectionChecksStatementsAfterCommentsAndSemicolons() {
        String sql = """
                -- normal read first
                select 1;
                /* migration step */
                alter table users add column status varchar(20)
                """;

        assertTrue(SqlExecutionJob.containsImplicitCommitStatement(sql));
    }

    @Test
    void implicitCommitDetectionIgnoresCommentedAndQuotedTokens() {
        String sql = """
                /* drop table users; */
                select 'create table x(id int); still a string';
                -- alter table users add column hidden int
                select 2
                """;

        assertFalse(SqlExecutionJob.containsImplicitCommitStatement(sql));
    }

    @Test
    void implicitCommitDetectionDoesNotSplitOnSemicolonsInsideCommentsOrStrings() {
        String sql = """
                select 'not done; still select';
                /* ignored; drop table users; */
                # ignored; truncate users
                create index idx_users_name on users(name)
                """;

        assertTrue(SqlExecutionJob.containsImplicitCommitStatement(sql));
    }
}
