import assert from 'node:assert/strict';
import { AccountActionType } from '@/service/accountAdmin';
import {
  buildAccountSecurityCommand,
  createAccountSecurityInitialValues,
} from './accountSecurity';

const selectedAccount = {
  user: 'sec002_ssl',
  host: '%',
  authenticationPlugin: 'caching_sha2_password',
  tlsRequirement: 'SPECIFIED',
  tlsCipher: 'AES256',
  tlsIssuer: 'CN=issuer',
  tlsSubject: 'CN=subject',
};

assert.deepEqual(createAccountSecurityInitialValues(selectedAccount), {
  user: 'sec002_ssl',
  host: '%',
  password: '',
  authPlugin: 'caching_sha2_password',
  tlsRequirement: 'SPECIFIED',
  tlsCipher: 'AES256',
  tlsIssuer: 'CN=issuer',
  tlsSubject: 'CN=subject',
});

const command = buildAccountSecurityCommand({
  dataSourceId: 1,
  actionType: AccountActionType.ALTER_AUTH_PLUGIN,
  values: {
    user: 'sec002_ssl',
    host: '%',
    password: 'secret',
    authPlugin: 'mysql_native_password',
    tlsRequirement: 'NONE',
    tlsCipher: 'stale-cipher',
    tlsIssuer: 'stale-issuer',
    tlsSubject: 'stale-subject',
  },
});

assert.deepEqual(command, {
  dataSourceId: 1,
  user: 'sec002_ssl',
  host: '%',
  password: 'secret',
  authPlugin: 'mysql_native_password',
  tlsRequirement: 'NONE',
  actionType: AccountActionType.ALTER_AUTH_PLUGIN,
});

console.log('account security helpers passed');
