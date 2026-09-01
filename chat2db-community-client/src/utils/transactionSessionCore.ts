import type { ITransactionRequest, ITransactionStateResponse } from '@/service/transaction';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';

export interface TxConsole {
  consoleId: number;
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
}

export type CloseAction = 'commit' | 'rollback';

interface TransactionSessionStore {
  setTransactionState: (consoleId: number, patch: Partial<TransactionState>) => void;
}

export interface ReleaseTransactionDependencies {
  store: TransactionSessionStore;
  commitTransaction: (request: ITransactionRequest) => Promise<ITransactionStateResponse>;
  releaseTransaction: (request: ITransactionRequest) => Promise<ITransactionStateResponse>;
}

export async function releaseTransactionConsoles(
  consoles: TxConsole[],
  action: CloseAction,
  dependencies: ReleaseTransactionDependencies,
): Promise<boolean> {
  const results = await Promise.all(
    consoles.map(async (console) => {
      const request = {
        dataSourceId: console.dataSourceId,
        databaseName: console.databaseName,
        schemaName: console.schemaName,
        consoleId: console.consoleId,
      };
      try {
        const result =
          action === 'commit'
            ? await dependencies.commitTransaction(request)
            : await dependencies.releaseTransaction(request);
        dependencies.store.setTransactionState(console.consoleId, transactionStatePatch(result));
        return true;
      } catch (error) {
        dependencies.store.setTransactionState(console.consoleId, {
          inTransaction: true,
          lastError: String(error),
        });
        return false;
      }
    }),
  );
  return results.every(Boolean);
}

function transactionStatePatch(result: ITransactionStateResponse | undefined): Partial<TransactionState> {
  return {
    mode: result?.mode === 'manual' ? 'manual' : 'auto',
    inTransaction: Boolean(result?.inTransaction),
    lastOutcome: result?.outcome,
    lastError: result?.lastError,
  };
}
