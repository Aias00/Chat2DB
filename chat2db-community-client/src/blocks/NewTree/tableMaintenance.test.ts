import assert from 'node:assert/strict';
import { DatabaseTypeCode } from '@/constants/common';
import { OperationColumn } from '@/constants/tree';
import { canShowTableMaintenanceOperation, getSupportedTableMaintenanceOperations } from './tableMaintenance';

assert.deepEqual(
  getSupportedTableMaintenanceOperations(DatabaseTypeCode.MYSQL, 'InnoDB'),
  [OperationColumn.AnalyzeTable, OperationColumn.OptimizeTable, OperationColumn.CheckTable],
  'InnoDB must not offer REPAIR TABLE',
);

assert.deepEqual(
  getSupportedTableMaintenanceOperations(DatabaseTypeCode.MYSQL, 'MyISAM'),
  [
    OperationColumn.AnalyzeTable,
    OperationColumn.OptimizeTable,
    OperationColumn.CheckTable,
    OperationColumn.RepairTable,
  ],
  'MyISAM offers the full MySQL table maintenance menu',
);

assert.deepEqual(
  getSupportedTableMaintenanceOperations(DatabaseTypeCode.MYSQL, 'archive'),
  [
    OperationColumn.AnalyzeTable,
    OperationColumn.OptimizeTable,
    OperationColumn.CheckTable,
    OperationColumn.RepairTable,
  ],
  'ARCHIVE engine matching is case-insensitive',
);

assert.deepEqual(
  getSupportedTableMaintenanceOperations(DatabaseTypeCode.MYSQL, 'CSV'),
  [
    OperationColumn.AnalyzeTable,
    OperationColumn.OptimizeTable,
    OperationColumn.CheckTable,
    OperationColumn.RepairTable,
  ],
  'CSV is repairable on MySQL 5.7 and 8.0',
);

assert.equal(
  canShowTableMaintenanceOperation(OperationColumn.RepairTable, DatabaseTypeCode.POSTGRESQL, 'MyISAM'),
  false,
  'non-MySQL table menus must not offer MySQL maintenance operations',
);

assert.equal(
  canShowTableMaintenanceOperation(OperationColumn.RepairTable, DatabaseTypeCode.MYSQL, undefined),
  false,
  'unknown engines must not offer REPAIR TABLE',
);

console.log('Table maintenance menu capability tests passed');
