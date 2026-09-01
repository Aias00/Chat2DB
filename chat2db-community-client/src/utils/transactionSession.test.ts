import assert from 'node:assert/strict';
import { releaseTransactionConsoles } from './transactionSessionCore';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';

async function run() {
{
  const patches: Partial<TransactionState>[] = [];
  const success = await releaseTransactionConsoles(
    [{ consoleId: 42, dataSourceId: 7, databaseName: 'shop', schemaName: 'public' }],
    'rollback',
    {
      store: {
        setTransactionState: (_consoleId, patch) => patches.push(patch),
      },
      releaseTransaction: async (request) => {
        assert.equal(request.consoleId, 42);
        assert.equal(request.dataSourceId, 7);
        return {
          inTransaction: false,
          mode: 'auto',
          outcome: 'UNKNOWN',
          lastError: 'rollback outcome unknown',
        };
      },
      commitTransaction: async () => {
        throw new Error('commit must not be called for rollback close');
      },
    },
  );

  assert.equal(success, true);
  assert.deepEqual(patches, [
    {
      mode: 'auto',
      inTransaction: false,
      lastOutcome: 'UNKNOWN',
      lastError: 'rollback outcome unknown',
    },
  ]);
}

{
  const patches: Partial<TransactionState>[] = [];
  const success = await releaseTransactionConsoles(
    [{ consoleId: 43, dataSourceId: 8 }],
    'commit',
    {
      store: {
        setTransactionState: (_consoleId, patch) => patches.push(patch),
      },
      commitTransaction: async () => ({
        inTransaction: false,
        mode: 'auto',
        outcome: 'COMMITTED',
      }),
      releaseTransaction: async () => {
        throw new Error('release must not be called for commit close');
      },
    },
  );

  assert.equal(success, true);
  assert.equal(patches[0]?.lastOutcome, 'COMMITTED');
}

  console.log('Transaction session tests passed');
}

run().catch((error) => {
  console.error(error);
  process.exit(1);
});
