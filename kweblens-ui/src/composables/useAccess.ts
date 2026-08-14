import type { Ref } from 'vue';
import { watch } from 'vue';

import { api } from '../api';
import { isSynthetic } from '../shell';
import type { KindAccess, NavItem } from '../types';
import { clusterScoped } from './clusterScoped';

/**
 * What the deployment's service account may do with the kind on screen (#354).
 *
 * <p>One request per surface — entering a list, not rendering a row — and the answer is
 * `null` whenever there is nothing trustworthy to say. `null` is not an error state: it is
 * "we do not know", which every consumer renders as ENABLED. Nothing here is an
 * authorization gate; see `permissions.ts`.
 *
 * <p>Declared through `clusterScoped`, so a cluster switch empties it before any fetch for the
 * new cluster starts (GH#323). That matters more here than for a count: a stale "allowed" is
 * merely optimistic, but a stale "denied" carried across a switch would grey out a control the
 * cluster you are now looking at is perfectly happy to run — a plausible, wrong claim about a
 * cluster nobody asked about.
 *
 * <p><b>No reload nonce in the watch.</b> The verdicts' identity is (cluster, kind, namespace);
 * a Retry re-asks the same question and the server's own 60s cache answers it. Putting a nonce
 * here would make a refresh look like a change of subject, which is the mistake `useAsyncData`
 * documents at length.
 */
export function useAccess(
  cluster: Ref<string | null>,
  selected: Ref<NavItem | null>,
  namespace: Ref<string | null>,
): Ref<KindAccess | null> {
  const access = clusterScoped<KindAccess | null>(cluster, () => null);

  watch(
    [cluster, selected, namespace],
    ([c, sel, ns], _prev, onCleanup) => {
      // Cleared first, and unconditionally: the previous kind's verdicts are a statement
      // about a different question, and between the two the honest answer is "unknown"
      // (which is to say: every control enabled).
      access.value = null;
      if (!c || !sel || isSynthetic(sel.id)) {
        return;
      }
      let cancelled = false;
      onCleanup(() => (cancelled = true));
      api
        .access(c, sel.id, sel.namespaced ? (ns ?? undefined) : undefined)
        // A failure is deliberately silent and leaves `null` behind. It is not shown in the
        // shell's error slot: nothing the operator asked for has failed, and an error banner
        // over a working list would be the probe complaining about itself.
        .then((a) => !cancelled && (access.value = a))
        .catch(() => !cancelled && (access.value = null));
    },
    { immediate: true },
  );

  return access;
}
