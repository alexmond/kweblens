import { effectScope, nextTick, ref } from 'vue';
import { describe, expect, it } from 'vitest';

import { useAsyncData } from './useAsyncData';

// The bug this composable exists to remove: a failed request left the pane's data null while
// the table's spinner was bound to `data === null`, so an error banner sat over a spinner
// that ran forever. Three states cannot be encoded in one nullable value.

const flush = () => new Promise((r) => setTimeout(r, 0));

function run<T>(deps: () => unknown, load: () => Promise<T>) {
  const scope = effectScope();
  let out!: ReturnType<typeof useAsyncData<T>>;
  scope.run(() => (out = useAsyncData(deps, load)));
  return { scope, ...out };
}

describe('useAsyncData', () => {
  it('stops loading once the request succeeds', async () => {
    const { scope, data, loading, error } = run(
      () => 1,
      () => Promise.resolve(['a']),
    );
    expect(loading.value).toBe(true);
    await flush();
    expect(loading.value).toBe(false);
    expect(data.value).toEqual(['a']);
    expect(error.value).toBeNull();
    scope.stop();
  });

  it('stops loading when the request FAILS', async () => {
    // The whole point. Before, loading was inferred from `data === null`, so a failure left
    // the spinner running under the error message.
    const { scope, loading, error } = run(
      () => 1,
      () => Promise.reject(new Error('Request timed out after 20s')),
    );
    await flush();
    expect(loading.value).toBe(false);
    expect(error.value).toContain('timed out');
    scope.stop();
  });

  it('keeps the last good data when a reload fails', async () => {
    // Blanking the pane on a failed retry throws away rows the user was reading, and they
    // were not the thing that broke.
    let ok = true;
    const { scope, data, error, reload } = run(
      () => 1,
      () => (ok ? Promise.resolve(['first']) : Promise.reject(new Error('nope'))),
    );
    await flush();
    expect(data.value).toEqual(['first']);
    ok = false;
    reload();
    await flush();
    expect(error.value).toContain('nope');
    expect(data.value).toEqual(['first']);
    scope.stop();
  });

  it('DISCARDS the last good data when the identity changes and the new load fails', async () => {
    // GH#323. The rule above is about a retry of the SAME request. Across a cluster switch the
    // rows are a statement about a different cluster: DiagnosisPanel's header kept saying
    // "11 critical, 19 warning" — the previous cluster's counts — over its own body saying the
    // request for this one had timed out.
    const cluster = ref('kind-a');
    const { scope, data, error } = run(
      () => cluster.value,
      () => (cluster.value === 'kind-a' ? Promise.resolve(['a-rows']) : Promise.reject(new Error('Could not reach'))),
    );
    await flush();
    expect(data.value).toEqual(['a-rows']);

    cluster.value = 'kind-b';
    await flush();
    expect(error.value).toContain('Could not reach');
    expect(data.value).toBeNull();
    scope.stop();
  });

  it('shows nothing rather than the old cluster while the new one is still loading', async () => {
    const cluster = ref('kind-a');
    const { scope, data, loading } = run(
      () => cluster.value,
      () => new Promise((resolve) => setTimeout(() => resolve([cluster.value]), 20)),
    );
    await new Promise((r) => setTimeout(r, 40));
    expect(data.value).toEqual(['kind-a']);

    cluster.value = 'kind-b';
    await nextTick();
    expect(data.value).toBeNull();
    expect(loading.value).toBe(true);
    scope.stop();
  });

  it('clears the previous error when a retry starts', async () => {
    let ok = false;
    const { scope, error, reload } = run(
      () => 1,
      () => (ok ? Promise.resolve([]) : Promise.reject(new Error('boom'))),
    );
    await flush();
    expect(error.value).toContain('boom');
    ok = true;
    reload();
    await flush();
    expect(error.value).toBeNull();
    scope.stop();
  });

  it('ignores a response that arrives after the inputs changed', async () => {
    // A slow answer for a cluster you have navigated away from must not overwrite the one
    // you are looking at now — this is what each pane's hand-rolled `cancelled` flag did.
    const key = ref('slow');
    const { scope, data } = run(
      () => key.value,
      () => {
        const which = key.value;
        return new Promise((resolve) => setTimeout(() => resolve([which]), which === 'slow' ? 30 : 0));
      },
    );
    key.value = 'fast';
    await new Promise((r) => setTimeout(r, 60));
    expect(data.value).toEqual(['fast']);
    scope.stop();
  });

  it('reloads when the dependencies change', async () => {
    const key = ref(1);
    let calls = 0;
    const { scope } = run(
      () => key.value,
      () => {
        calls += 1;
        return Promise.resolve([]);
      },
    );
    await flush();
    expect(calls).toBe(1);
    key.value = 2;
    await flush();
    expect(calls).toBe(2);
    scope.stop();
  });

  it('hands the failure to a caller that wants to handle it specially', async () => {
    // An expired session should sign the user out, not render as a table error.
    let seen: unknown = null;
    const scope = effectScope();
    scope.run(() =>
      useAsyncData(
        () => 1,
        () => Promise.reject(new Error('401')),
        (e) => (seen = e),
      ),
    );
    await flush();
    expect(String(seen)).toContain('401');
    scope.stop();
  });
});
