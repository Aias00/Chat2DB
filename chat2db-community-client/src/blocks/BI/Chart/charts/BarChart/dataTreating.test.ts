import assert from 'node:assert/strict';

// Test the sort comparator used in BarChart/LineChart/ComboChart dataTreating
// Pattern: dataToSort[a] < dataToSort[b] ? -1 : dataToSort[a] > dataToSort[b] ? 1 : 0

function comparator(a: number, b: number, values: number[]): number {
  const result = values[a] < values[b] ? -1 : values[a] > values[b] ? 1 : 0;
  return result;
}

const dataToSort = [30, 10, 20, 10, 30];
const indices = [0, 1, 2, 3, 4];

// ASC
const ascSorted = [...indices].sort((a, b) => comparator(a, b, dataToSort));
assert.deepEqual(ascSorted, [1, 3, 2, 0, 4]); // 10, 10, 20, 30, 30 — equal values keep stable order

// DESC
const descSorted = [...indices].sort((a, b) => -comparator(a, b, dataToSort));
assert.deepEqual(descSorted, [0, 4, 2, 1, 3]); // 30, 30, 20, 10, 10 — equal values keep stable order

// Equal values — comparator returns 0, sort is stable
assert.equal(comparator(0, 4, [30, 30]), 0); // both 30 → 0
assert.equal(comparator(1, 3, [10, 10]), 0); // both 10 → 0

// Single element
assert.deepEqual([0].sort((a, b) => comparator(a, b, [42])), [0]);

// Empty
assert.deepEqual([].sort((a, b) => comparator(a, b, [])), []);

console.log('BI chart comparator tests passed');
