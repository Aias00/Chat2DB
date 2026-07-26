import assert from 'node:assert/strict';

// Test the action-list filtering logic for ComboAxisSelect.
// Pattern: filter by action key instead of splice by index.

interface Action { key: string }
const allActions: Action[] = [
  { key: 'move-up' },
  { key: 'move-down' },
  { key: 'delete-axis' },
];

function filterActions(isFirst: boolean, isLast: boolean): Action[] {
  return allActions.filter((action) => {
    if (isFirst && action.key === 'move-up') return false;
    if (isLast && action.key === 'move-down') return false;
    return true;
  });
}

// Middle axis (not first, not last) — all 3 actions
assert.deepEqual(filterActions(false, false).map(a => a.key), ['move-up', 'move-down', 'delete-axis']);

// First only (not last) — remove move-up
assert.deepEqual(filterActions(true, false).map(a => a.key), ['move-down', 'delete-axis']);

// Last only (not first) — remove move-down
assert.deepEqual(filterActions(false, true).map(a => a.key), ['move-up', 'delete-axis']);

// Both first and last (single axis) — remove move-up AND move-down, keep delete
assert.deepEqual(filterActions(true, true).map(a => a.key), ['delete-axis']);

console.log('ComboAxisSelect action filter tests passed');
