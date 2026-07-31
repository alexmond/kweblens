import { describe, expect, it } from 'vitest';

import { filterRows, idProblem, STATIC_LOCK_REASON, summarise, toRows } from './clustersPage';
import type { ClusterInfo } from './types';

const CLUSTERS: ClusterInfo[] = [
  { id: 'default', name: 'default', masterUrl: 'https://198.51.100.1:6443/', origin: 'STATIC' },
  { id: 'prod-eu', name: 'Production EU', masterUrl: 'https://198.51.100.2:6443/', origin: 'RUNTIME' },
];

describe('toRows', () => {
  it('marks runtime clusters editable and static ones locked, with a reason', () => {
    const [staticRow, runtimeRow] = toRows(CLUSTERS, null);
    expect(staticRow.editable).toBe(false);
    expect(staticRow.lockedReason).toBe(STATIC_LOCK_REASON);
    expect(runtimeRow.editable).toBe(true);
    expect(runtimeRow.lockedReason).toBeNull();
  });

  it('treats a missing origin as STATIC, not editable', () => {
    // An older server that does not report origin predates runtime cluster management.
    // Defaulting to editable would render controls whose requests then fail.
    const [row] = toRows([{ id: 'x', name: 'x', masterUrl: 'https://198.51.100.9' }], null);
    expect(row.origin).toBe('STATIC');
    expect(row.editable).toBe(false);
  });

  it('flags the cluster currently being viewed', () => {
    expect(toRows(CLUSTERS, 'prod-eu').map((r) => r.current)).toEqual([false, true]);
  });
});

describe('filterRows', () => {
  const rows = toRows(CLUSTERS, null);

  it('matches on name, id and API server', () => {
    expect(filterRows(rows, 'Production').map((r) => r.id)).toEqual(['prod-eu']);
    expect(filterRows(rows, 'default').map((r) => r.id)).toEqual(['default']);
    expect(filterRows(rows, '100.2').map((r) => r.id)).toEqual(['prod-eu']);
  });

  it('ignores case and surrounding space', () => {
    expect(filterRows(rows, '  PRODUCTION  ').map((r) => r.id)).toEqual(['prod-eu']);
  });

  it('returns everything for an empty query', () => {
    expect(filterRows(rows, '   ')).toHaveLength(2);
  });
});

describe('summarise', () => {
  it('says so when nothing is configured', () => {
    expect(summarise([])).toBe('No clusters configured.');
  });

  it('distinguishes all-static, all-runtime and mixed', () => {
    const rows = toRows(CLUSTERS, null);
    expect(summarise(rows)).toContain('1 added at runtime');
    expect(summarise(rows.filter((r) => !r.editable))).toContain('all declared in configuration');
    expect(summarise(rows.filter((r) => r.editable))).toContain('all added at runtime');
  });

  it('does not say "1 clusters"', () => {
    expect(summarise(toRows([CLUSTERS[0]], null))).toContain('1 cluster,');
  });
});

describe('idProblem', () => {
  it('accepts an ordinary id', () => {
    expect(idProblem('prod-eu-1', [])).toBeNull();
  });

  it('rejects an empty id', () => {
    expect(idProblem('  ', [])).toContain('required');
  });

  it('rejects characters that a URL, a filename or a Secret key would not survive', () => {
    // The id is used in all three places, so the usable set is the intersection.
    for (const bad of ['Prod EU', 'prod/eu', 'prod_eu', '-lead', 'trail-', 'ÜML']) {
      expect(idProblem(bad, []), bad).not.toBeNull();
    }
  });

  it('rejects a duplicate', () => {
    expect(idProblem('default', ['default'])).toContain('already exists');
  });
});
