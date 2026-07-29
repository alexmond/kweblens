import { shallowRef, ref } from 'vue';

import type { DockSession } from '../dock';
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

  return { sessions, active, setActive, openDock, closeDock, toggleFloat };
}
