import type { Ref } from 'vue';

import { auth } from '../auth';
import type { DialogApi } from '../dialog';
import type { LogScope } from '../dock';
import type { RowAction } from '../rowActions';
import { nextColumnChoice } from '../columnFit';
import { saveHiddenCols, saveKeptCols } from '../prefs';
import { dispatchRowAction, fetchWorkloadPods, runBulkDelete, saveFavorites, toggleInSet } from '../shell';
import type { DockKind, KubeObject, NavItem } from '../types';

/** The shell's action handlers (row actions, selection/column toggles, favorites, bulk delete). */
export function useAppActions(a: {
  cluster: Ref<string | null>;
  authUser: Ref<string | null>;
  selected: Ref<NavItem | null>;
  selection: Ref<Set<string>>;
  objects: Ref<KubeObject[]>;
  hiddenCols: Ref<Set<string>>;
  /** Columns pinned against the width rule, and the ones the width took away (#238). */
  keptCols: Ref<Set<string>>;
  autoHiddenCols: Ref<Set<string>>;
  favorites: Ref<string[]>;
  dialog: DialogApi;
  openDock: (kind: DockKind, ns: string, pod: string, containers: string[], attach?: boolean) => void;
  openLogs: (
    ns: string,
    pod: string,
    containers: string[],
    scope: LogScope,
    workload?: { resourceId: string; name: string },
  ) => void;
  setForward: (f: { kind: string; namespace: string; name: string; ports: number[] }) => void;
  setDetail: (d: { resourceId: string; obj: KubeObject; edit?: boolean } | null) => void;
  /**
   * A write that did not complete: a row action, or a bulk delete's own summary line.
   *
   * <p>Kept apart from the shell's read failures because they are not interchangeable — the
   * shell renders one with a Retry and the other without, and everything under this composable
   * is a write (`paneFailure.ts`).
   */
  reportFailure: (title: string, e: unknown) => void;
  reportOutcome: (message: string) => void;
  setObjects: (updater: (prev: KubeObject[]) => KubeObject[]) => void;
  setShowLogin: (v: boolean) => void;
  setAuthUser: (v: string | null) => void;
  /** `objKey` of the object the drawer is showing — see RowActionDeps.detailKey. */
  detailKey: Ref<string | null>;
}) {
  const signOut = () => {
    auth.clear();
    a.setAuthUser(null);
  };

  const fetchPods = (obj: KubeObject): Promise<KubeObject[]> =>
    a.cluster.value ? fetchWorkloadPods(a.cluster.value, obj) : Promise.resolve([]);

  const handleRowAction = (resourceId: string, action: RowAction, obj: KubeObject, container?: string) => {
    if (!a.cluster.value) {
      return;
    }
    dispatchRowAction(resourceId, action, obj, container, {
      cluster: a.cluster.value,
      authUser: a.authUser.value,
      dialog: a.dialog,
      openDock: a.openDock,
      openLogs: a.openLogs,
      setForward: a.setForward,
      setDetail: a.setDetail,
      reportFailure: a.reportFailure,
      setObjects: a.setObjects,
      setShowLogin: a.setShowLogin,
      detailKey: a.detailKey.value,
    });
  };

  const toggleFavorite = (id: string) => {
    const next = a.favorites.value.includes(id)
      ? a.favorites.value.filter((x) => x !== id)
      : [...a.favorites.value, id];
    a.favorites.value = next;
    if (a.cluster.value) {
      saveFavorites(a.cluster.value, next);
    }
  };

  // Persist as well as apply: a column choice that vanishes on the next navigation is the
  // papercut #27 set out to fix. The decision itself is in `columnFit.ts` — checking a
  // column the WIDTH removed has to pin it, not merely un-hide it, or the next fit takes it
  // away again and the picker looks broken (#238).
  const toggleCol = (key: string) => {
    const next = nextColumnChoice(key, {
      hidden: a.hiddenCols.value,
      keep: a.keptCols.value,
      autoHidden: a.autoHiddenCols.value,
    });
    a.hiddenCols.value = next.hidden;
    a.keptCols.value = next.keep;
    saveHiddenCols(a.selected.value?.id, next.hidden);
    saveKeptCols(a.selected.value?.id, next.keep);
  };
  const toggleRow = (key: string) => (a.selection.value = toggleInSet(a.selection.value, key));
  const toggleAll = (keys: string[]) => {
    a.selection.value = a.selection.value.size >= keys.length && keys.length > 0 ? new Set() : new Set(keys);
  };

  const bulkDelete = () => {
    if (!a.cluster.value || !a.selected.value || a.selection.value.size === 0) {
      return;
    }
    if (!a.authUser.value) {
      a.setShowLogin(true);
      return;
    }
    void runBulkDelete({
      cluster: a.cluster.value,
      selected: a.selected.value,
      selection: a.selection.value,
      objects: a.objects.value,
      dialog: a.dialog,
      reportOutcome: a.reportOutcome,
      onAuthCleared: () => {
        signOut();
        a.setShowLogin(true);
      },
      clearSelection: () => (a.selection.value = new Set()),
    });
  };

  return { signOut, fetchPods, handleRowAction, toggleFavorite, toggleCol, toggleRow, toggleAll, bulkDelete };
}
