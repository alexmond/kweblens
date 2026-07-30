import { shallowRef, ref } from 'vue';

import { api } from '../api';
import { loadCluster } from '../prefs';
import type { ClusterInfo } from '../types';

/** The clusters list + the currently-selected cluster (defaults to the first). `refresh` re-runs
 *  the fetch — the shell calls it after a fresh sign-in so closed-mode data appears without a reload. */
export function useClusters(setError: (e: string | null) => void) {
  const clusters = shallowRef<ClusterInfo[]>([]);
  const cluster = ref<string | null>(null);
  const refresh = () =>
    api
      .clusters()
      .then((cs) => {
        clusters.value = cs;
        if (cluster.value === null) {
          // Reopen the cluster you were last on, but only if it still exists — a remembered id
          // for a cluster that has since been removed would leave the app selecting nothing.
          const remembered = loadCluster();
          const stillThere = remembered !== null && cs.some((c) => c.id === remembered);
          cluster.value = stillThere ? remembered : (cs[0]?.id ?? null);
        }
      })
      .catch((e) => setError(String(e)));
  refresh();
  return { clusters, cluster, refresh };
}
