import assert from 'node:assert/strict';
import { createElement } from 'react';
import { act } from 'react-dom/test-utils';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';

import sqlService from '@/service/sql';
import LockWaitsContent from './index';

const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>');

(globalThis as any).window = dom.window;
(globalThis as any).document = dom.window.document;
(globalThis as any).navigator = dom.window.navigator;
(globalThis as any).HTMLElement = dom.window.HTMLElement;
(globalThis as any).Element = dom.window.Element;
(globalThis as any).getComputedStyle = dom.window.getComputedStyle;
(globalThis as any).matchMedia = () => ({
  matches: false,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
});
(globalThis as any).ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
(globalThis as any).ShadowRoot = dom.window.ShadowRoot;

async function testLockViewRequestUsesRenderedDatasource() {
  let capturedParams: unknown;
  const originalGetLockView = sqlService.getLockView;
  sqlService.getLockView = async (params: any) => {
    capturedParams = params;
    return {
      source: 'performance_schema',
      dataLocks: [],
      waits: [],
      metaLocks: [],
      waitChains: [],
      errors: [],
    };
  };

  try {
    await act(async () => {
      createRoot(document.getElementById('root')!).render(createElement(LockWaitsContent, { dataSourceId: 72 }));
    });

    assert.deepEqual(capturedParams, { dataSourceId: 72 });
  } finally {
    sqlService.getLockView = originalGetLockView;
  }
}

testLockViewRequestUsesRenderedDatasource().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
