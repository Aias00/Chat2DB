package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.model.runtime.TransactionIsolationLevel;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionPoolTransactionTest {

    @AfterEach
    void tearDown() {
        ConnectionPool.releaseAll(true);
    }

    @Test
    void rollbackWaitsForCommitOnSameConsole() throws Exception {
        long consoleId = 9101L;
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        registerBound(consoleId, blockingCommitConnection(commitEntered, releaseCommit, commits, rollbacks));

        FutureTask<ConnectionPool.TransactionOutcome> commit =
                new FutureTask<>(() -> ConnectionPool.commit(consoleId));
        new Thread(commit, "commit-test").start();
        assertTrue(commitEntered.await(2, TimeUnit.SECONDS));

        FutureTask<ConnectionPool.TransactionOutcome> rollback =
                new FutureTask<>(() -> ConnectionPool.rollback(consoleId));
        new Thread(rollback, "rollback-test").start();

        Thread.sleep(100);
        assertFalse(rollback.isDone());
        assertEquals(0, rollbacks.get());

        releaseCommit.countDown();

        assertEquals(ConnectionPool.TransactionOutcome.COMMITTED,
                commit.get(2, TimeUnit.SECONDS));
        assertEquals(ConnectionPool.TransactionOutcome.RELEASED_WITHOUT_TRANSACTION,
                rollback.get(2, TimeUnit.SECONDS));
        assertEquals(1, commits.get());
        assertEquals(0, rollbacks.get());
    }

    @Test
    void releaseWaitsForInFlightConsoleOperation() throws Exception {
        long consoleId = 9102L;
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch finishOperation = new CountDownLatch(1);
        AtomicInteger rollbacks = new AtomicInteger();
        registerBound(consoleId, countingConnection(new AtomicInteger(), rollbacks));

        FutureTask<Void> operation = new FutureTask<>(() ->
                ConnectionPool.withConsoleLock(consoleId, () -> {
                    operationEntered.countDown();
                    assertTrue(finishOperation.await(2, TimeUnit.SECONDS));
                    return null;
                }));
        new Thread(operation, "operation-test").start();
        assertTrue(operationEntered.await(2, TimeUnit.SECONDS));

        FutureTask<ConnectionPool.TransactionOutcome> release =
                new FutureTask<>(() -> ConnectionPool.release(consoleId, true));
        new Thread(release, "release-test").start();

        Thread.sleep(100);
        assertFalse(release.isDone());
        assertEquals(0, rollbacks.get());

        finishOperation.countDown();

        assertEquals(ConnectionPool.TransactionOutcome.ROLLED_BACK,
                release.get(2, TimeUnit.SECONDS));
        operation.get(2, TimeUnit.SECONDS);
        assertEquals(1, rollbacks.get());
    }

    @Test
    void registeredTransactionRetainsSelectedIsolationLevel() {
        long consoleId = 9103L;
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConsoleId(consoleId);
        connectInfo.setDataSourceId(42L);
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setConnection(countingConnection(new AtomicInteger(), new AtomicInteger()));

        assertTrue(ConnectionPool.registerIfAbsent(
                consoleId,
                connectInfo,
                TransactionIsolationLevel.READ_COMMITTED
        ));

        assertEquals(
                TransactionIsolationLevel.READ_COMMITTED,
                ConnectionPool.getIsolationLevel(consoleId)
        );
        assertEquals(List.of(
                TransactionIsolationLevel.DEFAULT,
                TransactionIsolationLevel.READ_COMMITTED
        ), ConnectionPool.getSupportedIsolationLevels(consoleId));
    }

    @Test
    void consoleOwnedConnectionStaysExclusiveUntilTransactionRelease() {
        long consoleId = 9104L;
        AtomicInteger closes = new AtomicInteger();
        Connection connection = closeCountingConnection(closes);
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConsoleId(consoleId);
        connectInfo.setDataSourceId(42L);
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setConnection(connection);
        assertTrue(ConnectionPool.registerIfAbsent(consoleId, connectInfo));

        ConnectionPool.close(connectInfo);

        assertEquals(0, closes.get());
        assertSame(connection, ConnectionPool.getBoundConnectInfo(consoleId).getConnection());
        assertNull(ConnectionPool.getBoundConnectInfo(consoleId + 1));

        assertEquals(
                ConnectionPool.TransactionOutcome.ROLLED_BACK,
                ConnectionPool.release(consoleId, true)
        );
        assertEquals(1, closes.get());
    }

    @Test
    void idleEvictionRechecksActivityAfterTakingTheConsoleLock() {
        long consoleId = 9105L;
        registerBound(consoleId, countingConnection(new AtomicInteger(), new AtomicInteger()));
        ConnectionPool.BoundTransaction candidate = ConnectionPool.getBoundTransaction(consoleId);
        candidate.touch(0L);

        candidate.touch();

        assertFalse(ConnectionPool.evictIdleCandidate(consoleId, candidate));
        assertTrue(ConnectionPool.isInTransaction(consoleId));
    }

    private static void registerBound(long consoleId, Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConsoleId(consoleId);
        connectInfo.setDataSourceId(42L);
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setConnection(connection);
        assertTrue(ConnectionPool.registerIfAbsent(consoleId, connectInfo));
    }

    private static Connection blockingCommitConnection(CountDownLatch commitEntered,
            CountDownLatch releaseCommit, AtomicInteger commits, AtomicInteger rollbacks) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionPoolTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "commit" -> {
                        commits.incrementAndGet();
                        commitEntered.countDown();
                        assertTrue(releaseCommit.await(2, TimeUnit.SECONDS));
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

    private static Connection countingConnection(AtomicInteger commits, AtomicInteger rollbacks) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionPoolTransactionTest.class.getClassLoader(),
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

    private static Connection closeCountingConnection(AtomicInteger closes) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionPoolTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> {
                        closes.incrementAndGet();
                        yield null;
                    }
                    case "rollback", "setAutoCommit", "abort" -> null;
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
}
