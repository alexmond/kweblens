import type { Ref } from 'vue';
import { failureNotice } from '../apiFailure';
import { ref, shallowRef, watch } from 'vue';

/**
 * Loads data for a pane, keeping "loading", "loaded" and "failed" as three distinct states.
 *
 * <p>The bug this exists to remove: every pane used `data === null` to mean BOTH "not loaded
 * yet" and "the request failed", and bound its table's spinner to that. So a failed request
 * showed an error banner over a spinner that ran forever, with the empty state stuck on
 * "Loading…" — two contradictory answers on screen at once. Three states cannot be encoded
 * in one nullable value, so `loading` is its own flag and stops whatever the outcome.
 *
 * <p>The sequence guard replaces the hand-rolled `cancelled` flag each pane carried: a slow
 * response for a cluster you have already navigated away from must not overwrite the data
 * for the one you are looking at now.
 */
export interface AsyncData<T> {
  /** The loaded value, or null before the first success. Null does NOT imply loading. */
  data: Ref<T | null>;
  /** True only while a request is in flight. False once it succeeds OR fails. */
  loading: Ref<boolean>;
  /** The failure message, or null. Cleared when a retry starts. */
  error: Ref<string | null>;
  /** Run the load again — what a Retry control calls. */
  reload: () => void;
}

/**
 * @param deps what to reload on, in the shape `watch` expects
 * @param load performs the request
 * @param onError optional hook for failures the pane handles specially, e.g. an expired
 * session that should sign the user out rather than render as a table error
 */
export function useAsyncData<T>(
  deps: () => unknown,
  load: () => Promise<T>,
  onError?: (e: unknown) => void,
): AsyncData<T> {
  const data = shallowRef<T | null>(null) as Ref<T | null>;
  const loading = ref(true);
  const error = ref<string | null>(null);
  let seq = 0;

  const reload = () => {
    const mine = ++seq;
    loading.value = true;
    error.value = null;
    load()
      .then((value) => {
        if (mine === seq) {
          data.value = value;
        }
      })
      .catch((e: unknown) => {
        if (mine !== seq) {
          return;
        }
        // The data is deliberately left as it was rather than cleared: on a retry that
        // fails, showing the last good rows under an error beats blanking the pane.
        error.value = failureNotice(e);
        onError?.(e);
      })
      .finally(() => {
        if (mine === seq) {
          loading.value = false;
        }
      });
  };

  watch(deps, reload, { immediate: true });
  return { data, loading, error, reload };
}
