import type { ITransactionRequest, ITransactionStateResponse } from '@/service/transaction';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';
import type { IExecuteSqlParams } from '@/typings';

export interface TransactionStateAccess {
  getTransactionState: (consoleId: number) => TransactionState | undefined;
  setTransactionState: (consoleId: number, patch: Partial<TransactionState>) => void;
}

export type BeginTransaction = (request: ITransactionRequest) => Promise<ITransactionStateResponse>;

export async function ensureManualTransactionStarted(
  params: IExecuteSqlParams,
  stateAccess: TransactionStateAccess,
  beginTransaction: BeginTransaction,
) {
  const { consoleId, dataSourceId } = params;
  if (typeof consoleId !== 'number' || dataSourceId == null) {
    return;
  }
  const state = stateAccess.getTransactionState(consoleId);
  if (!state || state.mode !== 'manual' || state.inTransaction) {
    return;
  }

  try {
    const result = await beginTransaction({
      dataSourceId,
      databaseName: params.databaseName,
      schemaName: params.schemaName,
      consoleId,
    });
    if (!result?.inTransaction) {
      throw new Error(result?.lastError || 'Failed to start the manual transaction');
    }
    stateAccess.setTransactionState(consoleId, {
      inTransaction: true,
      lastError: result.lastError,
    });
  } catch (error) {
    stateAccess.setTransactionState(consoleId, {
      inTransaction: false,
      lastError: error instanceof Error ? error.message : String(error),
    });
    throw error;
  }
}
