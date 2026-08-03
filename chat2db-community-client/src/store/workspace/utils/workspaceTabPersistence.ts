import { IWorkspaceTab } from '@/typings';

/**
 * Maximum number of workspace tabs persisted to localStorage. The in-memory
 * list is unaffected; only what is persisted is capped, so localStorage cannot
 * grow unbounded and overflow quota (which would throw QuotaExceededError and
 * corrupt unrelated persisted keys). On reload, tabs beyond this cap simply do
 * not restore — graceful degradation versus quota corruption. The most recent
 * tabs are kept.
 */
const MAX_PERSISTED_TABS = 100;

function capPersistedTabs(tabs: IWorkspaceTab[]): IWorkspaceTab[] {
  return tabs.length > MAX_PERSISTED_TABS ? tabs.slice(-MAX_PERSISTED_TABS) : tabs;
}

export function getPersistableWorkspaceTabList(workspaceTabList?: IWorkspaceTab[] | null) {
  if (!workspaceTabList?.length) {
    return workspaceTabList || null;
  }

  try {
    return capPersistedTabs(
      JSON.parse(
        JSON.stringify(workspaceTabList, (_key, value) => {
          if (typeof value === 'function') {
            return undefined;
          }
          return value;
        }),
      ) as IWorkspaceTab[],
    );
  } catch {
    return capPersistedTabs(
      workspaceTabList.map((tab) => ({
        id: tab.id,
        type: tab.type,
        title: tab.title,
        uniqueData: tab.uniqueData
          ? Object.fromEntries(Object.entries(tab.uniqueData).filter(([, value]) => typeof value !== 'function'))
          : undefined,
      })),
    );
  }
}

export function getPersistableActiveConsoleId(params: {
  activeConsoleId?: string | number | null;
  workspaceTabList?: IWorkspaceTab[] | null;
}) {
  const { activeConsoleId, workspaceTabList } = params;
  if (!workspaceTabList?.length) {
    return null;
  }
  if (workspaceTabList.some((tab) => tab.id === activeConsoleId)) {
    return activeConsoleId || null;
  }
  return workspaceTabList[0].id;
}
