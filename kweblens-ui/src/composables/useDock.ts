import { shallowRef, ref } from 'vue';

import type { DockSession, LogScope } from '../dock';
import type { DockKind } from '../types';

/** Terminal/log dock sessions: open, close, and pop out into floating windows. */
export function useDock() {
  const sessions = shallowRef<DockSession[]>([]);
  const active = ref<string | null>(null);
  let seq = 0;

  const setActive = (id: string | null) => (active.value = id);

  const openDock = (kind: DockKind, namespace: string, pod: string, containers: string[], attach = false) => {
    seq += 1;
    const id = `${kind}:${namespace}/${pod}#${seq}`;
    sessions.value = [...sessions.value, { id, kind, namespace, pod, containers, attach }];
    active.value = id;
  };

  /**
   * Open a log session with an explicit scope: one container, every container of a pod, or
   * every pod behind a workload. Separate from `openDock` so the terminal call sites keep
   * their simple signature and log-only options don't leak into them.
   */
  const openLogs = (
    namespace: string,
    pod: string,
    containers: string[],
    logScope: LogScope,
    workload?: { resourceId: string; name: string },
  ) => {
    seq += 1;
    const target = logScope === 'workload' && workload ? workload.name : pod;
    const id = `logs:${namespace}/${target}#${seq}`;
    sessions.value = [...sessions.value, { id, kind: 'logs', namespace, pod, containers, logScope, workload }];
    active.value = id;
  };

  const closeDock = (id: string) => {
    const next = sessions.value.filter((s) => s.id !== id);
    if (active.value === id) {
      active.value = next[next.length - 1]?.id ?? null;
    }
    sessions.value = next;
  };

  const toggleFloat = (id: string, floating: boolean) => {
    sessions.value = sessions.value.map((s, i) => {
      if (s.id !== id) {
        return s;
      }
      const rect = s.rect ?? { x: 120 + ((i * 32) % 240), y: 120 + ((i * 32) % 240), w: 640, h: 340 };
      return { ...s, floating, rect };
    });
    if (floating) {
      if (active.value === id) {
        const docked = sessions.value.filter((s) => s.id !== id && !s.floating);
        active.value = docked[docked.length - 1]?.id ?? null;
      }
    } else {
      active.value = id;
    }
  };

  return { sessions, active, setActive, openDock, openLogs, closeDock, toggleFloat };
}
