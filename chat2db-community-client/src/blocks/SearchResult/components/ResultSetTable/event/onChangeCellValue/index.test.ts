import assert from 'node:assert/strict';
import onChangeCellValue from './index';

let listener: ((event: any) => void) | undefined;
let restoreCount = 0;
let trackedChangeCount = 0;
let largeValue = true;

const tableInstance = {
  on: (_event: string, callback: (event: any) => void) => {
    listener = callback;
    return 1;
  },
  getRecordByCell: () => ({
    CHAT2DB_ROW_NUMBER: 'row-1',
    __CHAT2DB_CELL_META__: { 1: { largeValue } },
  }),
  getHeaderField: () => 'value',
  changeCellValue: (col: number, row: number) => {
    restoreCount += 1;
    assert.ok(listener);
    listener({ col, row, currentValue: 'attempted edit', changedValue: 'original preview' });
  },
};

onChangeCellValue(tableInstance as any, () => {
  trackedChangeCount += 1;
});

assert.ok(listener);
listener({ col: 1, row: 1, currentValue: 'original preview', changedValue: 'attempted edit' });
assert.equal(restoreCount, 1, 'the synthetic VTable event must not recursively restore the cell again');
assert.equal(trackedChangeCount, 0, 'large-value preview restores must not be tracked as user edits');

largeValue = false;
listener({ col: 1, row: 1, currentValue: 'before', changedValue: 'after' });
assert.equal(trackedChangeCount, 1, 'ordinary cell edits should still reach the operation tracker');

console.log('onChangeCellValue tests passed');
