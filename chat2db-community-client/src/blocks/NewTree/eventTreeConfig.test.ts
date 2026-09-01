import assert from 'node:assert/strict';
import { createEventTreeNodeKey } from './eventTreeIdentity';

const eventKey = createEventTreeNodeKey({
  dataSourceId: 7,
  databaseName: 'app',
  eventName: 'daily_rollup',
});

assert.equal(eventKey, 'dataSource_7-database_app-events_chat2dbCatalogue-event_daily_rollup');

console.log('Event tree config tests passed');
