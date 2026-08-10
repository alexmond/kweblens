import type { Ref } from 'vue';
import { shallowRef, ref, watch } from 'vue';

import { api, clusterBase } from '../api';
import { failureNotice } from '../apiFailure';
import type { ColumnDef } from '../columns';
import { columnsFor, printerColumnDefs } from '../columns';
import { objKey } from '../kube';
import { isSynthetic } from '../shell';
import type { KubeObject, NavItem, NodeDiskUsage, UsageSummary } from '../types';

/** The selected kind's objects (live-watched), its columns, and Pod/Node metrics usage. */
export function useResourceData(
  cluster: Ref<string | null>,
  selected: Ref<NavItem | null>,
  namespace: Ref<string | null>,
  setError: (e: string | null) => void,
  /**
   * Bumped by the shell's Retry. A dependency of the object-list watch and nothing else: the
   * columns, the metrics poll and the watch stream all recover on their own, and the list is
   * the only one of the four whose failure reaches the shell's error slot.
   */
  reload?: Ref<number>,
) {
  const objects = shallowRef<KubeObject[]>([]);
  const loading = ref(false);
  // Reported separately from the error string, which the shell owns and any surface can
  // write to. An empty table after a FAILED list must not claim the kind is empty, and
  // "is there an error somewhere in the shell" is not the same question.
  const failed = ref(false);
  const live = ref(false);
  const cols = shallowRef<ColumnDef[]>([]);
  const usage = shallowRef<Record<string, UsageSummary>>({});
  const nodeDisk = shallowRef<Record<string, NodeDiskUsage>>({});

  const setObjects = (updater: (prev: KubeObject[]) => KubeObject[]) => {
    objects.value = updater(objects.value);
  };
  const scopedNs = (sel: NavItem): string | undefined => (sel.namespaced ? (namespace.value ?? undefined) : undefined);

  watch(
    [cluster, selected, namespace, () => reload?.value],
    ([c, sel], _prev, onCleanup) => {
      if (!c || !sel || isSynthetic(sel.id)) {
        objects.value = [];
        failed.value = false;
        return;
      }
      let cancelled = false;
      onCleanup(() => (cancelled = true));
      loading.value = true;
      failed.value = false;
      setError(null);
      api
        .objects(c, sel.id, scopedNs(sel))
        .then((r) => !cancelled && (objects.value = r))
        .catch((e) => {
          if (!cancelled) {
            setError(failureNotice(e));
            failed.value = true;
            objects.value = [];
          }
        })
        .finally(() => !cancelled && (loading.value = false));
    },
    { immediate: true },
  );

  watch(
    [cluster, selected],
    ([c, sel], _prev, onCleanup) => {
      if (!c || !sel || isSynthetic(sel.id)) {
        cols.value = [];
        return;
      }
      if (sel.id.includes('.')) {
        let cancelled = false;
        onCleanup(() => (cancelled = true));
        api
          .printerColumns(c, sel.id)
          .then((pc) => !cancelled && (cols.value = pc.length ? printerColumnDefs(pc) : []))
          .catch(() => (cols.value = []));
        return;
      }
      cols.value = columnsFor(sel.id);
    },
    { immediate: true },
  );

  watch(
    [cluster, selected, namespace],
    ([c, sel], _prev, onCleanup) => {
      if (!c || !sel || (sel.id !== 'pods' && sel.id !== 'nodes')) {
        usage.value = {};
        nodeDisk.value = {};
        return;
      }
      let cancelled = false;
      const load = () => {
        const p = sel.id === 'nodes' ? api.nodeMetrics(c) : api.podMetrics(c, scopedNs(sel));
        p.then((list) => {
          if (cancelled) {
            return;
          }
          const map: Record<string, UsageSummary> = {};
          list.forEach((u) => (map[(u.namespace ?? '') + '/' + u.name] = u));
          usage.value = map;
        }).catch(() => !cancelled && (usage.value = {}));
        if (sel.id === 'nodes') {
          api
            .nodeDisk(c)
            .then((list) => {
              if (cancelled) {
                return;
              }
              const dmap: Record<string, NodeDiskUsage> = {};
              list.forEach((d) => (dmap[d.node] = d));
              nodeDisk.value = dmap;
            })
            .catch(() => !cancelled && (nodeDisk.value = {}));
        }
      };
      load();
      const timer = window.setInterval(load, 15000);
      onCleanup(() => {
        cancelled = true;
        window.clearInterval(timer);
      });
    },
    { immediate: true },
  );

  watch(
    [cluster, selected, namespace],
    ([c, sel], _prev, onCleanup) => {
      if (!c || !sel || isSynthetic(sel.id)) {
        return;
      }
      const ns = scopedNs(sel);
      const url =
        `${clusterBase(c)}/resources/${encodeURIComponent(sel.id)}/objects/watch` +
        (ns ? `?namespace=${encodeURIComponent(ns)}` : '');
      const es = new EventSource(url);
      // Watch events are BUFFERED and applied at most once per animation frame — never one
      // reassignment (and full table re-render) per object. On connect a watch replays the
      // whole list (e.g. 157 ReplicaSets) as an ADDED burst; without batching that froze the
      // tab before first paint. All pending events coalesce into a single objects update.
      let pending: { del: boolean; obj: KubeObject }[] = [];
      let frame = 0;
      const flush = () => {
        frame = 0;
        if (!pending.length) {
          return;
        }
        const byKey = new Map(objects.value.map((o) => [objKey(o), o]));
        for (const { del, obj } of pending) {
          const k = objKey(obj);
          if (del) {
            byKey.delete(k);
          } else {
            byKey.set(k, obj);
          }
        }
        pending = [];
        objects.value = [...byKey.values()];
      };
      const enqueue = (del: boolean) => (e: MessageEvent) => {
        pending.push({ del, obj: JSON.parse(e.data) as KubeObject });
        if (frame === 0) {
          frame = requestAnimationFrame(flush);
        }
      };
      const upsert = enqueue(false);
      es.addEventListener('ADDED', upsert as EventListener);
      es.addEventListener('MODIFIED', upsert as EventListener);
      es.addEventListener('DELETED', enqueue(true) as EventListener);
      es.onopen = () => (live.value = true);
      es.onerror = () => (live.value = false);
      onCleanup(() => {
        live.value = false;
        es.close();
        if (frame !== 0) {
          cancelAnimationFrame(frame);
        }
        pending = [];
      });
    },
    { immediate: true },
  );

  return { objects, setObjects, loading, failed, live, cols, usage, nodeDisk };
}
