import assert from 'node:assert/strict';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';
import { ensureManualTransactionStarted, type TransactionStateAccess } from './transactionExecution';

function stateAccess(initial?: TransactionState) {
  let state = initial;
  const access: TransactionStateAccess = {
    getTransactionState: () => state,
    setTransactionState: (_consoleId, patch) => {
      state = { ...(state || { mode: 'auto', inTransaction: false }), ...patch };
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
  const state = stateAccess({ mode: 'auto', inTransaction: false });
  let calls = 0;
  await ensureManualTransactionStarted(params, state.access, async () => {
    calls += 1;
    return { inTransaction: true, mode: 'manual' };
  });
  assert.equal(calls, 0, 'auto-commit execution must not begin a manual transaction');
}

{
  const state = stateAccess({ mode: 'manual', inTransaction: true });
  let calls = 0;
  await ensureManualTransactionStarted(params, state.access, async () => {
    calls += 1;
    return { inTransaction: true, mode: 'manual' };
  });
  assert.equal(calls, 0, 'an already-open transaction must be reused');
}

{
  const state = stateAccess({ mode: 'manual', inTransaction: false });
  await ensureManualTransactionStarted(params, state.access, async (request) => {
    assert.equal(request.consoleId, 42);
    assert.equal(request.dataSourceId, 7);
    return { inTransaction: true, mode: 'manual' };
  });
  assert.equal(state.current()?.inTransaction, true);
  assert.equal(state.current()?.lastError, undefined);
}

{
  const state = stateAccess({ mode: 'manual', inTransaction: false });
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
  const state = stateAccess({ mode: 'manual', inTransaction: false });
  await assert.rejects(
    ensureManualTransactionStarted(params, state.access, async () => ({
      inTransaction: false,
      mode: 'auto',
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
