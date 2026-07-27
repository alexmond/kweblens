import type { Ref } from 'vue';
import { ref, watch } from 'vue';

import { api, clusterBase } from '../api';
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
) {
  const objects = ref<KubeObject[]>([]);
  const loading = ref(false);
  const live = ref(false);
  const cols = ref<ColumnDef[]>([]);
  const usage = ref<Record<string, UsageSummary>>({});
  const nodeDisk = ref<Record<string, NodeDiskUsage>>({});

  const setObjects = (updater: (prev: KubeObject[]) => KubeObject[]) => {
    objects.value = updater(objects.value);
  };
  const scopedNs = (sel: NavItem): string | undefined => (sel.namespaced ? (namespace.value ?? undefined) : undefined);

  watch(
    [cluster, selected, namespace],
    ([c, sel], _prev, onCleanup) => {
      if (!c || !sel || isSynthetic(sel.id)) {
        objects.value = [];
        return;
      }
      let cancelled = false;
      onCleanup(() => (cancelled = true));
      loading.value = true;
      setError(null);
      api
        .objects(c, sel.id, scopedNs(sel))
        .then((r) => !cancelled && (objects.value = r))
        .catch((e) => {
          if (!cancelled) {
            setError(String(e));
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
      const upsert = (e: MessageEvent) => {
        const obj = JSON.parse(e.data) as KubeObject;
        const key = objKey(obj);
        const idx = objects.value.findIndex((o) => objKey(o) === key);
        if (idx === -1) {
          objects.value = [...objects.value, obj];
        } else {
          const next = objects.value.slice();
          next[idx] = obj;
          objects.value = next;
        }
      };
      const remove = (e: MessageEvent) => {
        const obj = JSON.parse(e.data) as KubeObject;
        objects.value = objects.value.filter((o) => objKey(o) !== objKey(obj));
      };
      es.addEventListener('ADDED', upsert as EventListener);
      es.addEventListener('MODIFIED', upsert as EventListener);
      es.addEventListener('DELETED', remove as EventListener);
      es.onopen = () => (live.value = true);
      es.onerror = () => (live.value = false);
      onCleanup(() => {
        live.value = false;
        es.close();
      });
    },
    { immediate: true },
  );

  return { objects, setObjects, loading, live, cols, usage, nodeDisk };
}
