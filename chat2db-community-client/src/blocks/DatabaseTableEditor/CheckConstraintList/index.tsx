import { forwardRef, useContext, useEffect, useImperativeHandle, useState } from 'react';
import { Button, Checkbox, Input, Table } from 'antd';
import { Context } from '..';
import { EditColumnOperationType } from '@/constants';
import { ICheckConstraintItem } from '@/typings';
import {
  CheckConstraintField,
  createCheckConstraintDraft,
  markCheckConstraintDeleted,
  markCheckConstraintUpdated,
  prepareCheckConstraintsForSubmit,
  visibleCheckConstraints,
} from './checkConstraintList';

export interface ICheckConstraintListRef {
  getCheckConstraintListInfo: () => ICheckConstraintItem[];
}

const CheckConstraintList = forwardRef<ICheckConstraintListRef>((_, ref) => {
  const { tableDetails, databaseBaseInfo } = useContext(Context);
  const [constraints, setConstraints] = useState<ICheckConstraintItem[]>([]);

  useEffect(() => {
    setConstraints(
      (tableDetails.checkConstraintList || []).map((item, index) => ({
        ...item,
        key: item.key || `${item.name || 'check'}-${index}`,
        enforced: item.enforced !== false,
      })),
    );
  }, [tableDetails]);
  useImperativeHandle(ref, () => ({
    getCheckConstraintListInfo: () =>
      prepareCheckConstraintsForSubmit(constraints, {
        databaseName: databaseBaseInfo.databaseName,
        schemaName: databaseBaseInfo.schemaName,
        tableName: tableDetails.name || databaseBaseInfo.tableName,
      }),
  }));

  const update = (record: ICheckConstraintItem, field: CheckConstraintField, value: string | boolean) => {
    setConstraints((current) =>
      current.map((item) =>
        item.key === record.key ? markCheckConstraintUpdated(item, field, value) : item,
      ),
    );
  };

  return <>
    <Button onClick={() => setConstraints((current) => [...current, createCheckConstraintDraft(`check-${Date.now()}`)])}
    >Add constraint</Button>
    <Table rowKey={(item) => item.key || item.name} pagination={false} dataSource={visibleCheckConstraints(constraints)} columns={[
      { title: 'Name', dataIndex: 'name', render: (value, record) => <Input value={value} disabled={record.editStatus !== EditColumnOperationType.Add} onChange={(event) => update(record, 'name', event.target.value)} /> },
      { title: 'Expression', dataIndex: 'expression', render: (value, record) => <Input value={value} onChange={(event) => update(record, 'expression', event.target.value)} /> },
      { title: 'Enforced', dataIndex: 'enforced', render: (value, record) => <Checkbox checked={value !== false} onChange={(event) => update(record, 'enforced', event.target.checked)} /> },
      { title: '', render: (value, item) => <Button danger onClick={() => setConstraints((current) => current
        .map((entry) => entry.key === item.key ? markCheckConstraintDeleted(entry) : entry)
        .filter((entry): entry is ICheckConstraintItem => Boolean(entry)))}
                                                   >Delete</Button> },
    ]}
    />
  </>;
});

export default CheckConstraintList;
