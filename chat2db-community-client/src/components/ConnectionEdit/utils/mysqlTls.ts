import { IMysqlTlsConfig } from '@/typings';

const MYSQL_TLS_FORM_FIELDS: Record<string, keyof IMysqlTlsConfig> = {
  sslTlsMode: 'tlsMode',
  sslCaPem: 'caPem',
  sslClientCertPem: 'clientCertPem',
  sslClientPrivateKeyPem: 'clientPrivateKeyPem',
  sslClientKeyPassword: 'clientKeyPassword',
  sslKeyStoreType: 'keyStoreType',
  sslKeyStoreBytes: 'keyStoreBytes',
  sslKeyStorePassword: 'keyStorePassword',
};

const MYSQL_TLS_SECRET_FIELDS: Array<keyof IMysqlTlsConfig> = [
  'caPem',
  'clientCertPem',
  'clientPrivateKeyPem',
  'clientKeyPassword',
  'keyStoreType',
  'keyStoreBytes',
  'keyStorePassword',
];

export function expandMysqlTlsConfig(connectionData: { ssl?: IMysqlTlsConfig | null }) {
  const ssl = connectionData.ssl || {};
  return {
    sslTlsMode: ssl.tlsMode || 'DISABLED',
    sslCaPem: ssl.caPem || '',
    sslClientCertPem: ssl.clientCertPem || '',
    sslClientPrivateKeyPem: ssl.clientPrivateKeyPem || '',
    sslClientKeyPassword: ssl.clientKeyPassword || '',
    sslKeyStoreType: ssl.keyStoreType || '',
    sslKeyStoreBytes: ssl.keyStoreBytes || '',
    sslKeyStorePassword: ssl.keyStorePassword || '',
  };
}

export function collectMysqlTlsPayload(data: Record<string, any>): IMysqlTlsConfig {
  const ssl: IMysqlTlsConfig = {};
  Object.entries(MYSQL_TLS_FORM_FIELDS).forEach(([formField, sslField]) => {
    ssl[sslField] = data[formField];
    delete data[formField];
  });

  ssl.tlsMode = ssl.tlsMode || 'DISABLED';
  if (ssl.tlsMode === 'DISABLED') {
    MYSQL_TLS_SECRET_FIELDS.forEach((field) => {
      ssl[field] = '';
    });
  }

  return ssl;
}
