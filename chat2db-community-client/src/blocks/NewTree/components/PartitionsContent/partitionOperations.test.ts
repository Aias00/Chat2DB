import assert from 'node:assert/strict';
import {
  buildPartitionDdlExecuteRequest,
  defaultPartitionDefinition,
  executePartitionPreviewSql,
  getPartitionOperationAvailability,
  isPartitionDropConfirmationValid,
} from './partitionOperations';

const context = {
  dataSourceId: 42,
  databaseName: 'orders_db',
  tableName: 'orders',
};
const sql = 'ALTER TABLE `orders_db`.`orders` TRUNCATE PARTITION `p202401`';

assert.deepEqual(
  buildPartitionDdlExecuteRequest(context, sql),
  {
    dataSourceId: 42,
    databaseName: 'orders_db',
    tableName: 'orders',
    sql,
  },
  'partition DDL execution must stay bound to the selected datasource/database/table',
);

assert.deepEqual(
  getPartitionOperationAvailability('RANGE COLUMNS'),
  {
    add: true,
    drop: true,
    truncate: true,
    reorganize: true,
    coalesce: false,
    maintain: true,
  },
  'RANGE/LIST partitions expose ADD, DROP, TRUNCATE, REORGANIZE, and maintenance only',
);

assert.deepEqual(
  getPartitionOperationAvailability('LINEAR HASH'),
  {
    add: true,
    drop: false,
    truncate: false,
    reorganize: false,
    coalesce: true,
    maintain: true,
  },
  'HASH/KEY partitions expose ADD, COALESCE, and maintenance only',
);

assert.deepEqual(
  getPartitionOperationAvailability(null),
  {
    add: false,
    drop: false,
    truncate: false,
    reorganize: false,
    coalesce: false,
    maintain: false,
  },
  'non-partitioned tables expose no partition maintenance actions',
);

assert.equal(defaultPartitionDefinition('LIST COLUMNS'), 'VALUES IN (...)');
assert.equal(defaultPartitionDefinition('RANGE'), 'VALUES LESS THAN (...)');
assert.equal(isPartitionDropConfirmationValid('p202401', ' p202401 '), true);
assert.equal(isPartitionDropConfirmationValid('p202401', 'p202402'), false);

async function main() {
  let executedPayload: unknown;
  let refreshCount = 0;
  await executePartitionPreviewSql({
    context,
    sql,
    executeDDL: async (payload) => {
      executedPayload = payload;
      return { success: true, message: '', originalSql: sql };
    },
    refresh: () => {
      refreshCount++;
    },
  });

  assert.deepEqual(executedPayload, {
    dataSourceId: 42,
    databaseName: 'orders_db',
    tableName: 'orders',
    sql,
  });
  assert.equal(refreshCount, 1, 'successful partition DDL execution refreshes readback');

  console.log('Partition operation tests passed');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
