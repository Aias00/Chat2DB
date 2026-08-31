import type { IDdlExecuteRequest } from '@/service/dmlRequest';

export interface PartitionOperationContext {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string | null;
  tableName: string;
}

export function buildPartitionDdlExecuteRequest(
  context: PartitionOperationContext,
  sql: string,
): IDdlExecuteRequest {
  const request: IDdlExecuteRequest = {
    dataSourceId: context.dataSourceId,
    sql,
    tableName: context.tableName,
  };
  if (context.databaseName !== undefined) {
    request.databaseName = context.databaseName;
  }
  if (context.schemaName !== undefined) {
    request.schemaName = context.schemaName;
  }
  return request;
}

export async function executePartitionPreviewSql({
  context,
  sql,
  executeDDL,
  refresh,
}: {
  context: PartitionOperationContext;
  sql: string;
  executeDDL: (request: IDdlExecuteRequest) => Promise<unknown>;
  refresh: () => void | Promise<void>;
}) {
  await executeDDL(buildPartitionDdlExecuteRequest(context, sql));
  await refresh();
}
