import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = (path: string) => readFileSync(resolve(root, path), 'utf8');

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
assert.match(
  accountPanel,
  /roleManagementSupported\s*&&\s*!selectedAccount\.role/,
  'role management controls must stay hidden when capability gates MySQL 5.7 or role nodes',
);
assert.match(accountPanel, /setConfirmPreviewState\(previewState\)/, 'privilege changes must go through final SQL confirmation');
assert.match(accountPanel, /getDestructiveConfirmText/, 'destructive account operations must require typed confirmation');
assert.match(
  accountPanel,
  /DROP_USER[\s\S]*DROP_ROLE[\s\S]*return `\$\{command\.user\}@\$\{command\.host\}`/,
  'drop user/role confirmation must be tied to the exact account user and host',
);
for (const mode of ['SELECTED', 'ALL', 'NONE']) {
  assert.match(accountPanel, new RegExp(`value:\\s*'${mode}'`), `default role UI should expose ${mode}`);
}

console.log('Account admin frontend contract tests passed');
