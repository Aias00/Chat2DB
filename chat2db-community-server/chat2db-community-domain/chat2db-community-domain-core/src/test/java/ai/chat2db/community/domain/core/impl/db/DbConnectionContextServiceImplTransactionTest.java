package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConsoleTransactionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbConnectionContextServiceImplTransactionTest {

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        ConsoleTransactionRegistry.releaseAll(true);
    }

    @Test
    void commitValidatesDatasourceVisibilityBeforeMutatingConsoleTransaction() {
        long consoleId = 9001L;
        AtomicInteger commits = new AtomicInteger();
        registerBound(consoleId, 10L, proxyConnection(commits, new AtomicInteger()));
        DbConnectionContextServiceImpl service = service(Set.of());

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.commitTransaction(request(consoleId, 10L)));

        assertEquals("datasource.not.found", thrown.getCode());
        assertEquals(0, commits.get());
        assertTrue(ConsoleTransactionRegistry.isInTransaction(consoleId));
    }

    @Test
    void rollbackRejectsDatasourceMismatchBeforeMutatingConsoleTransaction() {
        long consoleId = 9002L;
        AtomicInteger rollbacks = new AtomicInteger();
        registerBound(consoleId, 10L, proxyConnection(new AtomicInteger(), rollbacks));
        DbConnectionContextServiceImpl service = service(Set.of(11L));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.rollbackTransaction(request(consoleId, 11L)));

        assertEquals("transaction.datasource.mismatch", thrown.getCode());
        assertEquals(0, rollbacks.get());
        assertTrue(ConsoleTransactionRegistry.isInTransaction(consoleId));
    }

    @Test
    void bindOpenTransactionUsesBoundContextInsteadOfRequestDatabaseAndSchema() {
        long consoleId = 9003L;
        Connection connection = proxyConnection(new AtomicInteger(), new AtomicInteger());
        ConnectInfo bound = registerBound(consoleId, 10L, connection);
        bound.setDatabaseName("trusted_db");
        bound.setSchemaName("trusted_schema");
        DbConnectionContextServiceImpl service = new DbConnectionContextServiceImpl();
        DbConnectionContextRequest taintedRequest = request(consoleId, 10L);
        taintedRequest.setDatabaseName("evil`; DROP DATABASE trusted_db; --");
        taintedRequest.setSchemaName("evil_schema");

        service.bind(taintedRequest);

        ConnectInfo current = Chat2DBContext.getConnectInfo();
        assertEquals("trusted_db", current.getDatabaseName());
        assertEquals("trusted_schema", current.getSchemaName());
        assertSame(connection, current.getConnection());
    }

    @Test
    void transactionStateRejectsDatasourceMismatch() {
        long consoleId = 9004L;
        registerBound(consoleId, 10L, proxyConnection(new AtomicInteger(), new AtomicInteger()));
        DbConnectionContextServiceImpl service = service(Set.of(10L, 11L));

        assertThrows(BusinessException.class, () -> service.getTransactionState(request(consoleId, 11L)));
    }

    @Test
    void releaseReturnsRolledBackOutcomeAndClearsBoundTransaction() {
        long consoleId = 9005L;
        AtomicInteger rollbacks = new AtomicInteger();
        registerBound(consoleId, 10L, proxyConnection(new AtomicInteger(), rollbacks));
        DbConnectionContextServiceImpl service = service(Set.of(10L));

        var response = service.releaseBoundConnection(request(consoleId, 10L));

        assertFalse(response.isInTransaction());
        assertEquals("auto", response.getMode());
        assertEquals("ROLLED_BACK", response.getOutcome());
        assertEquals(1, rollbacks.get());
        assertFalse(ConsoleTransactionRegistry.isInTransaction(consoleId));
    }

    @Test
    void releaseReturnsUnknownOutcomeWhenRollbackCannotBeConfirmed() {
        long consoleId = 9006L;
        registerBound(consoleId, 10L, rollbackFailingConnection());
        DbConnectionContextServiceImpl service = service(Set.of(10L));

        var response = service.releaseBoundConnection(request(consoleId, 10L));

        assertFalse(response.isInTransaction());
        assertEquals("auto", response.getMode());
        assertEquals("UNKNOWN", response.getOutcome());
        assertFalse(ConsoleTransactionRegistry.isInTransaction(consoleId));
    }

    private static DbConnectionContextServiceImpl service(Set<Long> visibleDatasourceIds) {
        DbConnectionContextServiceImpl service = new DbConnectionContextServiceImpl();
        setField(service, "workspaceDataSourceService",
                new VisibleWorkspaceDataSourceService(visibleDatasourceIds));
        return service;
    }

    private static DbConnectionContextRequest request(long consoleId, long dataSourceId) {
        DbConnectionContextRequest request = new DbConnectionContextRequest();
        request.setConsoleId(consoleId);
        request.setDataSourceId(dataSourceId);
        return request;
    }

    private static ConnectInfo registerBound(long consoleId, long dataSourceId, Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConsoleId(consoleId);
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        assertTrue(ConsoleTransactionRegistry.registerIfAbsent(consoleId, connectInfo));
        return connectInfo;
    }

    private static Connection proxyConnection(AtomicInteger commits, AtomicInteger rollbacks) {
        return (Connection) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks.incrementAndGet();
                        yield null;
                    }
                    case "setAutoCommit", "close", "abort" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Connection rollbackFailingConnection() {
        return (Connection) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "rollback" -> throw new SQLException("rollback failed");
                    case "close", "abort" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = DbConnectionContextServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record VisibleWorkspaceDataSourceService(Set<Long> visibleDatasourceIds)
            implements IDbWorkspaceDataSourceService {

        @Override
        public PageResponse<WorkspaceDataSource> listDataSources(DbDataSourcePageQueryRequest request) {
            return PageResponse.empty(1, 10);
        }

        @Override
        public WorkspaceDataSource queryDataSourceById(Long id, Boolean requestPassword) {
            if (!visibleDatasourceIds.contains(id)) {
                return null;
            }
            WorkspaceDataSource dataSource = new WorkspaceDataSource();
            dataSource.setId(id);
            return dataSource;
        }

        @Override
        public WorkspaceDataSource queryDisplayDataSourceById(Long id, Boolean requestPassword) {
            return queryDataSourceById(id, requestPassword);
        }

        @Override
        public void preConnect(DbDataSourcePreConnectRequest request) {
        }

        @Override
        public WorkspaceDataSource createDataSource(WorkspaceDataSource dataSource) {
            return dataSource;
        }

        @Override
        public WorkspaceDataSource updateDataSource(WorkspaceDataSource dataSource) {
            return dataSource;
        }

        @Override
        public WorkspaceDataSource updateDataSourceIdentityColor(Long id, String identityColor) {
            return queryDataSourceById(id, false);
        }

        @Override
        public void deleteDataSource(Long id) {
        }

        @Override
        public List<WorkspaceDataSource> exportDataSources(List<Long> datasourceIds) {
            return List.of();
        }

        @Override
        public List<WorkspaceDataSource> exportDisplayDataSources(List<Long> datasourceIds) {
            return List.of();
        }
    }
}
