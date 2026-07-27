import { ref } from 'vue';

import { api } from '../api';
import type { ClusterInfo } from '../types';

/** The clusters list + the currently-selected cluster (defaults to the first). */
export function useClusters(setError: (e: string | null) => void) {
  const clusters = ref<ClusterInfo[]>([]);
  const cluster = ref<string | null>(null);
  api
    .clusters()
    .then((cs) => {
      clusters.value = cs;
      if (cluster.value === null) {
        cluster.value = cs[0]?.id ?? null;
      }
    })
    .catch((e) => setError(String(e)));
  return { clusters, cluster };
}
