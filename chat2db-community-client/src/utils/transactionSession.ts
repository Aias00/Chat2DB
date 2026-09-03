import { staticMessage, staticModal } from '@chat2db/ui';
import { Button } from 'antd';
import { createElement, Fragment } from 'react';
import i18n from '@/i18n';
import transactionServer from '@/service/transaction';
import { useWorkspaceStore } from '@/store/workspace';
import type { IWorkspaceTab } from '@/typings';
import { releaseTransactionConsoles, type CloseAction, type TxConsole } from './transactionSessionCore';

/**
 * For each console being closed that has an open (uncommitted) transaction, prompt the user
 * to Commit, Roll back, or cancel closing. Mirrors confirmAndKillTerminalTabs: returns false
 * to abort the close, true to proceed. When the user commits or rolls back, the bound
 * connection is released on the server and the console's transaction state is cleared.
 */
export async function confirmAndReleaseTransaction(tabs: IWorkspaceTab[]): Promise<boolean> {
  const store = useWorkspaceStore.getState();
  const txConsoles: TxConsole[] = [];
  for (const tab of tabs) {
    const consoleId = tab.uniqueData?.consoleId;
    if (typeof consoleId !== 'number') {
      continue;
    }
    const state = store.getTransactionState(consoleId);
    if (state?.inTransaction) {
      txConsoles.push({
        consoleId,
        dataSourceId: tab.uniqueData?.dataSourceId as number,
        databaseName: tab.uniqueData?.databaseName,
        schemaName: tab.uniqueData?.schemaName,
      });
    }
  }
  if (!txConsoles.length) {
    return true;
  }
  return confirmTransactionClose(txConsoles);
}

function confirmTransactionClose(consoles: TxConsole[]): Promise<boolean> {
  return new Promise((resolve) => {
    let resolved = false;
    let settling = false;
    const modalRef: { current?: { destroy: () => void } } = {};
    const finish = (value: boolean) => {
      if (resolved) {
        return;
      }
      resolved = true;
      modalRef.current?.destroy();
      resolve(value);
    };

    const releaseAll = async (action: CloseAction) => {
      if (settling) {
        return;
      }
      settling = true;
      const success = await releaseTransactionConsoles(consoles, action, {
        store: useWorkspaceStore.getState(),
        commitTransaction: transactionServer.commitTransaction,
        releaseTransaction: transactionServer.releaseTransaction,
        onUnknownOutcome: () => staticMessage.warning(i18n('workspace.transaction.outcomeUnknown')),
      });
      if (!success) {
        staticMessage.error(i18n('workspace.transaction.releaseFailed'));
        finish(false);
        return;
      }
      finish(true);
    };

    modalRef.current = staticModal.confirm({
      title: i18n('workspace.transaction.closeTitle'),
      content: i18n('workspace.transaction.closeContent'),
      closable: false,
      okButtonProps: { style: { display: 'none' } },
      cancelButtonProps: { style: { display: 'none' } },
      footer: createElement(
        Fragment,
        null,
        createElement(
          Button,
          { key: 'cancel', onClick: () => !settling && finish(false) },
          i18n('workspace.transaction.cancel'),
        ),
        createElement(
          Button,
          { key: 'rollback', danger: true, onClick: () => void releaseAll('rollback') },
          i18n('workspace.transaction.rollback'),
        ),
        createElement(
          Button,
          { key: 'commit', type: 'primary', onClick: () => void releaseAll('commit') },
          i18n('workspace.transaction.commit'),
        ),
      ),
      onCancel: () => finish(false),
    });
  });
}

export default confirmAndReleaseTransaction;
