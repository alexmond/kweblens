import { describe, expect, it } from 'vitest';

import { buildCommands, filterCommands, score, wrapIndex } from './commandPalette';
import type { ClusterInfo, NavCategory, NavItem } from './types';

const item = (id: string, label: string): NavItem => ({ id, label, kind: label, namespaced: true });

const CLUSTERS: ClusterInfo[] = [
  { id: 'prod-eu', name: 'Production EU', masterUrl: 'https://198.51.100.1' },
  { id: 'prod-us', name: 'Production US', masterUrl: 'https://198.51.100.2' },
  { id: 'default', name: 'default', masterUrl: 'https://198.51.100.3' },
];

const NAV: NavCategory[] = [
  { label: 'Workloads', icon: '', items: [item('pods', 'Pods'), item('replicasets', 'ReplicaSets')] },
  {
    label: 'Custom Resources',
    icon: '',
    items: [],
    subgroups: [{ label: 'cert-manager.io', icon: '', items: [item('crd:certificates', 'Certificates')] }],
  },
];

describe('buildCommands', () => {
  it('includes kinds nested in subgroups', () => {
    // The trap: Custom Resources nests one subgroup per CRD API group, so a hand-rolled
    // flatMap over `items` alone drops every CRD-backed kind. That exact omission made the
    // count badges wrong in #195 — a palette that cannot find a CRD is the same bug.
    const keys = buildCommands([], NAV, null).map((c) => c.key);
    expect(keys).toContain('nav:crd:certificates');
  });

  it('names the API group in the hint so nested kinds are attributable', () => {
    const cert = buildCommands([], NAV, null).find((c) => c.key === 'nav:crd:certificates');
    expect(cert?.hint).toBe('Custom Resources › cert-manager.io');
  });

  it('omits the cluster you are already on', () => {
    const targets = buildCommands(CLUSTERS, [], 'prod-eu')
      .filter((c) => c.kind === 'cluster')
      .map((c) => c.target);
    expect(targets).toEqual(['prod-us', 'default']);
  });

  it('offers the Clusters page even with no cluster and no nav (#298)', () => {
    // Every other row is derived from a cluster, so a zero-cluster install had an empty
    // palette — and the page that fixes that was reachable only from an unlabelled tile.
    const only = buildCommands([], [], null);
    expect(only.map((c) => c.key)).toEqual(['page:clusters']);
    expect(only[0].kind).toBe('page');
  });

  it('keeps the Clusters page below the cluster switches', () => {
    // The palette opens on the switch, which is what it is mostly used for; a constant row
    // must not take that top slot.
    const keys = buildCommands(CLUSTERS, [], null).map((c) => c.key);
    expect(keys.indexOf('page:clusters')).toBe(CLUSTERS.length);
  });

  it('carries the nav item so the caller need not look it up', () => {
    const pods = buildCommands([], NAV, null).find((c) => c.target === 'pods');
    expect(pods?.item?.kind).toBe('Pods');
  });
});

describe('score', () => {
  it('ranks a prefix above a word-boundary match above a subsequence', () => {
    expect(score('pod', 'Pods')).toBeGreaterThan(score('pod', 'Pod Disruption Budgets'));
    expect(score('budget', 'Pod Disruption Budgets')).toBeGreaterThan(score('pdb', 'Pod Disruption Budgets'));
  });

  it('finds an acronym as a subsequence', () => {
    expect(score('rs', 'ReplicaSets')).toBeGreaterThanOrEqual(0);
  });

  it('rejects a non-match', () => {
    expect(score('zzz', 'Pods')).toBe(-1);
  });

  it('prefers the shorter target when both match the same way', () => {
    expect(score('pod', 'Pods')).toBeGreaterThan(score('pod', 'PodTemplatesLonger'));
  });
});

describe('filterCommands', () => {
  const commands = buildCommands(CLUSTERS, NAV, 'default');

  it('distinguishes clusters that share a two-letter prefix', () => {
    // Since #252 the rail labels these PE and PU rather than both PR, so they are no longer
    // identical — but two letters still cannot say which is which without hovering. Typing
    // the name has to disambiguate them.
    const hits = filterCommands(commands, 'Production US');
    expect(hits[0].target).toBe('prod-us');
  });

  it('finds a cluster by id as well as by name', () => {
    expect(filterCommands(commands, 'prod-eu')[0].target).toBe('prod-eu');
  });

  it('ranks a label match above a hint match', () => {
    // "Certificates" is a label; "cert-manager.io" is only a hint. The label is the text
    // being read, so it wins.
    const hits = filterCommands(commands, 'cert');
    expect(hits[0].label).toBe('Certificates');
  });

  it('returns nothing for a query that matches nothing', () => {
    expect(filterCommands(commands, 'qqqq')).toEqual([]);
  });

  it('shows clusters first when the query is empty', () => {
    expect(filterCommands(commands, '')[0].kind).toBe('cluster');
  });

  it('respects the limit', () => {
    expect(filterCommands(commands, '', 2)).toHaveLength(2);
  });
});

describe('wrapIndex', () => {
  it('wraps past the end and before the start', () => {
    expect(wrapIndex(3, 3)).toBe(0);
    expect(wrapIndex(-1, 3)).toBe(2);
  });

  it('survives an empty list', () => {
    // Arrowing around an empty result set must not produce NaN as an index.
    expect(wrapIndex(-1, 0)).toBe(0);
  });
});
