import { ref } from 'vue';

import { api } from '../api';
import type { ClusterInfo } from '../types';

/** The clusters list + the currently-selected cluster (defaults to the first). `refresh` re-runs
 *  the fetch — the shell calls it after a fresh sign-in so closed-mode data appears without a reload. */
export function useClusters(setError: (e: string | null) => void) {
  const clusters = ref<ClusterInfo[]>([]);
  const cluster = ref<string | null>(null);
  const refresh = () =>
    api
      .clusters()
      .then((cs) => {
        clusters.value = cs;
        if (cluster.value === null) {
          cluster.value = cs[0]?.id ?? null;
        }
      })
      .catch((e) => setError(String(e)));
  refresh();
  return { clusters, cluster, refresh };
}
