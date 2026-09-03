import assert from 'node:assert/strict';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';
import { ensureManualTransactionStarted, type TransactionStateAccess } from './transactionExecution';
import { TransactionIsolationLevel, TransactionMode } from '@/constants/transaction';

function stateAccess(initial?: TransactionState) {
  let state = initial;
  const access: TransactionStateAccess = {
    getTransactionState: () => state,
    setTransactionState: (_consoleId, patch) => {
      state = {
        ...(state || {
          mode: TransactionMode.AUTO,
          inTransaction: false,
          isolationLevel: TransactionIsolationLevel.DEFAULT,
          supportedIsolationLevels: [],
        }),
        ...patch,
      };
    },
  };
  return { access, current: () => state };
}

const params = {
  sql: 'UPDATE orders SET status = 1',
  dataSourceId: 7,
  databaseName: 'shop',
  consoleId: 42,
};

async function run() {
{
  const state = stateAccess({
    mode: TransactionMode.AUTO,
    inTransaction: false,
    isolationLevel: TransactionIsolationLevel.DEFAULT,
    supportedIsolationLevels: [],
  });
  let calls = 0;
  await ensureManualTransactionStarted(params, state.access, async () => {
    calls += 1;
    return {
      inTransaction: true,
      mode: TransactionMode.MANUAL,
      isolationLevel: TransactionIsolationLevel.DEFAULT,
      supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT],
    };
  });
  assert.equal(calls, 0, 'auto-commit execution must not begin a manual transaction');
}

{
  const state = stateAccess({
    mode: TransactionMode.MANUAL,
    inTransaction: true,
    isolationLevel: TransactionIsolationLevel.READ_COMMITTED,
    supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT, TransactionIsolationLevel.READ_COMMITTED],
  });
  let calls = 0;
  await ensureManualTransactionStarted(params, state.access, async () => {
    calls += 1;
    return {
      inTransaction: true,
      mode: TransactionMode.MANUAL,
      isolationLevel: TransactionIsolationLevel.READ_COMMITTED,
      supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT, TransactionIsolationLevel.READ_COMMITTED],
    };
  });
  assert.equal(calls, 0, 'an open console transaction must keep using its existing exclusive connection');
}

{
  const state = stateAccess({
    mode: TransactionMode.MANUAL,
    inTransaction: false,
    isolationLevel: TransactionIsolationLevel.REPEATABLE_READ,
    supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT, TransactionIsolationLevel.REPEATABLE_READ],
  });
  await ensureManualTransactionStarted(params, state.access, async (request) => {
    assert.equal(request.consoleId, 42);
    assert.equal(request.dataSourceId, 7);
    assert.equal(request.isolationLevel, TransactionIsolationLevel.REPEATABLE_READ);
    return {
      inTransaction: true,
      mode: TransactionMode.MANUAL,
      isolationLevel: TransactionIsolationLevel.REPEATABLE_READ,
      supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT, TransactionIsolationLevel.REPEATABLE_READ],
    };
  });
  assert.equal(state.current()?.inTransaction, true);
  assert.equal(state.current()?.isolationLevel, TransactionIsolationLevel.REPEATABLE_READ);
  assert.deepEqual(state.current()?.supportedIsolationLevels, [
    TransactionIsolationLevel.DEFAULT,
    TransactionIsolationLevel.REPEATABLE_READ,
  ]);
  assert.equal(state.current()?.lastError, undefined);
}

{
  const state = stateAccess({
    mode: TransactionMode.MANUAL,
    inTransaction: false,
    isolationLevel: TransactionIsolationLevel.DEFAULT,
    supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT],
  });
  await assert.rejects(
    ensureManualTransactionStarted(params, state.access, async () => {
      throw new Error('begin unavailable');
    }),
    /begin unavailable/,
  );
  assert.equal(state.current()?.inTransaction, false);
  assert.equal(state.current()?.lastError, 'begin unavailable');
}

{
  const state = stateAccess({
    mode: TransactionMode.MANUAL,
    inTransaction: false,
    isolationLevel: TransactionIsolationLevel.DEFAULT,
    supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT],
  });
  await assert.rejects(
    ensureManualTransactionStarted(params, state.access, async () => ({
      inTransaction: false,
      mode: TransactionMode.AUTO,
      isolationLevel: TransactionIsolationLevel.DEFAULT,
      supportedIsolationLevels: [TransactionIsolationLevel.DEFAULT],
      lastError: 'server refused manual mode',
    })),
    /server refused manual mode/,
  );
  assert.equal(state.current()?.inTransaction, false);
  assert.equal(state.current()?.lastError, 'server refused manual mode');
}

  console.log('Transaction execution tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
