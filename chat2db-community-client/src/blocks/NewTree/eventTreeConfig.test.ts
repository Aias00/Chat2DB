import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createEventTreeNodeDescription, createEventTreeNodeKey, supportsEventTree } from './eventTreeIdentity';

const eventKey = createEventTreeNodeKey({
  dataSourceId: 7,
  databaseName: 'app',
  eventName: 'daily_rollup',
});

assert.equal(eventKey, 'dataSource_7-database_app-events_chat2dbCatalogue-event_daily_rollup');

assert.equal(supportsEventTree('MYSQL'), true);
assert.equal(supportsEventTree('POSTGRESQL'), false);
assert.equal(supportsEventTree(undefined), false);

assert.equal(createEventTreeNodeDescription('ENABLED', true, 'Scheduler off'), 'ENABLED');
assert.equal(createEventTreeNodeDescription('ENABLED', false, 'Scheduler off'), 'ENABLED - Scheduler off');
assert.equal(createEventTreeNodeDescription(null, false, 'Scheduler off'), 'Scheduler off');

const menuSource = readFileSync('src/blocks/NewTree/hooks/useCreateRightClickMenu.tsx', 'utf8');
const dropEventSource = menuSource.slice(
  menuSource.indexOf('[OperationColumn.DropEvent]'),
  menuSource.indexOf('[OperationColumn.CreateAccount]'),
);
assert.ok(dropEventSource.indexOf('.getEventDropSql') < dropEventSource.indexOf('openUnifiedConfirmationModal'));
assert.match(dropEventSource, /content:\s*<pre[^>]*>\{sql\}<\/pre>/);
assert.ok(dropEventSource.indexOf('executeDDL') > dropEventSource.indexOf('onOk'));

console.log('Event tree config tests passed');
