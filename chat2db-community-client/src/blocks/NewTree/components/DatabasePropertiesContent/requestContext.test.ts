import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'index.tsx'), 'utf8');

assert.match(
  source,
  /getDatabaseInfo\(\{\s*dataSourceId,\s*databaseName\s*\}\)/s,
  'database charset readback must send dataSourceId with databaseName',
);

assert.match(
  source,
  /previewAlterDatabaseSql\(\{\s*dataSourceId,\s*databaseName,\s*charset:\s*values\.charset,\s*collation:\s*values\.collation\s*\}\)/s,
  'database charset preview must send dataSourceId with databaseName',
);

assert.match(
  source,
  /executeDDL\(\{\s*dataSourceId,\s*sql\s*\}\)/s,
  'database charset execution must keep using the selected dataSourceId',
);

console.log('DatabasePropertiesContent request context tests passed');
