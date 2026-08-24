import assert from 'node:assert/strict';
import { collectMysqlTlsPayload, expandMysqlTlsConfig } from './mysqlTls';

const expanded = expandMysqlTlsConfig({
  ssl: {
    tlsMode: 'VERIFY_IDENTITY',
    caPem: 'ca',
    clientCertPem: 'cert',
    clientPrivateKeyPem: 'key',
    clientKeyPassword: 'key-password',
    keyStoreType: 'PKCS12',
    keyStoreBytes: 'base64',
    keyStorePassword: 'store-password',
  },
});

assert.equal(expanded.sslTlsMode, 'VERIFY_IDENTITY');
assert.equal(expanded.sslCaPem, 'ca');
assert.equal(expanded.sslClientPrivateKeyPem, 'key');
assert.equal(expanded.sslKeyStorePassword, 'store-password');

const enabledFormData: Record<string, any> = {
  alias: 'mysql',
  sslTlsMode: 'VERIFY_CA',
  sslCaPem: 'ca',
  sslClientCertPem: 'cert',
  sslClientPrivateKeyPem: 'key',
  sslClientKeyPassword: 'key-password',
  sslKeyStoreType: 'JKS',
  sslKeyStoreBytes: 'base64',
  sslKeyStorePassword: 'store-password',
};
const enabledPayload = collectMysqlTlsPayload(enabledFormData);
assert.deepEqual(enabledPayload, {
  tlsMode: 'VERIFY_CA',
  caPem: 'ca',
  clientCertPem: 'cert',
  clientPrivateKeyPem: 'key',
  clientKeyPassword: 'key-password',
  keyStoreType: 'JKS',
  keyStoreBytes: 'base64',
  keyStorePassword: 'store-password',
});
assert.equal(enabledFormData.alias, 'mysql');
assert.equal('sslTlsMode' in enabledFormData, false);

const disabledFormData: Record<string, any> = {
  sslTlsMode: 'DISABLED',
  sslCaPem: 'old-ca',
  sslClientCertPem: 'old-cert',
  sslClientPrivateKeyPem: 'old-key',
  sslClientKeyPassword: 'old-key-password',
  sslKeyStoreType: 'PKCS12',
  sslKeyStoreBytes: 'old-store',
  sslKeyStorePassword: 'old-store-password',
};
assert.deepEqual(collectMysqlTlsPayload(disabledFormData), {
  tlsMode: 'DISABLED',
  caPem: '',
  clientCertPem: '',
  clientPrivateKeyPem: '',
  clientKeyPassword: '',
  keyStoreType: '',
  keyStoreBytes: '',
  keyStorePassword: '',
});

console.log('mysql TLS connection form helpers passed');
