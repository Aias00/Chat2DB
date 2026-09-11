const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { JSDOM } = require('jsdom');

const dom = new JSDOM('<!doctype html><div id="root"></div>', { url: 'http://localhost' });
Object.defineProperties(globalThis, {
  window: { configurable: true, value: dom.window },
  document: { configurable: true, value: dom.window.document },
  navigator: { configurable: true, value: dom.window.navigator },
  IS_REACT_ACT_ENVIRONMENT: { configurable: true, value: true },
});
const React = require('react');
const { createRoot } = require('react-dom/client');
const { act } = React;
const root = createRoot(document.getElementById('root'));
const timers = new Map();
const messages = [];
const failures = new Map();
const requests = [];
let timerId = 0;
let fallback = '失败';
let resolvePending;
let rejectPending;
const getTaskList = () => {};
const globalStore = () => ({});
globalStore.getState = () => ({ baseSetting: { language: 'zh-CN' } });

const request = (url, options) => {
  const taskId = options.params?.taskId;
  requests.push({ url, taskId });
  if (url === '/api/tasks/get') {
    const error = failures.get(taskId);
    if (error === 'deferred') {
      return new Promise((resolve, reject) => { resolvePending = resolve; rejectPending = reject; });
    }
    if (error instanceof Error || typeof error === 'string') return Promise.reject(error);
    if (error) return Promise.resolve({ success: false, ...error });
    return Promise.resolve({ success: true, data: { id: taskId, status: 'RUNNING' } });
  }
  return Promise.resolve({ success: true, data: [] });
};
const transport = {
  get: request, post: request, delete: request, put: request,
  interceptors: { request: { use() {} }, response: { use() {} } },
};
const box = (props) => React.createElement('div', null, props.children);
const list = React.forwardRef((_props, ref) => {
  React.useImperativeHandle(ref, () => ({ scrollTo() {}, getScrollInfo: () => ({ y: 0 }) }));
  return null;
});
const mocks = {
  react: React,
  'react/jsx-runtime': require('react/jsx-runtime'),
  ahooks: { useSize: () => ({ height: 200 }) },
  'rc-virtual-list': { default: list },
  './style': { useStyles: () => ({ styles: {} }) },
  antd: { Spin: box, Progress: box },
  '@chat2db/ui': { staticMessage: { error: (message) => messages.push(message) } },
  '@/i18n': { default: (key) => key === 'common.text.failure' ? fallback : key },
  '@/store/importExport': { useImportExportStore: (select) => select({ getTaskList }) },
  '@/store/importExport/taskCenterUtils': {
    mergeTaskEvents: (a, b) => a.concat(b), TASK_EVENT_INITIAL_PAGE_SIZE: 100, TASK_EVENT_PAGE_SIZE: 100,
  },
  '@/components/ConsoleOutput': { ConsoleOutputEmpty: box, ConsoleOutputMessageLine: box },
  './eventMessage': { formatTaskEventMessage: (event) => event.message },
  '@/constants/request': { ErrorCodesWithoutToast: [] },
  '@client-runtime': { clientRuntime: {} },
  '@/store/global': { useGlobalStore: globalStore },
  '@/utils/env': { isDesktop: false },
  'umi-request': { default: transport },
  './commandLine/commandLine': { commandLineRequest: () => assert.fail('Unexpected desktop transport') },
  '@/service/interceptorsResponse': { default: () => {} },
};
const sources = {
  log: 'src/blocks/ImportAndExport/components/Log/index.tsx',
  '@/service/importExport': 'src/service/importExport.ts',
  './base': 'src/service/base.tsx',
  '@/constants/importExport': 'src/constants/importExport.ts',
};
const modules = new Map();
function load(name) {
  if (modules.has(name)) return modules.get(name);
  const filename = path.resolve(__dirname, '..', sources[name]);
  const source = fs.readFileSync(filename, 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS,
      jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
    },
  });
  const module = { exports: {} };
  vm.runInNewContext(outputText, {
    module, exports: module.exports, console, Intl, window: dom.window,
    setTimeout: (fn, delay) => { const id = ++timerId; timers.set(id, { fn, delay }); return id; },
    clearTimeout: (id) => timers.delete(id),
    require(id) {
      if (sources[id]) return load(id);
      assert.ok(id in mocks, 'Unexpected dependency: ' + id);
      const mock = mocks[id];
      return mock && 'default' in mock ? { __esModule: true, ...mock } : mock;
    },
  }, { filename });
  modules.set(name, module.exports);
  return module.exports;
}
const Log = load('log').default;
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
const render = async (taskId) => act(async () => {
  root.render(React.createElement(Log, { taskId }));
  await flush();
});
const nextPoll = () => {
  const entry = timers.entries().next().value;
  assert.ok(entry, 'Polling must stay scheduled');
  timers.delete(entry[0]);
  return entry[1];
};
const tick = async () => act(async () => { await nextPoll().fn(); await flush(); });
async function main() {
  await render(1);
  failures.set(1, { errorCode: 'TASK_NOT_FOUND', errorMessage: '该任务已被删除，请刷新任务列表' });
  await tick();
  assert.equal(messages.at(-1), '该任务已被删除，请刷新任务列表', 'Preserve the real request errorMessage contract');
  assert.equal([...timers.values()][0].delay, 1500);
  await tick();
  assert.equal(messages.length, 1, 'Persistent failure must not repeat notifications');

  failures.delete(1);
  await tick();
  failures.set(1, new Error('Network unavailable'));
  await tick();
  assert.equal(messages.at(-1), 'Network unavailable', 'Recovery enables the next failure notification');

  await render(2);
  failures.set(2, { errorMessage: 'Task 2 failed' });
  await tick();
  assert.equal(messages.at(-1), 'Task 2 failed', 'A new task must not inherit notification suppression');

  failures.set(2, 'deferred');
  const pendingPoll = nextPoll().fn();
  await flush();
  await render(3);
  const beforeStale = messages.length;
  rejectPending(new Error('Stale task failure'));
  await act(async () => { await pendingPoll; await flush(); });
  assert.equal(messages.length, beforeStale, 'A stale task failure must not notify');

  for (const [error, expected] of [
    ['Desktop transport failed', 'Desktop transport failed'],
    [{ errorMessage: '' }, '失败'],
    [{ errorMessage: 42 }, '失败'],
  ]) {
    failures.delete(3);
    await tick();
    failures.set(3, error);
    await tick();
    assert.equal(messages.at(-1), expected);
  }
  failures.delete(3);
  await tick();
  fallback = 'Failure';
  failures.set(3, { errorMessage: ' ' });
  await tick();
  assert.equal(messages.at(-1), 'Failure', 'Resolve the fallback in the current language');

  failures.set(3, 'deferred');
  const unmountedPoll = nextPoll().fn();
  await flush();
  const beforeUnmount = messages.length;
  await act(async () => { root.unmount(); await flush(); });
  resolvePending({ success: true, data: { id: 3, status: 'RUNNING' } });
  await act(async () => { await unmountedPoll; await flush(); });
  assert.equal(messages.length, beforeUnmount);
  assert.equal(timers.size, 0, 'Unmounted logs must not schedule another poll');
  assert.ok(requests.filter(({ url }) => url === '/api/tasks/get').length > 10);
  console.log('Task log polling: business/network/string errors, fallback, deduplication, recovery, task changes and cleanup passed.');
}
main().catch((error) => { console.error(error); process.exitCode = 1; }).finally(() => dom.window.close());
