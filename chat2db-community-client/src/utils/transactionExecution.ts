import type { ITransactionBeginRequest, ITransactionStateResponse } from '@/service/transaction';
import type { TransactionState } from '@/store/workspace/slices/console/initialState';
import type { IExecuteSqlParams } from '@/typings';
import { TransactionIsolationLevel, TransactionMode } from '@/constants/transaction';

export interface TransactionStateAccess {
  getTransactionState: (consoleId: number) => TransactionState | undefined;
  setTransactionState: (consoleId: number, patch: Partial<TransactionState>) => void;
}

export type BeginTransaction = (request: ITransactionBeginRequest) => Promise<ITransactionStateResponse>;

export async function ensureManualTransactionStarted(
  params: IExecuteSqlParams,
  stateAccess: TransactionStateAccess,
  beginTransaction: BeginTransaction,
): Promise<ITransactionStateResponse | undefined> {
  const { consoleId, dataSourceId } = params;
  if (typeof consoleId !== 'number' || dataSourceId == null) {
    return;
  }
  const state = stateAccess.getTransactionState(consoleId);
  if (!state || state.mode !== TransactionMode.MANUAL || state.inTransaction) {
    return;
  }

  try {
    const result = await beginTransaction({
      dataSourceId,
      databaseName: params.databaseName,
      schemaName: params.schemaName,
      consoleId,
      isolationLevel: state.isolationLevel ?? TransactionIsolationLevel.DEFAULT,
    });
    if (!result?.inTransaction) {
      throw new Error(result?.lastError || 'Failed to start the manual transaction');
    }
    stateAccess.setTransactionState(consoleId, {
      mode: result.mode,
      inTransaction: true,
      isolationLevel: result.isolationLevel ?? state.isolationLevel ?? TransactionIsolationLevel.DEFAULT,
      supportedIsolationLevels: result.supportedIsolationLevels ?? state.supportedIsolationLevels,
      lastError: result.lastError,
    });
    return result;
  } catch (error) {
    stateAccess.setTransactionState(consoleId, {
      inTransaction: false,
      lastError: error instanceof Error ? error.message : String(error),
    });
    throw error;
  }
}
