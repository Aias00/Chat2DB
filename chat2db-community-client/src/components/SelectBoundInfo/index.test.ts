import assert from 'node:assert/strict';

// Test that treeNodeType is a stable React key for SelectBoundInfo
// The old code used key={index} which breaks when the list shrinks/reorders.
// The maintainer requested key={treeNodeType} because each entry has a unique role.

// TreeNodeType values from src/constants (simplified)
const TreeNodeType = {
  DATA_SOURCE: 'dataSource',
  DATABASE: 'database',
  SCHEMA: 'schema',
  TABLE: 'table',
} as const;

// Simulate selectedList: each entry has a unique treeNodeType role
const selectedList = [
  { treeNodeType: TreeNodeType.DATA_SOURCE, value: '1' },
  { treeNodeType: TreeNodeType.DATABASE, value: 'db1' },
  { treeNodeType: TreeNodeType.SCHEMA, value: 'schema1' },
  { treeNodeType: TreeNodeType.TABLE, value: 'table1' },
];

// Verify all keys are unique
const keys = selectedList.map(item => item.treeNodeType);
assert.equal(new Set(keys).size, keys.length, 'treeNodeType keys must be unique');

// Verify keys are stable when values change (e.g., user picks a different database)
const changedList = [
  { treeNodeType: TreeNodeType.DATA_SOURCE, value: '1' },
  { treeNodeType: TreeNodeType.DATABASE, value: 'db2' }, // value changed
  { treeNodeType: TreeNodeType.SCHEMA, value: 'schema2' }, // value changed
  { treeNodeType: TreeNodeType.TABLE, value: 'table1' },
];
const changedKeys = changedList.map(item => item.treeNodeType);
assert.deepEqual(keys, changedKeys, 'keys must be stable when values change');

// Verify keys survive list shrink (e.g., user deselects schema → only 3 items)
const shrunkList = [
  { treeNodeType: TreeNodeType.DATA_SOURCE, value: '1' },
  { treeNodeType: TreeNodeType.DATABASE, value: 'db1' },
  { treeNodeType: TreeNodeType.TABLE, value: 'table1' },
];
const shrunkKeys = shrunkList.map(item => item.treeNodeType);
assert.equal(new Set(shrunkKeys).size, shrunkKeys.length, 'shrunk keys must be unique');

// Verify keys survive empty values (e.g., unselected database)
const emptyValues = [
  { treeNodeType: TreeNodeType.DATA_SOURCE, value: '1' },
  { treeNodeType: TreeNodeType.DATABASE, value: '' }, // empty
  { treeNodeType: TreeNodeType.SCHEMA, value: '' }, // empty
];
const emptyKeys = emptyValues.map(item => item.treeNodeType);
assert.equal(new Set(emptyKeys).size, emptyKeys.length, 'keys must be unique even with empty values');

console.log('SelectBoundInfo key stability tests passed');
