import assert from 'node:assert/strict';
import {
  buildPartitionDdlExecuteRequest,
  executePartitionPreviewSql,
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
