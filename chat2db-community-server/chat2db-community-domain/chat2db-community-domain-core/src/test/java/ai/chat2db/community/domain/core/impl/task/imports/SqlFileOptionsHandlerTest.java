package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.antlr.v4.runtime.CommonToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlFileOptionsHandlerTest {

    @Test
    void commitsConfiguredBatchesAndRestoresAutoCommit() {
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        Connection connection = connection(commits, rollbacks);
        SqlFileOptionsHandler handler = new SqlFileOptionsHandler(spec("BATCH", "STOP", 2), context(), connection);

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        handler.handle(new Statement("INSERT INTO test VALUES (2)"));
        handler.handle(new Statement("INSERT INTO test VALUES (3)"));
        handler.flush();

        assertEquals(2, commits.get());
        assertEquals(0, rollbacks.get());
    }

    @Test
    void rejectsTransactionControlInTransactionModes() {
        Connection connection = connection(new AtomicInteger(), new AtomicInteger());
        SqlFileOptionsHandler handler = new SqlFileOptionsHandler(spec("SINGLE_TRANSACTION", "STOP", 1),
                context(), connection);

        assertThrows(TaskExecutionException.class, () -> handler.handle(new Statement("COMMIT")));
    }

    @Test
    void cancellationBetweenStatementsRollsBackAndRestoresAutoCommit() {
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        List<Boolean> autoCommitChanges = new ArrayList<>();
        Connection connection = connection(commits, rollbacks, new AtomicInteger(), autoCommitChanges, false);
        RecordingContext context = new RecordingContext();
        SqlFileOptionsHandler handler = new SqlFileOptionsHandler(spec("SINGLE_TRANSACTION", "STOP", 10),
                context, connection);

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        context.cancelled = true;

        assertThrows(TaskCancelledException.class,
                () -> handler.handle(new Statement("INSERT INTO test VALUES (2)")));
        assertEquals(1, rollbacks.get());
        assertEquals(List.of(false, true), autoCommitChanges);
    }

    @Test
    void summaryCountsStatementsOnlyAfterCommitSucceeds() {
        RecordingContext context = new RecordingContext();
        SqlFileOptionsHandler handler = new SqlFileOptionsHandler(spec("BATCH", "STOP", 2), context,
                connection(new AtomicInteger(), new AtomicInteger()));

        handler.handle(new Statement("INSERT INTO test VALUES (1)"));
        handler.handle(new Statement("INSERT INTO test VALUES (2)"));
        handler.handle(new Statement("INSERT INTO test VALUES (3)"));
        handler.flush();

        assertEquals(3, context.lastInfoDetails.get("committedStatements"));
    }

    @Test
    void leadingCommentsCannotBypassImplicitCommitValidation() {
        AtomicInteger executeCalls = new AtomicInteger();
        SqlFileOptionsHandler handler = new SqlFileOptionsHandler(spec("BATCH", "STOP", 10), context(),
                connection(new AtomicInteger(), new AtomicInteger(), executeCalls, new ArrayList<>(), false));

        assertThrows(TaskExecutionException.class,
                () -> handler.handle(new Statement("/* migration */ DROP TABLE test")));
        assertEquals(0, executeCalls.get());
    }

    @Test
    void failedStatementLogOmitsSqlSecretsAndIncludesLineRange() {
        RecordingContext context = new RecordingContext();
        SqlFileOptionsHandler handler = new SqlFileOptionsHandler(spec("SCRIPT", "CONTINUE", 10), context,
                connection(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new ArrayList<>(), true));
        Statement statement = new Statement("INSERT INTO users(password) VALUES ('secret-value')");
        CommonToken first = new CommonToken(0);
        first.setLine(7);
        CommonToken last = new CommonToken(0);
        last.setLine(9);
        statement.setFirstToken(first);
        statement.setLastToken(last);

        handler.handle(statement);

        assertFalse(context.lastErrorDetails.toString().contains("secret-value"));
        assertEquals(7, context.lastErrorDetails.get("startLine"));
        assertEquals(9, context.lastErrorDetails.get("endLine"));
    }

    private ImportTaskSpec spec(String commitMode, String errorPolicy, int batchSize) {
        return ImportTaskSpec.builder().commitMode(commitMode).errorPolicy(errorPolicy).batchSize(batchSize).build();
    }

    private Connection connection(AtomicInteger commits, AtomicInteger rollbacks) {
        return connection(commits, rollbacks, new AtomicInteger(), new ArrayList<>(), false);
    }

    private Connection connection(AtomicInteger commits, AtomicInteger rollbacks, AtomicInteger executeCalls,
                                  List<Boolean> autoCommitChanges, boolean failExecute) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> true;
                    case "setAutoCommit" -> {
                        autoCommitChanges.add((Boolean) args[0]);
                        yield null;
                    }
                    case "close" -> null;
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks.incrementAndGet();
                        yield null;
                    }
                    case "createStatement" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class[]{java.sql.Statement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                if (!statementMethod.getName().equals("execute")) {
                                    return null;
                                }
                                executeCalls.incrementAndGet();
                                if (failExecute) {
                                    throw new SQLException("statement failed");
                                }
                                return false;
                            });
                    case "isClosed" -> false;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    default -> null;
                });
    }

    private TaskExecutionContext context() {
        return new RecordingContext();
    }

    private static final class RecordingContext implements TaskExecutionContext {
        private boolean cancelled;
        private Map<String, Object> lastInfoDetails = Map.of();
        private Map<String, Object> lastErrorDetails = Map.of();

        @Override public void reportProgress(int progress, String stage, String message) { }
        @Override public void logInfo(String code, String message) { }
        @Override public void logInfo(String code, String message, Map<String, Object> details) {
            lastInfoDetails = details;
        }
        @Override public void logWarn(String code, String message, Map<String, Object> details) { }
        @Override public void logError(String code, String message, Map<String, Object> details) {
            lastErrorDetails = details;
        }
        @Override public void checkCancelled() {
            if (cancelled) {
                throw new TaskCancelledException();
            }
        }
        @Override public void registerCancelable(TaskCancelable resource) { }
        @Override public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) { return null; }
        @Override public void write(String content) { }
        @Override public void onStatementCreated(java.sql.Statement statement) { }
        @Override public void onStatementClosed(java.sql.Statement statement) { }
    }
}
