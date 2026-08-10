import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, NO_NAMESPACE, api } from './api';

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

describe('the detail URL carries a namespace segment even when there is none', () => {
  // #313: `boundClaim` resolves from a PersistentVolume, which is cluster-scoped. The drawer
  // used to skip the fetch entirely when the object had no namespace, so that relation was
  // never requested by the only shipped client — not empty, not errored, never asked for.
  const detailBody = () =>
    new Response('{"object":{},"relations":{}}', { status: 200, headers: { 'Content-Type': 'application/json' } });

  it('sends the sentinel for a cluster-scoped object rather than skipping the call', async () => {
    const calls = stubFetch(detailBody);
    await api.detail('c1', 'persistentvolumes', undefined, 'pv-1');
    expect(calls[0]).toBe(`/api/v1/clusters/c1/detail/persistentvolumes/${NO_NAMESPACE}/pv-1`);
  });

  it('treats an empty namespace the same as an absent one', async () => {
    const calls = stubFetch(detailBody);
    await api.detail('c1', 'nodes', '', 'node-1');
    expect(calls[0]).toBe(`/api/v1/clusters/c1/detail/nodes/${NO_NAMESPACE}/node-1`);
  });

  it('sends the real namespace for a namespaced object', async () => {
    const calls = stubFetch(detailBody);
    await api.detail('c1', 'configmaps', 'scratch', 'cm-1');
    expect(calls[0]).toBe('/api/v1/clusters/c1/detail/configmaps/scratch/cm-1');
  });

  it('escapes a namespace and name rather than pasting them into the path', async () => {
    const calls = stubFetch(detailBody);
    await api.detail('c1', 'configmaps', 'a/b', 'c d');
    expect(calls[0]).toBe('/api/v1/clusters/c1/detail/configmaps/a%2Fb/c%20d');
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

/** A ProblemDetail body, the shape `ApiExceptionHandler` produces. */
const problem = (status: number, statusText: string, detail: string, code: string) =>
  new Response(JSON.stringify({ detail, status, code }), {
    status,
    statusText,
    headers: { 'Content-Type': 'application/problem+json' },
  });

describe('a failed READ keeps the cluster’s own explanation too', () => {
  // getJson/getText used to throw `${status} ${statusText} — ${url}` and never look at the
  // body, so the sentence naming the service account, the verb and the namespace — fetched
  // over the wire, on the read path CLAUDE.md tells operators to run read-only RBAC on —
  // was dropped on the floor.

  it('surfaces the RBAC sentence from a list read, with the code that says whose 403 it is', async () => {
    stubFetch(() =>
      problem(
        403,
        'Forbidden',
        'pods is forbidden: User "system:serviceaccount:kweblens:kweblens" cannot list resource "pods" in the namespace "prod"',
        'cluster-refused',
      ),
    );

    const err = (await api.objects('c1', 'pods', 'prod').then(
      () => null,
      (e: unknown) => e,
    )) as ApiError;

    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(403);
    expect(err.code).toBe('cluster-refused');
    expect(err.message).toContain('cannot list resource "pods" in the namespace "prod"');
  });

  it('keeps the URL when there is no ProblemDetail — then it is the only context left', async () => {
    stubFetch(() => new Response('<html>gateway</html>', { status: 502, statusText: 'Bad Gateway' }));

    const err = await api.nav('c1').then(
      () => null,
      (e: unknown) => e,
    );

    expect(String(err)).toContain('502 Bad Gateway');
    expect(String(err)).toContain('/api/v1/clusters/c1/nav');
  });
});

describe('a failed WRITE keeps the cluster’s own explanation', () => {
  // apply/Helm/port-forward/add-cluster all went through helpers that threw the status line
  // alone, so a webhook rejection read "422 Unprocessable Content" and nothing else — while
  // the dry-run button beside it printed the whole sentence.

  it('surfaces an admission-webhook rejection from apply', async () => {
    stubFetch(() =>
      problem(
        422,
        'Unprocessable Content',
        'admission webhook "policy" denied the request: no owner label',
        'cluster-refused',
      ),
    );

    const err = (await api.apply('c1', 'kind: ConfigMap').then(
      () => null,
      (e: unknown) => e,
    )) as ApiError;

    expect(err.message).toBe('admission webhook "policy" denied the request: no owner label');
    expect(err.code).toBe('cluster-refused');
  });

  it('surfaces why a cluster definition was rejected, not just 400', async () => {
    stubFetch(() => problem(400, 'Bad Request', 'That kubeconfig could not be parsed as YAML', 'invalid-cluster'));

    const err = (await api
      .addCluster({ id: 'c2', kubeconfig: 'not: [yaml' } as Parameters<typeof api.addCluster>[0])
      .then(
        () => null,
        (e: unknown) => e,
      )) as ApiError;

    expect(err.message).toBe('That kubeconfig could not be parsed as YAML');
  });
});
