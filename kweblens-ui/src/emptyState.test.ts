import { describe, expect, it } from 'vitest';

import { clusterListEmpty, noMatchEmpty, resourceListEmpty } from './emptyState';

const base = { loaded: true, failed: false, count: 0, canWrite: true };

describe('clusterListEmpty', () => {
  it('says nothing before the first response', () => {
    // Zero clusters is not a fact until the fetch answers. Claiming it mid-flight is a wrong
    // answer that corrects itself, which reads worse than a blank moment.
    expect(clusterListEmpty({ ...base, loaded: false })).toBeNull();
  });

  it('says nothing when the fetch failed', () => {
    // A failed fetch leaves the list empty too, and "none are registered" is then false —
    // the error notice is the message.
    expect(clusterListEmpty({ ...base, failed: true })).toBeNull();
  });

  it('says nothing once there is a cluster', () => {
    expect(clusterListEmpty({ ...base, count: 1 })).toBeNull();
  });

  it('names the two ways a cluster gets registered, as the server does', () => {
    // The server already logs "No clusters registered — set kweblens.clusters[*] or provide
    // a kubeconfig." (ClusterBootstrap). GH#298 was that the browser said none of it.
    const copy = clusterListEmpty(base);
    expect(copy?.title).toBe('No clusters registered');
    expect(copy?.body).toContain('kweblens.clusters[*]');
    expect(copy?.body).toContain('kubeconfig');
  });

  it('offers Add cluster to a writer', () => {
    expect(clusterListEmpty(base)?.action).toEqual({ label: 'Add cluster', kind: 'add-cluster' });
  });

  it('offers the login instead when the reader cannot write', () => {
    // Adding a cluster is a POST, and every non-GET needs the admin login — offering the
    // button to a signed-out reader would be an action that 403s.
    const copy = clusterListEmpty({ ...base, canWrite: false });
    expect(copy?.action).toEqual({ label: 'Sign in', kind: 'sign-in' });
    expect(copy?.body).toContain('admin login');
  });
});

describe('noMatchEmpty', () => {
  it('quotes the query so the reader can see what was filtered on', () => {
    expect(noMatchEmpty('  prod ', 'cluster').title).toBe('No cluster matches “prod”');
  });

  it('offers no action — clearing the filter is the exit, and it is already on screen', () => {
    expect(noMatchEmpty('prod', 'cluster').action).toBeNull();
  });
});

describe('resourceListEmpty', () => {
  const base = { loading: false, failed: false, total: 0, query: '', scope: null, noun: 'Pods', namespace: null };

  it('claims nothing while the list is still loading', () => {
    expect(resourceListEmpty({ ...base, loading: true })).toBeNull();
  });

  it('claims nothing when the fetch failed — the shell is already showing why', () => {
    // "There are no Pods here" underneath "403 … cannot list pods" would contradict it.
    expect(resourceListEmpty({ ...base, failed: true })).toBeNull();
  });

  it('says the cluster has none, and names the namespace when one is filtering', () => {
    expect(resourceListEmpty(base)?.title).toBe('No pods in this cluster');
    expect(resourceListEmpty({ ...base, namespace: 'prod' })?.title).toBe('No pods in prod');
  });

  it('blames the search box when the search box is what hid them', () => {
    const copy = resourceListEmpty({ ...base, total: 137, query: ' nginx ' });
    expect(copy?.title).toBe('No pods match “nginx”');
    expect(copy?.body).toContain('137 loaded');
  });

  it('blames the Helm scope when that is what hid them, and says how to undo it', () => {
    const copy = resourceListEmpty({ ...base, total: 137, scope: 'billing' });
    expect(copy?.title).toBe('The Helm release billing manages no pods');
    expect(copy?.body).toContain('Clear it');
  });

  it('mentions both when both are narrowing', () => {
    const copy = resourceListEmpty({ ...base, total: 137, query: 'nginx', scope: 'billing' });
    expect(copy?.title).toContain('nginx');
    expect(copy?.body).toContain('billing');
  });
});
