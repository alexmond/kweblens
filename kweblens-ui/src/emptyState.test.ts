import { describe, expect, it } from 'vitest';

import {
  attentionEmpty,
  clusterListEmpty,
  directoryEmpty,
  helmValuesEmpty,
  metricSeriesEmpty,
  noChangesEmpty,
  noMatchEmpty,
  nodePodsEmpty,
  paletteEmpty,
  resourceListEmpty,
  warningsEmpty,
} from './emptyState';

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

// ---- The rule, checked once per surface ------------------------------------------------
//
// Every builder added by the R3 sweep gets the same two cases — silent while in flight,
// silent after a failure — because that is the one thing they must all agree on, and one
// pane getting it wrong is what each of the bugs below actually was.

describe('every pane keeps quiet until its request has answered', () => {
  const cases: [string, (s: { loading: boolean; failed: boolean }) => unknown][] = [
    ['attentionEmpty', (s) => attentionEmpty({ ...s, count: 0, unavailable: [], clean: 'All good.' })],
    ['warningsEmpty', (s) => warningsEmpty({ ...s, count: 0, namespace: null })],
    ['nodePodsEmpty', (s) => nodePodsEmpty({ ...s, count: 0, node: 'node-1' })],
    ['directoryEmpty', (s) => directoryEmpty({ ...s, count: 0, path: '/etc' })],
    ['helmValuesEmpty', (s) => helmValuesEmpty({ ...s, blank: true, release: 'billing' })],
    ['metricSeriesEmpty', (s) => metricSeriesEmpty({ ...s, available: true, points: 0 })],
    ['paletteEmpty', (s) => paletteEmpty({ ...s, count: 0, query: 'nginx' })],
  ];

  it.each(cases)('%s says nothing while loading', (_name, build) => {
    expect(build({ loading: true, failed: false })).toBeNull();
  });

  it.each(cases)('%s says nothing after a failure', (_name, build) => {
    // The pane's error notice is the message. A second, confident "there is nothing here"
    // underneath it contradicts it — PR #306's rule, applied to the empty half.
    expect(build({ loading: false, failed: true })).toBeNull();
  });

  it.each(cases)('%s does say something once the answer is in and it is empty', (_name, build) => {
    expect(build({ loading: false, failed: false })).not.toBeNull();
  });
});

describe('attentionEmpty', () => {
  const base = { loading: false, failed: false, count: 0, unavailable: [] as string[], clean: 'Every claim is bound.' };

  it('says nothing when there are rows to show', () => {
    expect(attentionEmpty({ ...base, count: 3 })).toBeNull();
  });

  it("uses the category's own all-clear when every kind was checked", () => {
    expect(attentionEmpty(base)?.title).toBe('Every claim is bound.');
  });

  it('withdraws the all-clear when a kind could not be listed, and names it', () => {
    // A category overview builds one verdict out of several per-kind checks, so an
    // all-clear can otherwise be assembled from checks that never ran.
    const copy = attentionEmpty({ ...base, unavailable: ['Persistent Volumes', 'Storage Classes'] });
    expect(copy?.title).not.toContain('bound');
    expect(copy?.title).toBe('Nothing to report from the checks that ran');
    expect(copy?.body).toContain('Persistent Volumes');
    expect(copy?.body).toContain('Storage Classes');
  });
});

describe('warningsEmpty', () => {
  const base = { loading: false, failed: false, count: 0, namespace: null };

  it('names the namespace when one is filtering, so the zero is readable', () => {
    expect(warningsEmpty(base)?.body).not.toContain(' in ');
    expect(warningsEmpty({ ...base, namespace: 'prod' })?.body).toContain('in prod');
  });

  it('says nothing when there are warnings', () => {
    expect(warningsEmpty({ ...base, count: 2 })).toBeNull();
  });
});

describe('nodePodsEmpty', () => {
  it('names the node and offers the usual reason', () => {
    const copy = nodePodsEmpty({ loading: false, failed: false, count: 0, node: 'worker-3' });
    expect(copy?.title).toContain('worker-3');
    expect(copy?.body).toContain('cordoned');
  });
});

describe('directoryEmpty', () => {
  it('names the path — the browser walks, and "empty" alone leaves the reader guessing where', () => {
    expect(directoryEmpty({ loading: false, failed: false, count: 0, path: '/var/run/secrets' })?.title).toBe(
      '/var/run/secrets is empty',
    );
  });

  it('says nothing when the directory has entries', () => {
    expect(directoryEmpty({ loading: false, failed: false, count: 4, path: '/etc' })).toBeNull();
  });
});

describe('helmValuesEmpty', () => {
  const base = { loading: false, failed: false, blank: true, release: 'billing' };

  it('names the release and says the defaults are what is running', () => {
    expect(helmValuesEmpty(base)?.title).toContain('billing');
    expect(helmValuesEmpty(base)?.body).toContain('chart defaults');
  });

  it('says nothing when the release has values of its own', () => {
    expect(helmValuesEmpty({ ...base, blank: false })).toBeNull();
  });
});

describe('metricSeriesEmpty', () => {
  const base = { loading: false, failed: false, available: true, points: 0 };

  it('tells a missing backend apart from a backend with nothing to say', () => {
    expect(metricSeriesEmpty({ ...base, available: false })?.title).toBe('No metrics backend');
    expect(metricSeriesEmpty(base)?.title).toBe('No samples in the last hour');
  });

  it('never blames a missing backend for a failed request', () => {
    // The regression this pins: the `.catch` wrote `{ available: false }`, so every timeout
    // rendered as "Graphs need a Prometheus / VictoriaMetrics backend" — a confident claim
    // about the operator's cluster produced by an error nobody read.
    expect(metricSeriesEmpty({ ...base, failed: true, available: false })).toBeNull();
  });

  it('says nothing when there is a line to draw', () => {
    expect(metricSeriesEmpty({ ...base, points: 60 })).toBeNull();
  });
});

describe('paletteEmpty', () => {
  const base = { loading: false, failed: false, count: 0, query: 'nginx' };

  it('quotes the query and names both things the palette searches', () => {
    expect(paletteEmpty(base)?.title).toBe('No command or object matches “nginx”');
  });

  it('never claims "no match" over a search that failed', () => {
    expect(paletteEmpty({ ...base, failed: true })).toBeNull();
  });

  it('says nothing while there are rows', () => {
    expect(paletteEmpty({ ...base, count: 5 })).toBeNull();
  });
});

describe('noChangesEmpty', () => {
  it('is unconditional — both documents are already in hand, so there is no state to guard', () => {
    expect(noChangesEmpty().title).toBe('No changes');
    expect(noChangesEmpty().action).toBeNull();
  });
});
