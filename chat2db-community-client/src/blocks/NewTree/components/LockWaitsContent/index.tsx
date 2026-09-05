import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Table, Tabs, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { ILockView } from '@/service/sql';
import { beginLatestRequest, invalidateLatestRequest, isLatestRequest } from '@/utils/latestRequest';

/**
 * Lock waits and blocking chains (MYSQL-OPS-003). Read-only; InnoDB data locks come from
 * performance_schema (8.0) or information_schema (5.7), metadata locks are shown only
 * when instrumented. The feature never terminates sessions — termination belongs to the
 * session flow (MYSQL-OPS-001).
 */
interface LockWaitsContentProps {
  dataSourceId: number;
  onOpenSession?: (target: LockSessionNavigationTarget) => void;
}

type LockSnapshotRow = Record<string, string | null | boolean | number | undefined>;
type WaitChainRow = ILockView['waitChains'][number];

export interface LockSessionNavigationTarget {
  dataSourceId: number;
  sessionId: string;
  engineThreadId?: string | null;
  databaseName?: string | null;
  user?: string | null;
  host?: string | null;
  query?: string | null;
}

const sourceLabel = (source: ILockView['source']) => {
  if (source === 'performance_schema') {
    return 'Performance Schema (8.0)';
  }
  if (source === 'information_schema') {
    return 'information_schema (5.7)';
  }
  return i18n('workspace.ops.lockSourceUnavailable');
};

const valueText = (value: unknown) => (value == null || value === '' ? i18n('workspace.ops.valueUnavailable') : String(value));

const lockErrorText = (code: NonNullable<ILockView['errors']>[number]['code'], fallback?: string) => {
  if (code === 'privilege_required') {
    return i18n('workspace.ops.lockPrivilegeRequired');
  }
  if (code === 'unavailable') {
    return i18n('workspace.ops.lockMetadataUnavailable');
  }
  return fallback || code;
};

const formatLockErrors = (errors?: ILockView['errors']) => {
  if (!errors?.length) {
    return null;
  }
  return errors.map((item) => `${item.section}: ${lockErrorText(item.code, item.message)}`).join('; ');
};

const firstRowValue = (row: LockSnapshotRow, ...keys: string[]) => {
  for (const key of keys) {
    const value = row[key];
    if (value != null && value !== '') {
      return String(value);
    }
  }
  return null;
};

const LockWaitsContent = ({ dataSourceId, onOpenSession }: LockWaitsContentProps) => {
  const [view, setView] = useState<ILockView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const requestGenerationRef = useRef(0);

  const load = useCallback(() => {
    const requestGeneration = beginLatestRequest(requestGenerationRef);
    setLoading(true);
    setError(null);
    setView((current) => (current?.dataSourceId === dataSourceId ? current : null));
    sqlService
      .getLockView({ dataSourceId })
      .then((result) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        setView(result);
        setError(formatLockErrors(result.errors));
      })
      .catch((e) => {
        if (!isLatestRequest(requestGenerationRef, requestGeneration)) return;
        setView(null);
        setError(e?.message || i18n('common.text.failure'));
      })
      .finally(() => {
        if (isLatestRequest(requestGenerationRef, requestGeneration)) setLoading(false);
      });
  }, [dataSourceId]);

  useEffect(() => {
    load();
    return () => invalidateLatestRequest(requestGenerationRef);
  }, [load]);

  const renderSessionThread = (
    threadId: string | null,
    sessionAvailable: boolean,
    engineThreadId: string | null,
    metadataLockCount: number,
    sessionContext: Omit<LockSessionNavigationTarget, 'dataSourceId' | 'sessionId'>,
  ) => {
    const canOpenSession = Boolean(onOpenSession && sessionAvailable && threadId);
    return (
      <span>
        {canOpenSession ? (
          <Button
            type="link"
            size="small"
            onClick={() =>
              onOpenSession?.({
                ...sessionContext,
                dataSourceId: view?.dataSourceId ?? dataSourceId,
                sessionId: threadId!,
              })
            }
            aria-label={i18n('workspace.ops.lockOpenSession', threadId)}
          >
            {valueText(threadId)}
          </Button>
        ) : (
          valueText(threadId)
        )}
        {!sessionAvailable && <Tag>{i18n('workspace.ops.sessionStale')}</Tag>}
        {engineThreadId && engineThreadId !== threadId && <Tag>{i18n('workspace.ops.engineThread', engineThreadId)}</Tag>}
        {metadataLockCount > 0 && <Tag color="blue">{i18n('workspace.ops.metadataLockCount', metadataLockCount)}</Tag>}
      </span>
    );
  };

  const chainColumns: ColumnsType<WaitChainRow> = [
    { title: i18n('workspace.ops.datasourceId'), dataIndex: 'dataSourceId', width: 110 },
    { title: i18n('workspace.ops.lockType'), dataIndex: 'lockKind', width: 100, render: valueText },
    { title: i18n('workspace.ops.lockObject'), dataIndex: 'lockObject', width: 220, render: valueText },
    {
      title: i18n('workspace.ops.waiterThread'),
      dataIndex: 'waiterThreadId',
      width: 180,
      render: (_, record) =>
        renderSessionThread(
          record.waiterThreadId,
          record.waiterSessionAvailable,
          record.waiterEngineThreadId,
          record.waiterMetadataLockCount,
          {
            engineThreadId: record.waiterEngineThreadId,
            databaseName: record.waiterDatabase,
            user: record.waiterUser,
            host: record.waiterHost,
            query: record.waiterQuery,
          },
        ),
    },
    { title: i18n('workspace.ops.waiterUser'), dataIndex: 'waiterUser', width: 120 },
    { title: i18n('workspace.ops.waiterState'), dataIndex: 'waiterState', width: 100 },
    { title: i18n('workspace.ops.waiterLockMode'), dataIndex: 'waiterLockMode', width: 120, render: valueText },
    { title: i18n('workspace.ops.waiterQuery'), dataIndex: 'waiterQuery', ellipsis: true },
    {
      title: i18n('workspace.ops.blockerThread'),
      dataIndex: 'blockerThreadId',
      width: 180,
      render: (_, record) =>
        renderSessionThread(
          record.blockerThreadId,
          record.blockerSessionAvailable,
          record.blockerEngineThreadId,
          record.blockerMetadataLockCount,
          {
            engineThreadId: record.blockerEngineThreadId,
            databaseName: record.blockerDatabase,
            user: record.blockerUser,
            host: record.blockerHost,
            query: record.blockerQuery,
          },
        ),
    },
    { title: i18n('workspace.ops.blockerUser'), dataIndex: 'blockerUser', width: 120 },
    { title: i18n('workspace.ops.blockerState'), dataIndex: 'blockerState', width: 100 },
    { title: i18n('workspace.ops.blockerLockMode'), dataIndex: 'blockerLockMode', width: 120, render: valueText },
    { title: i18n('workspace.ops.blockerQuery'), dataIndex: 'blockerQuery', ellipsis: true },
    {
      title: i18n('workspace.ops.role'),
      width: 130,
      render: (_, record) =>
        record.cycle ? (
          <Tag color="orange">{i18n('workspace.ops.cycle')}</Tag>
        ) : record.rootBlocker ? (
          <Tag color="red">{i18n('workspace.ops.rootBlocker')}</Tag>
        ) : (
          <Tag>{i18n('workspace.ops.lockBlocking')}</Tag>
        ),
    },
  ];

  const lockColumns: ColumnsType<LockSnapshotRow> = [
    { title: i18n('workspace.ops.lockId'), dataIndex: 'ENGINE_LOCK_ID', width: 200, render: (v, r) => v ?? r.lock_id },
    { title: i18n('workspace.ops.lockObject'), dataIndex: 'OBJECT_SCHEMA', width: 220, render: (v, r) => (v != null ? `${v}.${r.OBJECT_NAME}` : r.lock_table) },
    { title: i18n('workspace.ops.lockType'), dataIndex: 'LOCK_TYPE', width: 110, render: (v, r) => v ?? r.lock_type },
    { title: i18n('workspace.ops.lockMode'), dataIndex: 'LOCK_MODE', width: 110, render: (v, r) => v ?? r.lock_mode },
    { title: i18n('workspace.ops.lockStatus'), dataIndex: 'LOCK_STATUS', width: 110 },
    { title: i18n('workspace.ops.lockData'), dataIndex: 'LOCK_DATA', ellipsis: true, render: (v, r) => v ?? r.lock_data },
  ];

  const sessionColumns: ColumnsType<LockSnapshotRow> = [
    {
      title: i18n('workspace.ops.sessionId'),
      dataIndex: 'PROCESSLIST_ID',
      width: 100,
      render: (_v, r) => {
        const sessionId = firstRowValue(r, 'PROCESSLIST_ID', 'trx_mysql_thread_id');
        const engineThreadId = firstRowValue(r, 'THREAD_ID');
        if (!onOpenSession || !sessionId) {
          return valueText(sessionId ?? engineThreadId);
        }
        return (
          <Button
            type="link"
            size="small"
            onClick={() =>
              onOpenSession({
                dataSourceId: view?.dataSourceId ?? dataSourceId,
                sessionId,
                engineThreadId,
                databaseName: firstRowValue(r, 'PROCESSLIST_DB', 'DB'),
                user: firstRowValue(r, 'PROCESSLIST_USER', 'USER'),
                host: firstRowValue(r, 'PROCESSLIST_HOST', 'HOST'),
                query: firstRowValue(r, 'trx_query', 'PROCESSLIST_INFO'),
              })
            }
            aria-label={i18n('workspace.ops.lockOpenSession', sessionId)}
          >
            {sessionId}
          </Button>
        );
      },
    },
    { title: i18n('workspace.ops.engineThreadId'), dataIndex: 'THREAD_ID', width: 110 },
    { title: i18n('workspace.ops.user'), dataIndex: 'PROCESSLIST_USER', width: 120, render: (v, r) => v ?? r.USER },
    { title: i18n('workspace.ops.host'), dataIndex: 'PROCESSLIST_HOST', width: 160, render: (v, r) => v ?? r.HOST },
    { title: i18n('workspace.ops.database'), dataIndex: 'PROCESSLIST_DB', width: 120, render: (v, r) => v ?? r.DB },
    { title: i18n('workspace.ops.state'), dataIndex: 'trx_state', width: 120, render: (v, r) => v ?? r.PROCESSLIST_STATE },
    { title: i18n('workspace.ops.query'), dataIndex: 'trx_query', ellipsis: true, render: (v, r) => v ?? r.PROCESSLIST_INFO },
  ];

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
        <span>
          {view
            ? `${i18n('workspace.ops.datasourceId')}: ${view.dataSourceId ?? dataSourceId} · ${i18n('workspace.ops.lockSource', sourceLabel(view.source))}`
            : ''}
        </span>
        <Button size="small" onClick={load} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
        {error && <span style={{ color: 'var(--text-color-danger)' }}>{error}</span>}
      </div>
      {view && (
        <>
          <Alert
            showIcon
            type="info"
            style={{ marginBottom: 8 }}
            message={i18n('workspace.ops.lockSnapshotNotice')}
          />
          <Tabs
            items={[
            {
              key: 'chains',
              label: i18n('workspace.ops.blockingChains'),
              children: (
                <Table
                  size="small"
                  rowKey={(r) =>
                    [
                      dataSourceId,
                      r.waiterTransactionId ?? 'no-waiter-trx',
                      r.blockerTransactionId ?? 'no-blocker-trx',
                      r.waiterLockId ?? 'no-waiter-lock',
                      r.blockerLockId ?? 'no-blocker-lock',
                      r.waiterThreadId ?? 'no-waiter-thread',
                      r.blockerThreadId ?? 'no-blocker-thread',
                    ].join(':')
                  }
                  columns={chainColumns}
                  dataSource={view.waitChains}
                  loading={loading}
                  pagination={false}
                  scroll={{ x: 1400, y: 300 }}
                  locale={{ emptyText: i18n('workspace.ops.noLockWaits') }}
                />
              ),
            },
            {
              key: 'metadataChains',
              label: i18n('workspace.ops.metadataBlockingChains', view.metadataWaitChains?.length || 0),
              children: (
                <Table
                  size="small"
                  rowKey={(r) =>
                    [
                      dataSourceId,
                      r.lockObject ?? 'no-object',
                      r.waiterEngineThreadId ?? 'no-waiter-thread',
                      r.blockerEngineThreadId ?? 'no-blocker-thread',
                    ].join(':')
                  }
                  columns={chainColumns}
                  dataSource={view.metadataWaitChains || []}
                  loading={loading}
                  pagination={false}
                  scroll={{ x: 1700, y: 300 }}
                  locale={{ emptyText: i18n('workspace.ops.noLockWaits') }}
                />
              ),
            },
            {
              key: 'dataLocks',
              label: i18n('workspace.ops.innodbDataLocks', view.dataLocks.length),
              children: (
                <Table
                  size="small"
                  rowKey={(r, index) => String(r.ENGINE_LOCK_ID ?? r.lock_id ?? `lock-${index}`)}
                  columns={lockColumns}
                  dataSource={view.dataLocks}
                  loading={loading}
                  pagination={false}
                  scroll={{ x: 1200, y: 300 }}
                />
              ),
            },
            {
              key: 'metaLocks',
              label: i18n('workspace.ops.metadataLocks', view.metaLocks.length),
              children: (
                <Table
                  size="small"
                  rowKey={(r) =>
                    [
                      r.OBJECT_SCHEMA,
                      r.OBJECT_NAME,
                      r.LOCK_TYPE,
                      r.OWNER_THREAD_ID,
                      r.OWNER_EVENT_ID,
                    ].join('.')
                  }
                  columns={[
                    {
                      title: i18n('workspace.ops.lockObject'),
                      dataIndex: 'OBJECT_SCHEMA',
                      width: 220,
                      render: (v, r) => `${v}.${r.OBJECT_NAME}`,
                    },
                    { title: i18n('workspace.ops.lockType'), dataIndex: 'LOCK_TYPE', width: 120 },
                    { title: i18n('workspace.ops.lockDuration'), dataIndex: 'LOCK_DURATION', width: 120 },
                    { title: i18n('workspace.ops.lockStatus'), dataIndex: 'LOCK_STATUS', width: 100, render: valueText },
                    { title: i18n('workspace.ops.ownerThread'), dataIndex: 'OWNER_THREAD_ID', width: 120 },
                    { title: i18n('workspace.ops.sessionId'), dataIndex: 'OWNER_PROCESSLIST_ID', width: 100, render: valueText },
                    { title: i18n('workspace.ops.user'), dataIndex: 'OWNER_USER', width: 120, render: valueText },
                    { title: i18n('workspace.ops.state'), dataIndex: 'OWNER_STATE', width: 120, render: valueText },
                  ]}
                  dataSource={view.metaLocks}
                  loading={loading}
                  pagination={false}
                  scroll={{ x: 800, y: 300 }}
                  locale={{ emptyText: i18n('workspace.ops.metadataLocksUnavailable') }}
                />
              ),
            },
            {
              key: 'sessions',
              label: i18n('workspace.ops.sessions', view.sessions?.length || 0),
              children: (
                <Table
                  size="small"
                  rowKey={(r, index) =>
                    `${String(r.PROCESSLIST_ID ?? r.trx_mysql_thread_id ?? r.THREAD_ID ?? 'session')}-${index}`
                  }
                  columns={sessionColumns}
                  dataSource={view.sessions || []}
                  loading={loading}
                  pagination={false}
                  scroll={{ x: 1000, y: 300 }}
                  locale={{ emptyText: i18n('workspace.ops.sessionsUnavailable') }}
                />
              ),
            },
            ]}
          />
        </>
      )}
    </div>
  );
};

export default LockWaitsContent;
