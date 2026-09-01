export function createEventTreeNodeKey(params: {
  dataSourceId?: string | number | null;
  databaseName?: string | null;
  eventName?: string | null;
}) {
  return [
    `dataSource_${params.dataSourceId}`,
    `database_${params.databaseName}`,
    'events_chat2dbCatalogue',
    `event_${params.eventName}`,
  ].join('-');
}
