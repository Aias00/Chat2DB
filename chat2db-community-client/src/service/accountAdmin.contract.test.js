const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');

const root = resolve(__dirname, '..');
const source = (path) => readFileSync(resolve(root, path), 'utf8');

const accountAdmin = source('service/accountAdmin.ts');
assert.match(accountAdmin, /defaultRoles\?:\s*Account\[\]/, 'default roles must preserve role user and host');
assert.match(accountAdmin, /activeRoles\?:\s*Account\[\]/, 'active roles must preserve role user and host');
assert.match(accountAdmin, /roleList\?:\s*Account\[\]/, 'selected default roles must preserve per-role host');

for (const action of ['CREATE_ROLE', 'DROP_ROLE', 'GRANT_ROLE', 'REVOKE_ROLE', 'SET_DEFAULT_ROLE']) {
  assert.match(accountAdmin, new RegExp(`${action}\\s*=\\s*'${action}'`), `missing ${action} service action`);
}

const mysqlIntelliSense = source('constants/IntelliSense/mysql.ts');
for (const keyword of ['ROLE', 'CURRENT_ROLE', 'DEFAULT', 'ADMIN', 'OPTION']) {
  assert.match(mysqlIntelliSense, new RegExp(`'${keyword}'`), `MySQL completion should include ${keyword}`);
}

const treeConstants = source('constants/tree.ts');
assert.match(treeConstants, /CreateRole\s*=\s*'createRole'/, 'tree operation should include create role');

const menuConfig = source('blocks/NewTree/menuConfig.tsx');
assert.match(
  menuConfig,
  /TreeNodeType\.DATABASE_ACCOUNTS\]:\s*\[[^\]]*OperationColumn\.CreateAccount[^\]]*OperationColumn\.CreateRole[^\]]*OperationColumn\.Refresh/s,
  'account tree root should expose role creation',
);

const treeConfig = source('blocks/NewTree/treeConfig.tsx');
assert.match(treeConfig, /role:\s*account\.role/, 'tree account nodes should carry role flag');
assert.match(treeConfig, /defaultRoles:\s*account\.defaultRoles/, 'tree account nodes should carry default role readback');

const accountPanel = source('pages/main/workspace/components/AccountPrivilegePanel/index.tsx');
for (const action of ['GRANT_ROLE', 'REVOKE_ROLE', 'SET_DEFAULT_ROLE', 'DROP_ROLE']) {
  assert.match(accountPanel, new RegExp(`AccountActionType\\.${action}`), `panel should expose ${action}`);
}

console.log('Account admin frontend contract tests passed');
