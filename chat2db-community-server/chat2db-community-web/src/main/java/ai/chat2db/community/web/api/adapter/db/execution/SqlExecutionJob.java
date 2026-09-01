package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogStatusEnum;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbStreamingExecuteRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbExecuteResultEnhanceService;
import ai.chat2db.community.tools.http.LocalCookie;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.web.api.config.console.ConsoleHelper;
import ai.chat2db.community.domain.api.model.operation.SqlOperationLogRecord;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogSourceEnum;
import ai.chat2db.community.domain.api.service.db.IDbLargeValueTokenService;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.api.service.db.IDbSqlExecutionService;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class SqlExecutionJob implements Runnable, ISqlExecutionStatementListener {

    @Getter
    private final SqlExecutionRequest request;
    private final ISqlExecutionSink sink;
    private final IDbConnectionContextService connectionContextService;
    private final IDbSqlExecutionService sqlExecutionService;
    private final DbWebConverter dbWebConverter;
    private final IDbLargeValueTokenService largeValueTokenService;
    private final IDbExecuteResultEnhanceService executeResultEnhanceService;
    private final IOpsSqlOperationLogService sqlOperationLogRecorder;
    private final Consumer<SqlExecutionJob> finishCallback;
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private final SqlExecutionEventContext eventContext = new SqlExecutionEventContext();

    @Setter
    @Getter
    private volatile Future<?> future;
    private volatile Statement currentStatement;
    private volatile Thread workerThread;

    public SqlExecutionJob(SqlExecutionRequest request, ISqlExecutionSink sink,
                           IDbConnectionContextService connectionContextService,
                           IDbSqlExecutionService sqlExecutionService,
                           DbWebConverter dbWebConverter,
                           IDbLargeValueTokenService largeValueTokenService,
                           IDbExecuteResultEnhanceService executeResultEnhanceService,
                           IOpsSqlOperationLogService sqlOperationLogRecorder,
                           Consumer<SqlExecutionJob> finishCallback) {
        this.request = request;
        this.sink = sink;
        this.connectionContextService = connectionContextService;
        this.sqlExecutionService = sqlExecutionService;
        this.dbWebConverter = dbWebConverter;
        this.largeValueTokenService = largeValueTokenService;
        this.executeResultEnhanceService = executeResultEnhanceService;
        this.sqlOperationLogRecorder = sqlOperationLogRecorder;
        this.finishCallback = finishCallback;
    }

    @Override
    public void run() {
        Long consoleId = request.getConnectionContext() == null ? null : request.getConnectionContext().getConsoleId();
        if (consoleId != null && connectionContextService.isInTransaction(consoleId)) {
            try {
                connectionContextService.withConsoleTransactionLock(consoleId, () -> {
                    runInternal();
                    return null;
                });
            } catch (Exception e) {
                throw e instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(e);
            }
            return;
        }
        runInternal();
    }

    private void runInternal() {
        workerThread = Thread.currentThread();
        ConsoleHelper.setHeaders(request.getConsoleMessage());
        restoreLocalHeaders();
        SqlExecutionLogConsumer logConsumer = null;
        try {
            Context context = request.getContext();
            if (context != null) {
                ContextUtils.setContext(context);
            }
            bindConnectionContext();
            sink.send("started", Map.of("executionId", request.getExecutionId()));
            DbDlExecuteRequest param = dbWebConverter.request2param(request.getSqlEditorRequest());
            logConsumer = new SqlExecutionLogConsumer(
                    new SqlExecutionConsumer(request, sink, dbWebConverter, largeValueTokenService,
                            executeResultEnhanceService, eventContext),
                    request,
                    sqlOperationLogRecorder);
            DbStreamingExecuteRequest executeStreamingRequest = new DbStreamingExecuteRequest();
            executeStreamingRequest.setExecutionId(request.getExecutionId());
            executeStreamingRequest.setDlExecuteRequest(param);
            executeStreamingRequest.setConsumer(logConsumer);
            executeStreamingRequest.setStatementListener(this);
            executeStreamingRequest.setCancellation(canceled::get);
            warnImplicitCommitIfNeeded(param);
            sqlExecutionService.executeStreaming(executeStreamingRequest);
            if (canceled.get()) {
                logConsumer.finishCancelled(request.getSqlEditorRequest().getSql(), null);
            } else {
                logConsumer.finishSuccess();
            }
            sink.send(canceled.get() ? "cancelled" : "finished", terminalEvent(null));
        } catch (Exception e) {
            if (canceled.get()) {
                recordTerminalStatus(logConsumer, SqlOperationLogStatusEnum.CANCELLED.getCode(), e.getMessage());
                sink.send("cancelled", terminalEvent(e.getMessage()));
            } else {
                log.error("SQL execution failed, executionId={}", request.getExecutionId(), e);
                recordTerminalStatus(logConsumer, SqlOperationLogStatusEnum.FAIL.getCode(), e.getMessage());
                sink.send("failed", terminalEvent(e.getMessage()));
            }
        } finally {
            currentStatement = null;
            ContextUtils.removeContext();
            connectionContextService.clear();
            finishCallback.accept(this);
        }
    }

    /**
     * Builds a terminal-event payload that always carries the executionId, an optional
     * message, and the console's current transaction state so the frontend can keep its
     * Commit/Rollback controls in sync after every execution (including cancellation, which
     * leaves an open transaction intact by design).
     */
    private Map<String, Object> terminalEvent(String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("executionId", request.getExecutionId());
        if (message != null) {
            payload.put("message", message);
        }
        Long consoleId = request.getSqlEditorRequest() == null ? null : request.getSqlEditorRequest().getConsoleId();
        payload.put("inTransaction", consoleId != null
                && connectionContextService.isInTransaction(consoleId));
        return payload;
    }

    public void cancel() {
        canceled.set(true);
        Statement statement = currentStatement;
        if (statement != null) {
            try {
                statement.cancel();
            } catch (Exception e) {
                log.warn("cancel SQL statement failed, executionId={}", request.getExecutionId(), e);
            }
        }
        Future<?> localFuture = future;
        if (workerThread != null && localFuture != null) {
            localFuture.cancel(true);
        }
    }

    public boolean hasStarted() {
        return workerThread != null;
    }

    public void sendCancelled() {
        canceled.set(true);
        recordFallbackTerminalStatus(SqlOperationLogStatusEnum.CANCELLED.getCode(), null);
        sink.send("cancelled", terminalEvent(null));
    }

    public void pollMessages() {
        Statement statement = currentStatement;
        if (statement == null) {
            return;
        }
        try {
            List<Map<String, Object>> messages = collectWarnings(statement.getWarnings());
            synchronized (eventContext) {
                SqlExecutionEventIdentity identity = eventContext.currentIdentity();
                for (Map<String, Object> message : messages) {
                    sink.send("message", message, identity);
                }
            }
            statement.clearWarnings();
        } catch (Exception e) {
            log.debug("poll SQL messages failed, executionId={}", request.getExecutionId(), e);
        }
    }

    @Override
    public void onStatementCreated(Statement statement) {
        currentStatement = statement;
    }

    @Override
    public void onStatementClosed(Statement statement) {
        if (currentStatement == statement) {
            pollMessages();
            currentStatement = null;
        }
    }

    private void warnImplicitCommitIfNeeded(DbDlExecuteRequest param) {
        Long consoleId = param == null ? null : param.getConsoleId();
        if (consoleId == null
                || !connectionContextService.isInTransaction(consoleId)
                || !containsImplicitCommitStatement(param.getSql())) {
            return;
        }
        // A DDL/implicit-commit statement is about to run while a manual transaction is open.
        // Surface a non-blocking warning to the user; execution proceeds regardless.
        Map<String, Object> message = new HashMap<>();
        message.put("level", "WARN");
        message.put("message", "This statement implicitly commits the current transaction.");
        message.put("source", "implicit-commit-warning");
        synchronized (eventContext) {
            SqlExecutionEventIdentity identity = eventContext.currentIdentity();
            sink.send("message", message, identity);
        }
    }

    static boolean containsImplicitCommitStatement(String sql) {
        for (String firstToken : firstSqlTokens(sql)) {
            if (isImplicitCommitToken(firstToken)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isImplicitCommitToken(String firstToken) {
        return switch (firstToken) {
            case "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "SET", "START", "CHANGE", "SLAVE", "PURGE",
                 "RESET", "CACHE", "GRANT", "REVOKE", "FLUSH", "LOCK", "UNLOCK", "OPTIMIZE", "REPAIR",
                 "ANALYZE", "CHECK", "LOAD" -> true;
            default -> false;
        };
    }

    private static List<String> firstSqlTokens(String sql) {
        if (sql == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        int length = sql.length();
        int statementStart = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean backtick = false;
        boolean bracketQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < length; index++) {
            char current = sql.charAt(index);
            char next = index + 1 < length ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (singleQuote) {
                if (current == '\'' && next == '\'') {
                    index++;
                } else if (current == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                if (current == '"' && next == '"') {
                    index++;
                } else if (current == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (backtick) {
                if (current == '`') {
                    backtick = false;
                }
                continue;
            }
            if (bracketQuote) {
                if (current == ']') {
                    bracketQuote = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (current == '#') {
                lineComment = true;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (current == '\'') {
                singleQuote = true;
                continue;
            }
            if (current == '"') {
                doubleQuote = true;
                continue;
            }
            if (current == '`') {
                backtick = true;
                continue;
            }
            if (current == '[') {
                bracketQuote = true;
                continue;
            }
            if (current == ';') {
                addFirstSqlToken(tokens, sql, statementStart, index);
                statementStart = index + 1;
            }
        }
        addFirstSqlToken(tokens, sql, statementStart, length);
        return tokens;
    }

    private static void addFirstSqlToken(List<String> tokens, String sql, int startInclusive, int endExclusive) {
        String token = firstSqlToken(sql, startInclusive, endExclusive);
        if (!token.isEmpty()) {
            tokens.add(token);
        }
    }

    private static String firstSqlToken(String sql, int startInclusive, int endExclusive) {
        int index = startInclusive;
        while (index < endExclusive) {
            while (index < endExclusive && Character.isWhitespace(sql.charAt(index))) {
                index++;
            }
            if (index + 1 < endExclusive && sql.charAt(index) == '-' && sql.charAt(index + 1) == '-') {
                index = skipLineComment(sql, index + 2, endExclusive);
                continue;
            }
            if (index < endExclusive && sql.charAt(index) == '#') {
                index = skipLineComment(sql, index + 1, endExclusive);
                continue;
            }
            if (index + 1 < endExclusive && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*') {
                int blockEnd = sql.indexOf("*/", index + 2);
                index = blockEnd < 0 || blockEnd >= endExclusive ? endExclusive : blockEnd + 2;
                continue;
            }
            break;
        }
        int tokenStart = index;
        while (index < endExclusive && Character.isLetter(sql.charAt(index))) {
            index++;
        }
        return sql.substring(tokenStart, index).toUpperCase(Locale.ROOT);
    }

    private static int skipLineComment(String sql, int index, int endExclusive) {
        while (index < endExclusive) {
            char current = sql.charAt(index);
            if (current == '\n' || current == '\r') {
                return index + 1;
            }
            index++;
        }
        return endExclusive;
    }

    private void recordTerminalStatus(SqlExecutionLogConsumer logConsumer, String status, String message) {
        if (logConsumer != null) {
            if (SqlOperationLogStatusEnum.CANCELLED.getCode().equals(status)) {
                logConsumer.finishCancelled(requestSql(), message);
            } else {
                logConsumer.finishFailed(requestSql(), message);
            }
            return;
        }
        recordFallbackTerminalStatus(status, message);
    }

    private void recordFallbackTerminalStatus(String status, String message) {
        sqlOperationLogRecorder.recordAsync(SqlOperationLogRecord.builder()
                .sql(requestSql())
                .status(status)
                .errorMessage(message)
                .executionId(request.getExecutionId())
                .source(SqlOperationLogSourceEnum.SQL_EDITOR_JCEF.name())
                .connectionProfile(request.getConnectionProfile())
                .context(request.getContext())
                .build());
    }

    private void bindConnectionContext() {
        DbConnectionContextRequest connectionContext = request.getConnectionContext();
        if (connectionContext == null) {
            return;
        }
        connectionContextService.bind(connectionContext);
        ConnectionProfile profile = connectionContextService.currentProfile();
        request.setConnectionProfile(profile);
    }

    private String requestSql() {
        return request.getSqlEditorRequest() == null ? null : request.getSqlEditorRequest().getSql();
    }

    private void restoreLocalHeaders() {
        Map<String, Object> headers = request.getHeaders();
        if (headers == null) {
            return;
        }
        headers.forEach((key, value) -> {
            if (key != null && value != null) {
                LocalCookie.setHeader(key, String.valueOf(value));
            }
        });
    }

    private List<Map<String, Object>> collectWarnings(SQLWarning warning) {
        List<Map<String, Object>> messages = new ArrayList<>();
        SQLWarning current = warning;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                Map<String, Object> item = new HashMap<>();
                item.put("level", "INFO");
                item.put("message", current.getMessage());
                item.put("errorCode", current.getErrorCode());
                item.put("sqlState", current.getSQLState());
                item.put("source", "statement-warning");
                messages.add(item);
            }
            current = current.getNextWarning();
        }
        return messages;
    }
}
