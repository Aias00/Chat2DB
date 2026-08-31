import { DatabaseTypeCode } from '@/constants/common';
import { OperationColumn } from '@/constants/tree';

const MYSQL_TABLE_MAINTENANCE_OPERATIONS: readonly OperationColumn[] = [
  OperationColumn.AnalyzeTable,
  OperationColumn.OptimizeTable,
  OperationColumn.CheckTable,
  OperationColumn.RepairTable,
] as const;

const MYSQL_REPAIR_ENGINES = new Set(['MYISAM', 'ARCHIVE', 'CSV']);

export function canShowTableMaintenanceOperation(
  operation: OperationColumn,
  databaseType?: DatabaseTypeCode,
  engine?: string,
): boolean {
  if (!MYSQL_TABLE_MAINTENANCE_OPERATIONS.includes(operation)) {
    return true;
  }
  if (databaseType !== DatabaseTypeCode.MYSQL) {
    return false;
  }
  if (operation !== OperationColumn.RepairTable) {
    return true;
  }
  return !!engine && MYSQL_REPAIR_ENGINES.has(engine.trim().toUpperCase());
}

export function getSupportedTableMaintenanceOperations(
  databaseType?: DatabaseTypeCode,
  engine?: string,
): OperationColumn[] {
  return MYSQL_TABLE_MAINTENANCE_OPERATIONS.filter((operation) =>
    canShowTableMaintenanceOperation(operation, databaseType, engine),
  );
}
