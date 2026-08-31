import React, { memo, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Checkbox, ConfigProvider, Empty, Form, Input, Modal, Select, Space, Spin, Tooltip, theme } from 'antd';
import { DeleteOutlined, KeyOutlined, LockOutlined, UnlockOutlined } from '@ant-design/icons';
import { staticMessage } from '@chat2db/ui';
import i18n from '@/i18n';
import SQLPreview from '@/components/SQLPreview';
import accountAdminService, {
  AccountActionType,
  AccountPrivilege,
  AccountPrivilegeScope,
  formatAccountExecuteMessage,
  type Account,
  type AccountCapability,
  type AccountCommand,
  type AccountExecute,
} from '@/service/accountAdmin';
import connectionService from '@/service/connection';
import sqlService from '@/service/sql';
import { DatabaseTypeCode, TreeNodeType } from '@/constants';
import { useTreeStore } from '@/store/tree';
import { IBoundInfo } from '@/typings';
import styles from './index.less';

interface IProps {
  uniqueData?: IBoundInfo;
}

interface PreviewState {
  sql: string;
  command: AccountCommand;
  previewToken: string;
}

const defaultPrivileges = Object.values(AccountPrivilege);

function createPrivilegeOptions(privileges: AccountPrivilege[] = defaultPrivileges) {
  return privileges.map((privilege) => ({
    label: privilege.replace(/_/g, ' '),
    value: privilege,
  }));
}

const AccountPrivilegePanel = memo((props: IProps) => {
  const { uniqueData } = props;
  const dataSourceId = uniqueData?.dataSourceId;
  const [capability, setCapability] = useState<AccountCapability | null>(null);
  const [previewState, setPreviewState] = useState<PreviewState | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [confirmPreviewState, setConfirmPreviewState] = useState<PreviewState | null>(null);
  const [executeModalOpen, setExecuteModalOpen] = useState(false);
  const [accountModalOpen, setAccountModalOpen] = useState(false);
  const [accountActionType, setAccountActionType] = useState<AccountActionType | null>(null);
  const [executeLoading, setExecuteLoading] = useState(false);
  const [databaseOptions, setDatabaseOptions] = useState<Array<{ label: string; value: string }>>([]);
  const [tableOptions, setTableOptions] = useState<Array<{ label: string; value: string }>>([]);
  const [accountOptions, setAccountOptions] = useState<Account[]>([]);
  const [databaseLoading, setDatabaseLoading] = useState(false);
  const [tableLoading, setTableLoading] = useState(false);
  const [form] = Form.useForm();
  const [accountForm] = Form.useForm();
  const [roleForm] = Form.useForm();
  const previewRequestRef = useRef(0);
  const updateTreeNodeDataByDetail = useTreeStore((state) => state.updateTreeNodeDataByDetail);
  const accountLockSupported = capability?.accountLockSupported !== false;
  const { token } = theme.useToken();
  const submitButtonStyle = useMemo(
    () =>
      ({
        '--account-submit-bg': token.colorPrimary,
        '--account-submit-color': token.colorTextLightSolid,
      } as React.CSSProperties),
    [token.colorPrimary, token.colorTextLightSolid],
  );
  const privilegeOptions = useMemo(
    () => createPrivilegeOptions(capability?.editablePrivileges?.length ? capability.editablePrivileges : undefined),
    [capability?.editablePrivileges],
  );

  const watchedScope = Form.useWatch('scope', form);
  const watchedDatabaseName = Form.useWatch('databaseName', form);
  const watchedTableName = Form.useWatch('tableName', form);
  const watchedPrivileges = Form.useWatch('privileges', form);
  const watchedGrantOption = Form.useWatch('grantOption', form);
  const watchedActionType = Form.useWatch('actionType', form);

  const selectedAccount =
    uniqueData?.user && uniqueData?.host
      ? {
          user: uniqueData.user,
          host: uniqueData.host,
          role: uniqueData.role,
          defaultRoles: uniqueData.defaultRoles,
        }
      : null;
  const roleManagementSupported = capability?.roleManagementSupported === true;
  const roleOptions = useMemo(
    () =>
      accountOptions
        .filter((account) => account.role)
        .map((account) => ({
          label: account.displayName,
          value: roleValue(account),
        })),
    [accountOptions],
  );

  const resetPreview = () => {
    setPreviewState(null);
  };

  const resetConfirmState = () => {
    setConfirmPreviewState(null);
  };

  const refreshUserTree = () => {
    if (!dataSourceId) {
      return;
    }
    loadAccounts();
    updateTreeNodeDataByDetail({
      treeNodeType: TreeNodeType.DATABASE_ACCOUNTS,
      dataSourceId,
      databaseType: uniqueData?.databaseType || DatabaseTypeCode.MYSQL,
    });
  };

  const loadDatabases = () => {
    if (!dataSourceId) {
      setDatabaseOptions([]);
      return;
    }
    setDatabaseLoading(true);
    connectionService
      .getDatabaseList({ dataSourceId })
      .then((list) => {
        setDatabaseOptions(
          (list || []).map((item) => ({
            label: item.name,
            value: item.name,
          })),
        );
      })
      .catch(() => {
        setDatabaseOptions([]);
      })
      .finally(() => {
        setDatabaseLoading(false);
      });
  };

  const loadTables = (databaseName?: string) => {
    setTableOptions([]);
    if (!dataSourceId || !databaseName) {
      return;
    }
    setTableLoading(true);
    sqlService
      .getTableList({
        dataSourceId,
        databaseName,
        pageNo: 1,
        pageSize: 1000,
      })
      .then((res) => {
        setTableOptions(
          (res?.data || []).map((item) => ({
            label: item.name,
            value: item.name,
          })),
        );
      })
      .catch(() => {
        setTableOptions([]);
      })
      .finally(() => {
        setTableLoading(false);
      });
  };

  const loadCapability = () => {
    if (!dataSourceId) {
      return Promise.resolve(null);
    }
    return accountAdminService.capability({ dataSourceId }).then((nextCapability) => {
      setCapability(nextCapability);
      return nextCapability;
    });
  };

  const loadAccounts = () => {
    if (!dataSourceId) {
      setAccountOptions([]);
      return Promise.resolve([]);
    }
    return accountAdminService
      .list({ dataSourceId })
      .then((accounts) => {
        setAccountOptions(accounts || []);
        return accounts || [];
      })
      .catch(() => {
        setAccountOptions([]);
        return [];
      });
  };

  useEffect(() => {
    if (!dataSourceId) {
      resetPreview();
      return;
    }
    loadCapability();
    loadAccounts();
    loadDatabases();
    form.setFieldsValue({
      actionType: AccountActionType.GRANT_PRIVILEGE,
      scope: AccountPrivilegeScope.DATABASE,
      databaseName: uniqueData?.databaseName,
      tableName: undefined,
      privileges: [AccountPrivilege.SELECT],
      grantOption: false,
    });
    resetPreview();
  }, [dataSourceId, uniqueData?.databaseName]);

  useEffect(() => {
    resetPreview();
    resetConfirmState();
    setExecuteModalOpen(false);
    setAccountModalOpen(false);
    setAccountActionType(null);
  }, [dataSourceId, uniqueData?.user, uniqueData?.host]);

  useEffect(() => {
    if (!dataSourceId) {
      return;
    }
    if (watchedScope === AccountPrivilegeScope.GLOBAL) {
      form.setFieldsValue({ databaseName: undefined, tableName: undefined });
      return;
    }
    if (!watchedDatabaseName && databaseOptions.length) {
      form.setFieldsValue({ databaseName: uniqueData?.databaseName || databaseOptions[0].value });
    }
  }, [watchedScope, watchedDatabaseName, databaseOptions, uniqueData?.databaseName]);

  useEffect(() => {
    if (!dataSourceId) {
      return;
    }
    if (watchedScope !== AccountPrivilegeScope.TABLE) {
      form.setFieldsValue({ tableName: undefined });
      setTableOptions([]);
      return;
    }
    form.setFieldsValue({ tableName: undefined });
    loadTables(watchedDatabaseName);
  }, [watchedScope, watchedDatabaseName]);

  useEffect(() => {
    if (!dataSourceId) {
      return;
    }
    if (
      watchedScope === AccountPrivilegeScope.TABLE &&
      tableOptions.length &&
      (!watchedTableName || !tableOptions.some((option) => option.value === watchedTableName))
    ) {
      form.setFieldsValue({ tableName: tableOptions[0].value });
    }
  }, [watchedScope, watchedTableName, tableOptions]);

  useEffect(() => {
    if (!dataSourceId) {
      return;
    }
    if (watchedActionType === AccountActionType.REVOKE_PRIVILEGE) {
      form.setFieldsValue({ grantOption: false });
    }
  }, [watchedActionType]);

  const buildPrivilegeCommand = (): AccountCommand | null => {
    if (!dataSourceId || !selectedAccount) {
      return null;
    }
    const values = form.getFieldsValue();
    return {
      dataSourceId,
      user: selectedAccount.user,
      host: selectedAccount.host,
      actionType: values.actionType,
      scope: values.scope,
      databaseName: values.databaseName,
      tableName: values.tableName,
      privileges: values.privileges,
      grantOption: values.actionType === AccountActionType.GRANT_PRIVILEGE ? values.grantOption : false,
    };
  };

  useEffect(() => {
    const command = buildPrivilegeCommand();
    if (!isPreviewReady(command)) {
      previewRequestRef.current += 1;
      resetPreview();
      setPreviewLoading(false);
      return;
    }

    const readyCommand = command;
    const requestId = previewRequestRef.current + 1;
    previewRequestRef.current = requestId;
    const timer = window.setTimeout(() => {
      if (previewRequestRef.current === requestId) {
        setPreviewLoading(true);
      }
      accountAdminService
        .preview(readyCommand)
        .then((preview) => {
          if (previewRequestRef.current !== requestId) {
            return;
          }
          setPreviewState({
            sql: preview.sql,
            command: readyCommand,
            previewToken: preview.previewToken,
          });
        })
        .catch(() => {
          if (previewRequestRef.current === requestId) {
            resetPreview();
          }
        })
        .finally(() => {
          if (previewRequestRef.current === requestId) {
            setPreviewLoading(false);
          }
        });
    }, 250);

    return () => {
      window.clearTimeout(timer);
    };
  }, [
    dataSourceId,
    selectedAccount?.user,
    selectedAccount?.host,
    watchedActionType,
    watchedScope,
    watchedDatabaseName,
    watchedTableName,
    watchedPrivileges,
    watchedGrantOption,
  ]);

  const previewAndConfirm = (command: AccountCommand) => {
    return accountAdminService.preview(command).then((preview) => {
      setConfirmPreviewState({
        sql: preview.sql,
        command,
        previewToken: preview.previewToken,
      });
      setExecuteModalOpen(true);
    });
  };

  const executePreview = (state: PreviewState | null) => {
    if (!state) {
      return Promise.resolve();
    }
    setExecuteLoading(true);
    return accountAdminService
      .execute({
        ...state.command,
        previewToken: state.previewToken,
      })
      .then((result) => {
        showExecutionMessage(result);
        if (result.success && dataSourceId) {
          refreshUserTree();
        }
        return result;
      })
      .finally(() => {
        setExecuteLoading(false);
      });
  };

  const executeConfirmCommand = () => {
    return executePreview(confirmPreviewState).then((result) => {
      if (result?.success) {
        setExecuteModalOpen(false);
      }
    });
  };

  const submitPrivilegeCommand = () => {
    form.validateFields().then(() => {
      executePreview(previewState);
    });
  };

  const openAccountModal = (actionType: AccountActionType) => {
    setAccountActionType(actionType);
    setAccountModalOpen(true);
  };

  useEffect(() => {
    if (!accountModalOpen) {
      return;
    }
    accountForm.setFieldsValue({
      user: selectedAccount?.user,
      host: selectedAccount?.host,
      password: '',
    });
  }, [accountModalOpen, selectedAccount?.user, selectedAccount?.host]);

  useEffect(() => {
    if (!selectedAccount || selectedAccount.role || !roleOptions.length) {
      return;
    }
    roleForm.setFieldsValue({
      roleValue: roleOptions[0].value,
      withAdminOption: false,
      defaultRoleMode: 'SELECTED',
      roleValues: (selectedAccount.defaultRoles || []).map(roleValue),
    });
  }, [selectedAccount?.user, selectedAccount?.host, selectedAccount?.role, roleOptions]);

  const submitAccountCommand = () => {
    if (!accountActionType) {
      return Promise.resolve();
    }
    return accountForm.validateFields().then((values) => {
      resetConfirmState();
      return previewAndConfirm({
        dataSourceId: dataSourceId!,
        user: values.user,
        host: values.host,
        password: values.password,
        actionType: accountActionType,
      }).then(() => {
        setAccountModalOpen(false);
      });
    });
  };

  const handleSelectedAccountCommand = (actionType: AccountActionType) => {
    if (!dataSourceId || !selectedAccount) {
      return;
    }
    resetConfirmState();
    previewAndConfirm({
      dataSourceId,
      user: selectedAccount.user,
      host: selectedAccount.host,
      actionType,
    });
  };

  const submitRoleCommand = (actionType: AccountActionType) => {
    if (!dataSourceId || !selectedAccount) {
      return Promise.resolve();
    }
    return roleForm.validateFields().then((values) => {
      const selectedRole = parseRoleValue(values.roleValue);
      const command: AccountCommand = {
        dataSourceId,
        user: selectedAccount.user,
        host: selectedAccount.host,
        actionType,
        roleName: selectedRole?.user,
        roleHost: selectedRole?.host,
        withAdminOption: values.withAdminOption,
      };
      resetConfirmState();
      return previewAndConfirm(command);
    });
  };

  const submitDefaultRoleCommand = () => {
    if (!dataSourceId || !selectedAccount) {
      return Promise.resolve();
    }
    return roleForm.validateFields().then((values) => {
      const mode = values.defaultRoleMode || 'SELECTED';
      const command: AccountCommand = {
        dataSourceId,
        user: selectedAccount.user,
        host: selectedAccount.host,
        actionType: AccountActionType.SET_DEFAULT_ROLE,
        defaultRoleMode: mode,
        roleList: mode === 'SELECTED' ? (values.roleValues || []).map(parseRoleValue).filter(isAccount) : [],
      };
      resetConfirmState();
      return previewAndConfirm(command);
    });
  };

  if (!dataSourceId) {
    return (
      <>
        <Form form={form} style={{ display: 'none' }} />
        <Empty description={i18n('workspace.text.pleaseSelectDataSource')} />
      </>
    );
  }

  return (
    <div className={styles.container}>
      {capability && !capability.accountListReadable && (
        <Alert
          className={styles.alert}
          type="warning"
          showIcon
          message={i18n('workspace.databaseAccount.accountListUnreadable')}
        />
      )}
      <div className={styles.body}>
        {!selectedAccount ? (
          <>
            <Form form={form} style={{ display: 'none' }} />
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={i18n('workspace.databaseAccount.selectUserFromTree')}
            />
          </>
        ) : (
          <section className={styles.editorPane}>
            <Space className={styles.accountActions}>
              <Button
                disabled={!selectedAccount || selectedAccount.role}
                onClick={() => openAccountModal(AccountActionType.ALTER_PASSWORD)}
              >
                {i18n('workspace.databaseAccount.changePassword')}
              </Button>
              <Tooltip
                title={
                  accountLockSupported
                    ? i18n('workspace.databaseAccount.lockAccount')
                    : i18n('workspace.databaseAccount.lockUnsupported')
                }
              >
                <Button
                  disabled={!selectedAccount || selectedAccount.role || !accountLockSupported}
                  icon={<LockOutlined />}
                  onClick={() => handleSelectedAccountCommand(AccountActionType.LOCK_ACCOUNT)}
                />
              </Tooltip>
              <Tooltip
                title={
                  accountLockSupported
                    ? i18n('workspace.databaseAccount.unlockAccount')
                    : i18n('workspace.databaseAccount.lockUnsupported')
                }
              >
                <Button
                  disabled={!selectedAccount || selectedAccount.role || !accountLockSupported}
                  icon={<UnlockOutlined />}
                  onClick={() => handleSelectedAccountCommand(AccountActionType.UNLOCK_ACCOUNT)}
                />
              </Tooltip>
              <Tooltip
                title={
                  selectedAccount.role ? i18n('workspace.databaseAccount.deleteRole') : i18n('workspace.databaseAccount.deleteUser')
                }
              >
                <Button
                  danger
                  disabled={!selectedAccount}
                  icon={<DeleteOutlined />}
                  onClick={() =>
                    handleSelectedAccountCommand(selectedAccount.role ? AccountActionType.DROP_ROLE : AccountActionType.DROP_USER)
                  }
                />
              </Tooltip>
            </Space>
            <Form form={form} layout="vertical" className={styles.privilegeForm}>
              <Form.Item name="actionType" label={i18n('workspace.databaseAccount.operation')} rules={[{ required: true }]}>
                <Select
                  options={[
                    {
                      label: i18n('workspace.databaseAccount.grantPrivilege'),
                      value: AccountActionType.GRANT_PRIVILEGE,
                    },
                    {
                      label: i18n('workspace.databaseAccount.revokePrivilege'),
                      value: AccountActionType.REVOKE_PRIVILEGE,
                    },
                  ]}
                />
              </Form.Item>
              <Form.Item name="scope" label={i18n('workspace.databaseAccount.scope')} rules={[{ required: true }]}>
                <Select
                  options={[
                    { label: i18n('workspace.databaseAccount.scopeGlobal'), value: AccountPrivilegeScope.GLOBAL },
                    { label: i18n('workspace.databaseAccount.scopeDatabase'), value: AccountPrivilegeScope.DATABASE },
                    { label: i18n('workspace.databaseAccount.scopeTable'), value: AccountPrivilegeScope.TABLE },
                  ]}
                />
              </Form.Item>
              {watchedScope !== AccountPrivilegeScope.GLOBAL && (
                <Form.Item
                  name="databaseName"
                  label={i18n('workspace.databaseAccount.database')}
                  rules={[{ required: true }]}
                >
                  <Select
                    showSearch
                    loading={databaseLoading}
                    options={databaseOptions}
                    optionFilterProp="label"
                    placeholder={i18n('workspace.databaseAccount.selectDatabase')}
                  />
                </Form.Item>
              )}
              {watchedScope === AccountPrivilegeScope.TABLE && (
                <Form.Item name="tableName" label={i18n('workspace.databaseAccount.table')} rules={[{ required: true }]}>
                  <Select
                    showSearch
                    loading={tableLoading}
                    options={tableOptions}
                    optionFilterProp="label"
                    placeholder={i18n('workspace.databaseAccount.selectTable')}
                  />
                </Form.Item>
              )}
              <Form.Item name="privileges" label={i18n('workspace.databaseAccount.privileges')} rules={[{ required: true }]}>
                <Select
                  mode="multiple"
                  allowClear
                  showSearch
                  className={styles.privilegeSelect}
                  options={privilegeOptions}
                  optionFilterProp="label"
                  placeholder={i18n('workspace.databaseAccount.selectPrivileges')}
                />
              </Form.Item>
              {watchedActionType !== AccountActionType.REVOKE_PRIVILEGE && (
                <Form.Item name="grantOption" valuePropName="checked">
                  <Checkbox>{i18n('workspace.databaseAccount.grantOption')}</Checkbox>
                </Form.Item>
              )}
            </Form>
            {roleManagementSupported && !selectedAccount.role && (
              <Form form={roleForm} layout="vertical" className={styles.roleForm}>
                <Form.Item name="roleValue" label={i18n('workspace.databaseAccount.role')} rules={[{ required: true }]}>
                  <Select
                    showSearch
                    options={roleOptions}
                    optionFilterProp="label"
                    placeholder={i18n('workspace.databaseAccount.selectRole')}
                  />
                </Form.Item>
                <Form.Item name="withAdminOption" valuePropName="checked">
                  <Checkbox>{i18n('workspace.databaseAccount.withAdminOption')}</Checkbox>
                </Form.Item>
                <Space wrap>
                  <Button disabled={!roleOptions.length} onClick={() => submitRoleCommand(AccountActionType.GRANT_ROLE)}>
                    {i18n('workspace.databaseAccount.grantRole')}
                  </Button>
                  <Button disabled={!roleOptions.length} onClick={() => submitRoleCommand(AccountActionType.REVOKE_ROLE)}>
                    {i18n('workspace.databaseAccount.revokeRole')}
                  </Button>
                </Space>
                <Form.Item
                  name="defaultRoleMode"
                  label={i18n('workspace.databaseAccount.defaultRole')}
                  rules={[{ required: true }]}
                >
                  <Select
                    options={[
                      { label: i18n('workspace.databaseAccount.defaultRoleSelected'), value: 'SELECTED' },
                      { label: i18n('workspace.databaseAccount.defaultRoleAll'), value: 'ALL' },
                      { label: i18n('workspace.databaseAccount.defaultRoleNone'), value: 'NONE' },
                    ]}
                  />
                </Form.Item>
                <Form.Item name="roleValues" label={i18n('workspace.databaseAccount.defaultRoles')}>
                  <Select mode="multiple" allowClear showSearch options={roleOptions} optionFilterProp="label" />
                </Form.Item>
                <Button disabled={!roleOptions.length} onClick={submitDefaultRoleCommand}>
                  {i18n('workspace.databaseAccount.setDefaultRole')}
                </Button>
              </Form>
            )}
            <section className={styles.previewSection}>
              <div className={styles.previewHeader}>
                <span>{i18n('workspace.databaseAccount.previewSql')}</span>
                {previewLoading && <Spin size="small" />}
              </div>
              {previewState?.sql ? (
                <SqlPreview sql={previewState.sql} />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={i18n('workspace.databaseAccount.previewEmpty')} />
              )}
            </section>
            <ConfigProvider wave={{ disabled: true }}>
              <Button
                block
                type="primary"
                className={styles.submitButton}
                style={submitButtonStyle}
                loading={executeLoading}
                disabled={!selectedAccount || !previewState}
                onClick={submitPrivilegeCommand}
              >
                {i18n('workspace.databaseAccount.confirmChange')}
              </Button>
            </ConfigProvider>
          </section>
        )}
      </div>
      <Modal
        title={
          <Space>
            <KeyOutlined />
            <span>
              {accountActionType ? accountActionTitle(accountActionType) : i18n('workspace.databaseAccount.userOperation')}
            </span>
          </Space>
        }
        open={accountModalOpen}
        destroyOnClose
        onOk={submitAccountCommand}
        onCancel={() => setAccountModalOpen(false)}
      >
        <Form form={accountForm} layout="vertical" className={styles.accountForm}>
          <Form.Item name="user" label={i18n('workspace.databaseAccount.user')} rules={[{ required: true }]}>
            <Input disabled />
          </Form.Item>
          <Form.Item name="host" label={i18n('workspace.databaseAccount.host')} rules={[{ required: true }]}>
            <Input disabled />
          </Form.Item>
          {accountActionType === AccountActionType.ALTER_PASSWORD && (
            <Form.Item name="password" label={i18n('workspace.databaseAccount.password')} rules={[{ required: true }]}>
              <Input.Password />
            </Form.Item>
          )}
        </Form>
      </Modal>
      <Modal
        title={i18n('workspace.databaseAccount.executeSql')}
        width={720}
        open={executeModalOpen}
        confirmLoading={executeLoading}
        maskClosable={false}
        onOk={executeConfirmCommand}
        onCancel={() => setExecuteModalOpen(false)}
      >
        <SqlPreview sql={confirmPreviewState?.sql || ''} />
      </Modal>
    </div>
  );
});

function SqlPreview({ sql }: { sql: string }) {
  return (
    <SQLPreview className={styles.sqlPreview} sql={sql} source="account-privilege-panel" foldable={false} />
  );
}

function showExecutionMessage(result: AccountExecute) {
  const content = formatAccountExecuteMessage(result);
  if (!result.success) {
    staticMessage.error(content);
    return;
  }
  staticMessage.success(content);
}

function isPreviewReady(command: AccountCommand | null): command is AccountCommand {
  if (!command?.actionType || !command.user || !command.host || !command.scope || !command.privileges?.length) {
    return false;
  }
  if (command.scope !== AccountPrivilegeScope.GLOBAL && !command.databaseName) {
    return false;
  }
  if (command.scope === AccountPrivilegeScope.TABLE && !command.tableName) {
    return false;
  }
  return true;
}

function accountActionTitle(actionType: AccountActionType) {
  switch (actionType) {
    case AccountActionType.ALTER_PASSWORD:
      return i18n('workspace.databaseAccount.changePassword');
    case AccountActionType.LOCK_ACCOUNT:
      return i18n('workspace.databaseAccount.lockAccount');
    case AccountActionType.UNLOCK_ACCOUNT:
      return i18n('workspace.databaseAccount.unlockAccount');
    case AccountActionType.DROP_USER:
      return i18n('workspace.databaseAccount.deleteUser');
    case AccountActionType.DROP_ROLE:
      return i18n('workspace.databaseAccount.deleteRole');
    default:
      return i18n('workspace.databaseAccount.userOperation');
  }
}

function roleValue(account: Account) {
  return JSON.stringify({ user: account.user, host: account.host, displayName: account.displayName, role: true });
}

function parseRoleValue(value?: string): Account | null {
  if (!value) {
    return null;
  }
  try {
    return JSON.parse(value) as Account;
  } catch {
    return null;
  }
}

function isAccount(account: Account | null): account is Account {
  return !!account;
}

export default AccountPrivilegePanel;
