package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.runtime.TransactionStateResponse;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.model.request.data.source.ConsoleCloseRequest;
import ai.chat2db.community.web.api.model.request.db.TransactionBeginRequest;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages manual transactions scoped to a single SQL Console: begin, commit, rollback,
 * state lookup, and release on console close / connection switch.
 *
 * <p>Each request carries {@code consoleId} via {@link ConsoleCloseRequest}; the domain
 * service resolves the trusted saved-console context and reuses the bound connection across
 * executions while a transaction is open.
 */
@Slf4j
@RequestMapping("/api/rdb/transaction")
@RestController
public class DbTransactionController {

    @Autowired
    private IDbConnectionContextService connectionContextService;

    @Autowired
    private DbWebConverter dbWebConverter;

    /**
     * Begins a manual transaction for the console.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/begin}.
     */
    @RequestMapping(value = "/begin", method = RequestMethod.POST)
    public DataResult<TransactionStateResponse> begin(@Valid @RequestBody TransactionBeginRequest request) {
        try {
            return DataResult.of(connectionContextService.beginManualTransaction(
                    dbWebConverter.transactionBeginRequest2context(request)
            ));
        } finally {
            connectionContextService.clear();
        }
    }

    /**
     * Commits the console's open transaction.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/commit}.
     */
    @RequestMapping(value = "/commit", method = RequestMethod.POST)
    public DataResult<TransactionStateResponse> commit(@Valid @RequestBody ConsoleCloseRequest request) {
        try {
            return DataResult.of(connectionContextService.commitTransaction(
                    dbWebConverter.consoleCloseRequest2context(request)
            ));
        } finally {
            connectionContextService.clear();
        }
    }

    /**
     * Rolls back the console's open transaction.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/rollback}.
     */
    @RequestMapping(value = "/rollback", method = RequestMethod.POST)
    public DataResult<TransactionStateResponse> rollback(@Valid @RequestBody ConsoleCloseRequest request) {
        try {
            return DataResult.of(connectionContextService.rollbackTransaction(
                    dbWebConverter.consoleCloseRequest2context(request)
            ));
        } finally {
            connectionContextService.clear();
        }
    }

    /**
     * Returns the console's current transaction state.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/state}.
     */
    @RequestMapping(value = "/state", method = RequestMethod.POST)
    public DataResult<TransactionStateResponse> state(@Valid @RequestBody ConsoleCloseRequest request) {
        try {
            return DataResult.of(connectionContextService.getTransactionState(
                    dbWebConverter.consoleCloseRequest2context(request)
            ));
        } finally {
            connectionContextService.clear();
        }
    }

    /**
     * Releases the console's bound connection (rolls back any open transaction first). Called
     * when the console is closed or its connection changes.
     * <p>
     * Endpoint: {@code POST /api/rdb/transaction/release}.
     */
    @RequestMapping(value = "/release", method = RequestMethod.POST)
    public DataResult<TransactionStateResponse> release(@Valid @RequestBody ConsoleCloseRequest request) {
        try {
            return DataResult.of(connectionContextService.releaseBoundConnection(
                    dbWebConverter.consoleCloseRequest2context(request)
            ));
        } finally {
            connectionContextService.clear();
        }
    }

}
