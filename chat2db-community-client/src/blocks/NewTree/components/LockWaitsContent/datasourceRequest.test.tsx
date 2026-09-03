import assert from 'node:assert/strict';
import Module from 'node:module';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';

require.extensions['.css'] = () => undefined;
require.extensions['.less'] = () => undefined;
require.extensions['.png'] = () => undefined;
require.extensions['.svg'] = () => undefined;
require.extensions['.webp'] = () => undefined;
require.extensions['.woff'] = () => undefined;
require.extensions['.woff2'] = () => undefined;

const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>');
dom.window.document.body.appendChild(dom.window.document.createElement('script'));

(globalThis as any).__APP_NAME__ = 'chat2db-community-test';
(globalThis as any).__APP_CAPITAL_NAME__ = 'Chat2DB Community Test';
(globalThis as any).__APP_DISPLAY_NAME__ = 'Chat2DB Community Test';
(globalThis as any).__APP_PROTOCOL_SCHEME__ = 'chat2db-community-test';
(globalThis as any).__APP_VERSION__ = '5.3.0';
(globalThis as any).__RUNTIME_ENV__ = 'community';
(globalThis as any).__ENV__ = 'test';
(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
(globalThis as any).window = dom.window;
(globalThis as any).document = dom.window.document;
Object.defineProperty(globalThis, 'location', {
  configurable: true,
  value: dom.window.location,
});
Object.defineProperty(globalThis, 'navigator', {
  configurable: true,
  value: dom.window.navigator,
});
(globalThis as any).HTMLElement = dom.window.HTMLElement;
(globalThis as any).Element = dom.window.Element;
(globalThis as any).SVGElement = dom.window.SVGElement;
const getComputedStyleStub = () => ({
  getPropertyValue: () => '',
});
const matchMediaStub = () => ({
  matches: false,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  addListener: () => undefined,
  removeListener: () => undefined,
});
const ResizeObserverStub = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
(globalThis as any).getComputedStyle = getComputedStyleStub;
(globalThis as any).matchMedia = matchMediaStub;
(globalThis as any).ResizeObserver = ResizeObserverStub;
(dom.window as any).getComputedStyle = getComputedStyleStub;
(dom.window as any).matchMedia = matchMediaStub;
(dom.window as any).ResizeObserver = ResizeObserverStub;
(globalThis as any).ShadowRoot = dom.window.ShadowRoot;

const i18nMessages: Record<string, string> = {
  'common.button.refresh': 'Refresh',
  'common.text.failure': 'Failure',
  'workspace.ops.lockBlocking': 'Blocking',
  'workspace.ops.blockerQuery': 'Blocker Query',
  'workspace.ops.blockerState': 'Blocker State',
  'workspace.ops.blockerThread': 'Blocker Thread',
  'workspace.ops.blockerUser': 'Blocker User',
  'workspace.ops.blockingChains': 'Blocking Chains',
  'workspace.ops.cycle': 'Cycle',
  'workspace.ops.database': 'Database',
  'workspace.ops.datasourceId': 'Datasource ID',
  'workspace.ops.engineThread': 'engine thread {1}',
  'workspace.ops.engineThreadId': 'Engine Thread',
  'workspace.ops.host': 'Host',
  'workspace.ops.innodbDataLocks': 'InnoDB Data Locks ({1})',
  'workspace.ops.lockData': 'Lock Data',
  'workspace.ops.lockDuration': 'Duration',
  'workspace.ops.lockId': 'Lock ID',
  'workspace.ops.lockMode': 'Mode',
  'workspace.ops.lockObject': 'Object',
  'workspace.ops.lockSnapshotNotice':
    'Current datasource snapshot only. Missing session details can mean the row was released, privileges are limited, or the MySQL version does not expose that source.',
  'workspace.ops.lockSource': 'Source: {1}',
  'workspace.ops.lockSourceUnavailable': 'lock sources unavailable',
  'workspace.ops.lockStatus': 'Status',
  'workspace.ops.lockType': 'Type',
  'workspace.ops.metadataLockCount': '{1} metadata locks',
  'workspace.ops.metadataLocks': 'Metadata Locks ({1})',
  'workspace.ops.metadataLocksUnavailable': 'Metadata lock instrumentation unavailable',
  'workspace.ops.noLockWaits': 'No active lock waits',
  'workspace.ops.ownerThread': 'Owner Thread',
  'workspace.ops.query': 'Query',
  'workspace.ops.role': 'Role',
  'workspace.ops.rootBlocker': 'ROOT BLOCKER',
  'workspace.ops.sessionId': 'Session ID',
  'workspace.ops.sessionStale': 'stale',
  'workspace.ops.sessions': 'Sessions ({1})',
  'workspace.ops.sessionsUnavailable': 'Session rows unavailable',
  'workspace.ops.lockOpenSession': 'Open session {1}',
  'workspace.ops.sessionConsoleTitle': 'Inspect session {1}',
  'workspace.ops.sessionNavigationUnavailable': 'Session navigation is unavailable for this row',
  'workspace.ops.state': 'State',
  'workspace.ops.user': 'User',
  'workspace.ops.valueUnavailable': 'unavailable',
  'workspace.ops.waiterQuery': 'Waiter Query',
  'workspace.ops.waiterState': 'Waiter State',
  'workspace.ops.waiterThread': 'Waiter Thread',
  'workspace.ops.waiterUser': 'Waiter User',
};

const mockSqlService = {
  getLockView: async (_params: unknown): Promise<any> => ({
    source: 'performance_schema',
    dataLocks: [],
    waits: [],
    metaLocks: [],
    sessions: [],
    waitChains: [],
    errors: [],
  }),
};
const originalLoad = (Module as any)._load;
(Module as any)._load = function load(request: string, parent: unknown, isMain: boolean) {
  if (request === '@/i18n') {
    return {
      __esModule: true,
      default: (key: string, ...args: unknown[]) =>
        (i18nMessages[key] || key).replace(/\{(\d+)}/g, (_, index) => String(args[Number(index) - 1] ?? '')),
    };
  }
  if (request === '@/service/sql') {
    return {
      __esModule: true,
      default: mockSqlService,
    };
  }
  return originalLoad.call(this, request, parent, isMain);
};

function createTestContainer() {
  const container = document.createElement('div');
  document.body.appendChild(container);
  return container;
}

async function testLockViewRequestUsesRenderedDatasource() {
  const { default: LockWaitsContent } = await import('./index');
  let capturedParams: unknown;
  const originalGetLockView = mockSqlService.getLockView;
  mockSqlService.getLockView = async (params: any) => {
    capturedParams = params;
    return {
      source: 'performance_schema',
      dataLocks: [],
      waits: [],
      metaLocks: [],
      sessions: [],
      waitChains: [],
      errors: [],
    };
  };
  const container = createTestContainer();
  const root = createRoot(container);

  try {
    await act(async () => {
      root.render(createElement(LockWaitsContent, { dataSourceId: 72 }));
    });

    assert.deepEqual(capturedParams, { dataSourceId: 72 });
  } finally {
    await act(async () => {
      root.unmount();
    });
    container.remove();
    mockSqlService.getLockView = originalGetLockView;
  }
}

async function testLockSnapshotShowsDatasourceAndDegradedSessionState() {
  const { default: LockWaitsContent } = await import('./index');
  const originalGetLockView = mockSqlService.getLockView;
  mockSqlService.getLockView = async () => ({
    dataSourceId: 73,
    source: 'performance_schema',
    dataLocks: [],
    waits: [],
    metaLocks: [
      {
        OBJECT_SCHEMA: 'app',
        OBJECT_NAME: 'orders',
        LOCK_TYPE: 'EXCLUSIVE',
        LOCK_DURATION: 'TRANSACTION',
        LOCK_STATUS: 'PENDING',
        OWNER_THREAD_ID: '52',
      },
    ],
    sessions: [
      {
        THREAD_ID: '55',
        PROCESSLIST_ID: '155',
        PROCESSLIST_USER: 'root-user',
        PROCESSLIST_HOST: '127.0.0.1',
        PROCESSLIST_DB: 'app',
        PROCESSLIST_STATE: 'executing',
        PROCESSLIST_INFO: 'update t set c = 1',
      },
    ],
    waitChains: [
      {
        dataSourceId: 73,
        waiterTransactionId: 'trx-1',
        waiterLockId: 'lock-1',
        waiterThreadId: '101',
        waiterEngineThreadId: '51',
        waiterState: 'LOCK WAIT',
        waiterUser: 'waiter-user',
        waiterHost: 'client-a',
        waiterDatabase: 'app',
        waiterQuery: 'update waiting',
        waiterSessionAvailable: true,
        waiterMetadataLockCount: 0,
        blockerTransactionId: 'trx-2',
        blockerLockId: 'lock-2',
        blockerThreadId: '52',
        blockerEngineThreadId: '52',
        blockerState: null,
        blockerUser: null,
        blockerHost: null,
        blockerDatabase: null,
        blockerQuery: null,
        blockerSessionAvailable: false,
        blockerMetadataLockCount: 1,
        rootBlocker: false,
        cycle: true,
      },
    ],
    errors: [],
  });
  let openedSession: unknown;
  const container = createTestContainer();
  const root = createRoot(container);

  try {
    await act(async () => {
      root.render(
        createElement(LockWaitsContent, {
          dataSourceId: 73,
          onOpenSession: (target: unknown) => {
            openedSession = target;
          },
        }),
      );
    });

    const text = document.body.textContent || '';
    assert.match(text, /Datasource ID: 73/);
    assert.match(text, /Current datasource snapshot only/);
    assert.match(text, /stale/);
    assert.match(text, /Cycle/);
    assert.match(text, /Sessions \(1\)/);
    assert.equal(document.querySelector('[aria-label="Open session 52"]'), null);

    const openWaiterSession = document.querySelector('[aria-label="Open session 101"]');
    assert.ok(openWaiterSession);
    await act(async () => {
      openWaiterSession.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    });
    assert.deepEqual(openedSession, {
      dataSourceId: 73,
      sessionId: '101',
      engineThreadId: '51',
      databaseName: 'app',
      user: 'waiter-user',
      host: 'client-a',
      query: 'update waiting',
    });

    const metadataLocksTab = Array.from(container.querySelectorAll('[role="tab"]')).find((tab) =>
      tab.textContent?.includes('Metadata Locks'),
    );
    assert.ok(metadataLocksTab);
    await act(async () => {
      metadataLocksTab.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    });
    assert.match(container.textContent || '', /PENDING/);
  } finally {
    await act(async () => {
      root.unmount();
    });
    container.remove();
    mockSqlService.getLockView = originalGetLockView;
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function emptyLockView(dataSourceId: number) {
  return {
    dataSourceId,
    source: 'performance_schema',
    dataLocks: [],
    waits: [],
    metaLocks: [],
    sessions: [],
    waitChains: [],
    errors: [],
  };
}

async function testLatestRefreshWins() {
  const { default: LockWaitsContent } = await import('./index');
  const originalGetLockView = mockSqlService.getLockView;
  const first = deferred<any>();
  const second = deferred<any>();
  let requestCount = 0;
  mockSqlService.getLockView = () => (++requestCount === 1 ? first.promise : second.promise);
  const container = createTestContainer();
  const root = createRoot(container);

  try {
    await act(async () => {
      root.render(createElement(LockWaitsContent, { dataSourceId: 80 }));
    });
    assert.equal(requestCount, 1);
    await act(async () => {
      root.render(createElement(LockWaitsContent, { dataSourceId: 81 }));
    });
    assert.equal(requestCount, 2);

    await act(async () => {
      second.resolve(emptyLockView(81));
      await second.promise;
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.match(container.textContent || '', /Datasource ID: 81/);

    await act(async () => {
      first.resolve(emptyLockView(80));
      await first.promise;
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    assert.match(container.textContent || '', /Datasource ID: 81/);
    assert.doesNotMatch(container.textContent || '', /Datasource ID: 80/);
  } finally {
    await act(async () => {
      root.unmount();
    });
    container.remove();
    mockSqlService.getLockView = originalGetLockView;
  }
}

Promise.resolve()
  .then(testLockViewRequestUsesRenderedDatasource)
  .then(testLockSnapshotShowsDatasourceAndDegradedSessionState)
  .then(testLatestRefreshWins)
  .catch((error) => {
  console.error(error);
  process.exitCode = 1;
  });
