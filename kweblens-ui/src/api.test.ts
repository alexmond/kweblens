import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, api } from './api';

/** Stub `fetch`, recording the URL each call was made against. */
function stubFetch(res: () => Response) {
  const calls: string[] = [];
  vi.stubGlobal('fetch', (url: string) => {
    calls.push(url);
    return Promise.resolve(res());
  });
  return calls;
}

const okJson = () => new Response('{"result":"deleted"}', { status: 200, headers: { 'Content-Type': 'text/json' } });

afterEach(() => vi.unstubAllGlobals());

describe('the action URL carries a namespace segment even when there is none', () => {
  // #297: a cluster-scoped kind has no namespace, and an empty segment makes
  // `…/resources/persistentvolumes//pv-1/delete`, which the server's mapping does not match —
  // it 404s. `_` is not a legal namespace, so it stands for "cluster-scoped".

  it('sends `_` for a cluster-scoped object', async () => {
    const calls = stubFetch(okJson);
    await api.del('c1', 'persistentvolumes', '', 'pv-1');
    expect(calls[0]).toBe('/api/v1/clusters/c1/resources/persistentvolumes/_/pv-1/delete');
  });

  it('sends the real namespace for a namespaced object', async () => {
    const calls = stubFetch(okJson);
    await api.del('c1', 'configmaps', 'scratch', 'cm-1');
    expect(calls[0]).toBe('/api/v1/clusters/c1/resources/configmaps/scratch/cm-1/delete');
  });
});

describe('a refused action keeps the cluster’s own explanation', () => {
  it('throws the ProblemDetail detail rather than the status line', async () => {
    stubFetch(
      () =>
        new Response('{"detail":"admission webhook denied the request: no owner label","status":422}', {
          status: 422,
          statusText: 'Unprocessable Entity',
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    );

    const err = await api.del('c1', 'configmaps', 'scratch', 'cm-1').then(
      () => null,
      (e: unknown) => e,
    );
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(422);
    expect(String(err)).toContain('admission webhook denied the request: no owner label');
  });

  it('falls back to the status line when the body is not a ProblemDetail', async () => {
    stubFetch(() => new Response('<html>gateway</html>', { status: 502, statusText: 'Bad Gateway' }));

    const err = await api.del('c1', 'configmaps', 'scratch', 'cm-1').then(
      () => null,
      (e: unknown) => e,
    );
    expect(String(err)).toContain('502 Bad Gateway');
  });
});
