import { useCallback, useEffect, useState } from 'react';
import { Button, Input, InputNumber, Modal, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import i18n from '@/i18n';
import sqlService, { IPartitionItem } from '@/service/sql';
import {
  defaultPartitionDefinition,
  defaultReorganizePartitionDefinitions,
  executePartitionPreviewSql,
  getPartitionOperationAvailability,
  isPartitionDropConfirmationValid,
  normalizePartitionMethod,
} from './partitionOperations';

/**
 * Table partition inspection and maintenance (MYSQL-OBJ-009). Operations are limited per
 * partition type: ADD for RANGE/LIST/HASH/KEY, REORGANIZE/DROP/TRUNCATE for RANGE/LIST,
 * COALESCE for HASH/KEY, and ANALYZE/CHECK/OPTIMIZE for all.
 */
const PartitionsContent = ({
  dataSourceId,
  databaseName,
  schemaName,
  tableName,
}: {
  dataSourceId: number;
  databaseName: string;
  schemaName?: string | null;
  tableName: string;
}) => {
  const [data, setData] = useState<IPartitionItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [coalesceCount, setCoalesceCount] = useState<number>(1);
  const [hashAddCount, setHashAddCount] = useState<number>(1);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    sqlService
      .getPartitionList({ dataSourceId, databaseName, schemaName, tableName })
      .then((list) => setData(list || []))
      .catch((e) => {
        setData([]);
        setError(e?.message || i18n('common.text.failure'));
      })
      .finally(() => setLoading(false));
  }, [dataSourceId, databaseName, schemaName, tableName]);

  useEffect(() => {
    load();
  }, [load]);

  const showInputError = () => {
    Modal.error({ title: i18n('workspace.ops.partitionFailed'), content: i18n('workspace.ops.partitionInputRequired') });
  };

  const confirmAndExecute = (title: string, sql: Promise<string>, destructive: boolean) => {
    sql.then((preview) => {
      Modal.confirm({
        title,
        content: (
          <div>
            {destructive && <div style={{ color: 'var(--text-color-danger)', marginBottom: 8 }}>{i18n('workspace.ops.partitionDestructiveHint')}</div>}
            <div style={{ marginBottom: 8 }}>{i18n('workspace.ops.partitionOperationRiskHint')}</div>
            <pre style={{ whiteSpace: 'pre-wrap' }}>{preview}</pre>
          </div>
        ),
        okText: i18n('common.button.execute'),
        cancelText: i18n('common.button.cancel'),
        onOk: () =>
          executePartitionPreviewSql({
            context: { dataSourceId, databaseName, schemaName, tableName },
            sql: preview,
            executeDDL: sqlService.executeDDL,
            refresh: load,
          })
            .catch((e) => Modal.error({ title: i18n('workspace.ops.partitionFailed'), content: e?.message })),
      });
    }).catch((e) => Modal.error({ title: i18n('workspace.ops.partitionFailed'), content: e?.message }));
  };

  const baseRequest = { dataSourceId, databaseName, schemaName, tableName };
  const method = normalizePartitionMethod(data[0]?.method);
  const availability = getPartitionOperationAvailability(method);

  const openAddPartitionModal = () => {
    if (!availability.add) {
      return;
    }
    if (availability.coalesce) {
      confirmAndExecute(
        i18n('workspace.ops.partitionAdd'),
        sqlService.getPartitionAddSql({ ...baseRequest, count: hashAddCount }),
        false,
      );
      return;
    }

    let partitionName = '';
    let partitionDefinition = defaultPartitionDefinition(method);
    Modal.confirm({
      title: i18n('workspace.ops.partitionAdd'),
      content: (
        <div>
          <Input
            placeholder={i18n('workspace.ops.partitionNewName')}
            onChange={(event) => {
              partitionName = event.target.value;
            }}
          />
          <Input.TextArea
            style={{ marginTop: 8 }}
            rows={3}
            defaultValue={partitionDefinition}
            placeholder={i18n('workspace.ops.partitionDefinitionPlaceholder')}
            onChange={(event) => {
              partitionDefinition = event.target.value;
            }}
          />
        </div>
      ),
      okText: i18n('common.button.next'),
      cancelText: i18n('common.button.cancel'),
      onOk: () => {
        if (!partitionName.trim() || !partitionDefinition.trim()) {
          showInputError();
          return Promise.reject();
        }
        confirmAndExecute(
          i18n('workspace.ops.partitionAdd'),
          sqlService.getPartitionAddSql({ ...baseRequest, partitionName, partitionDefinition }),
          false,
        );
      },
    });
  };

  const openDropPartitionModal = (partitionName: string) => {
    let confirmedName = '';
    Modal.confirm({
      title: i18n('workspace.ops.partitionDrop'),
      content: (
        <div>
          <div style={{ color: 'var(--text-color-danger)', marginBottom: 8 }}>{i18n('workspace.ops.partitionDestructiveHint')}</div>
          <Input
            placeholder={i18n('workspace.ops.partitionConfirmName', partitionName)}
            onChange={(event) => {
              confirmedName = event.target.value;
            }}
          />
        </div>
      ),
      okText: i18n('common.button.next'),
      cancelText: i18n('common.button.cancel'),
      onOk: () => {
        if (!isPartitionDropConfirmationValid(partitionName, confirmedName)) {
          showInputError();
          return Promise.reject();
        }
        confirmAndExecute(
          i18n('workspace.ops.partitionDrop'),
          sqlService.getPartitionDropSql({ ...baseRequest, partitionName }),
          true,
        );
      },
    });
  };

  const openReorganizePartitionModal = (partitionName: string) => {
    if (!availability.reorganize) {
      return;
    }
    let partitionDefinitions = defaultReorganizePartitionDefinitions(method);
    Modal.confirm({
      title: i18n('workspace.ops.partitionReorganize'),
      content: (
        <Input.TextArea
          rows={4}
          defaultValue={partitionDefinitions}
          placeholder={i18n('workspace.ops.partitionDefinitionsPlaceholder')}
          onChange={(event) => {
            partitionDefinitions = event.target.value;
          }}
        />
      ),
      okText: i18n('common.button.next'),
      cancelText: i18n('common.button.cancel'),
      onOk: () => {
        if (!partitionDefinitions.trim()) {
          showInputError();
          return Promise.reject();
        }
        confirmAndExecute(
          i18n('workspace.ops.partitionReorganize'),
          sqlService.getPartitionReorganizeSql({ ...baseRequest, partitionName, partitionDefinitions }),
          false,
        );
      },
    });
  };

  const columns: ColumnsType<IPartitionItem> = [
    { title: i18n('workspace.ops.partitionName'), dataIndex: 'partitionName', width: 150 },
    { title: i18n('workspace.ops.partitionMethod'), dataIndex: 'method', width: 140 },
    { title: i18n('workspace.ops.partitionExpression'), dataIndex: 'expression', width: 180 },
    { title: i18n('workspace.ops.partitionBoundary'), dataIndex: 'description', width: 160 },
    { title: i18n('workspace.ops.partitionRows'), dataIndex: 'tableRows', width: 90 },
    {
      title: i18n('workspace.ops.partitionSize'),
      width: 100,
      render: (_, r) => {
        const bytes = (r.dataLength ?? 0) + (r.indexLength ?? 0);
        return bytes > 0 ? `${(bytes / 1024 / 1024).toFixed(2)} MB` : '-';
      },
    },
    {
      title: i18n('workspace.ops.partitionAction'),
      width: 260,
      render: (_, r) => {
        const rowAvailability = getPartitionOperationAvailability(r.method);
        return (
          <>
            {rowAvailability.drop && r.partitionName && (
              <>
                <Button
                  size="small"
                  danger
                  style={{ marginRight: 4 }}
                  onClick={() => openDropPartitionModal(r.partitionName!)}
                >
                  {i18n('workspace.ops.partitionDrop')}
                </Button>
              </>
            )}
            {rowAvailability.truncate && r.partitionName && (
              <>
                <Button
                  size="small"
                  danger
                  style={{ marginRight: 4 }}
                  onClick={() =>
                    confirmAndExecute(
                      i18n('workspace.ops.partitionTruncate'),
                      sqlService.getPartitionTruncateSql({ ...baseRequest, partitionName: r.partitionName! }),
                      true,
                    )
                  }
                >
                  {i18n('workspace.ops.partitionTruncate')}
                </Button>
              </>
            )}
            {rowAvailability.reorganize && r.partitionName && (
              <Button
                size="small"
                style={{ marginRight: 4 }}
                onClick={() => openReorganizePartitionModal(r.partitionName!)}
              >
                {i18n('workspace.ops.partitionReorganize')}
              </Button>
            )}
            {rowAvailability.maintain && (
              <>
                <Button
                  size="small"
                  style={{ marginRight: 4 }}
                  onClick={() =>
                    confirmAndExecute(
                      i18n('workspace.ops.partitionAnalyze'),
                      sqlService.getPartitionMaintainSql({ ...baseRequest, operation: 'ANALYZE', partitionName: r.partitionName! }),
                      false,
                    )
                  }
                >
                  {i18n('workspace.ops.partitionAnalyze')}
                </Button>
                <Button
                  size="small"
                  style={{ marginRight: 4 }}
                  onClick={() =>
                    confirmAndExecute(
                      i18n('workspace.ops.partitionCheck'),
                      sqlService.getPartitionMaintainSql({ ...baseRequest, operation: 'CHECK', partitionName: r.partitionName! }),
                      false,
                    )
                  }
                >
                  {i18n('workspace.ops.partitionCheck')}
                </Button>
                <Button
                  size="small"
                  onClick={() =>
                    confirmAndExecute(
                      i18n('workspace.ops.partitionOptimize'),
                      sqlService.getPartitionMaintainSql({ ...baseRequest, operation: 'OPTIMIZE', partitionName: r.partitionName! }),
                      false,
                    )
                  }
                >
                  {i18n('workspace.ops.partitionOptimize')}
                </Button>
              </>
            )}
          </>
        );
      },
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
        <span>
          {data.length ? i18n('workspace.ops.partitionMethodHint', method) : ''}
          {!data.length && !error ? i18n('workspace.ops.partitionEmpty') : ''}
        </span>
        {availability.add && (
          <>
            {availability.coalesce && (
              <InputNumber
                min={1}
                value={hashAddCount}
                onChange={(v) => setHashAddCount(v ?? 1)}
                style={{ width: 80 }}
              />
            )}
            <Button size="small" onClick={openAddPartitionModal}>
              {i18n('workspace.ops.partitionAdd')}
            </Button>
          </>
        )}
        {availability.coalesce && (
          <>
            <InputNumber
              min={1}
              value={coalesceCount}
              onChange={(v) => setCoalesceCount(v ?? 1)}
              style={{ width: 80 }}
            />
            <Button
              size="small"
              onClick={() =>
                confirmAndExecute(
                  i18n('workspace.ops.partitionCoalesce'),
                  sqlService.getPartitionCoalesceSql({ ...baseRequest, count: coalesceCount }),
                  false,
                )
              }
            >
              {i18n('workspace.ops.partitionCoalesce')}
            </Button>
          </>
        )}
        <Button size="small" onClick={load} loading={loading}>
          {i18n('common.button.refresh')}
        </Button>
        {error && <span style={{ color: 'var(--text-color-danger)' }}>{error}</span>}
      </div>
      <Table
        size="small"
        rowKey={(r) => `${r.partitionName ?? ''}-${r.subpartitionName ?? ''}`}
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={false}
        scroll={{ x: 1100, y: 380 }}
      />
    </div>
  );
};

export default PartitionsContent;
