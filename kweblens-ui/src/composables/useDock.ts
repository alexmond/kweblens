import type { Ref } from 'vue';
import { computed, shallowRef } from 'vue';

import { clusterScoped } from './clusterScoped';
import type { DockSession, LogScope } from '../dock';
import type { DockKind } from '../types';

/**
 * Terminal/log dock sessions: open, close, and pop out into floating windows.
 *
 * <p>Sessions are held per cluster and exposed for the current one only (GH#323). They are not
 * discarded on a switch — a shell is expensive to re-open and the operator may be stepping into
 * another cluster and back — but they are not carried over either: the dock used to hand every
 * session whatever cluster was current, so a switch restarted an open terminal against the new
 * cluster while its tab still named the pod from the old one.
 */
export function useDock(cluster: Ref<string | null>) {
  const all = shallowRef<DockSession[]>([]);
  const active = clusterScoped<string | null>(cluster, () => null);
  let seq = 0;

  const mine = (list: DockSession[]) => list.filter((s) => s.cluster === cluster.value);
  const sessions = computed(() => mine(all.value));

  const setActive = (id: string | null) => (active.value = id);

  const add = (session: Omit<DockSession, 'cluster'>) => {
    const c = cluster.value;
    if (!c) {
      return;
    }
    all.value = [...all.value, { ...session, cluster: c }];
    active.value = session.id;
  };

  const openDock = (kind: DockKind, namespace: string, pod: string, containers: string[], attach = false) => {
    seq += 1;
    add({ id: `${kind}:${namespace}/${pod}#${seq}`, kind, namespace, pod, containers, attach });
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
    add({ id: `logs:${namespace}/${target}#${seq}`, kind: 'logs', namespace, pod, containers, logScope, workload });
  };

  const closeDock = (id: string) => {
    const next = all.value.filter((s) => s.id !== id);
    if (active.value === id) {
      const rest = mine(next);
      active.value = rest[rest.length - 1]?.id ?? null;
    }
    all.value = next;
  };

  const toggleFloat = (id: string, floating: boolean) => {
    all.value = all.value.map((s, i) => {
      if (s.id !== id) {
        return s;
      }
      const rect = s.rect ?? { x: 120 + ((i * 32) % 240), y: 120 + ((i * 32) % 240), w: 640, h: 340 };
      return { ...s, floating, rect };
    });
    if (floating) {
      if (active.value === id) {
        const docked = mine(all.value).filter((s) => s.id !== id && !s.floating);
        active.value = docked[docked.length - 1]?.id ?? null;
      }
    } else {
      active.value = id;
    }
  };

  return { sessions, active, setActive, openDock, openLogs, closeDock, toggleFloat };
}
