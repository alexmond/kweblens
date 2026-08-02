import { describe, expect, it } from 'vitest';

import { mergeCommands, objectCommands, scopeNotes, shouldSearch, type Command } from './commandPalette';
import type { SearchHit, SearchResponse } from './types';

const hit = (over: Partial<SearchHit> = {}): SearchHit => ({
  resourceId: 'deployments',
  kind: 'Deployment',
  namespace: 'ci',
  name: 'sonarqube',
  status: null,
  age: '3d',
  score: 991,
  ...over,
});

const nav = (n: number): Command[] =>
  Array.from({ length: n }, (_, i) => ({
    key: `nav:${i}`,
    kind: 'nav' as const,
    label: `k${i}`,
    hint: '',
    target: '',
  }));

const response = (over: Partial<SearchResponse> = {}): SearchResponse => ({
  query: 'sonar',
  namespace: null,
  hits: [hit()],
  total: 1,
  truncated: false,
  searchedKinds: ['Pods', 'Deployments'],
  unsearchedKinds: [],
  skippedKinds: [],
  tookMs: 12,
  ...over,
});

describe('shouldSearch', () => {
  it('waits for a second character', () => {
    // One request lists every kind in the bounded set, so a single letter is not worth it —
    // and would match nearly everything anyway.
    expect(shouldSearch('s')).toBe(false);
    expect(shouldSearch(' s ')).toBe(false);
    expect(shouldSearch('so')).toBe(true);
  });
});

describe('objectCommands', () => {
  it('carries the object’s OWN kind and namespace, not the open list’s', () => {
    // A bare name is not addressable: `postgresql` exists in every namespace a chart was
    // installed into. Addressing by the list's kind is the GH#187 bug.
    const [row] = objectCommands([hit({ resourceId: 'pods', kind: 'Pod', namespace: 'staging', name: 'pg-0' })]);
    expect(row.target).toBe('pods');
    expect(row.hit?.namespace).toBe('staging');
    expect(row.hint).toBe('Pod · staging');
  });

  it('says cluster-scoped rather than showing an empty namespace', () => {
    const [row] = objectCommands([hit({ resourceId: 'nodes', kind: 'Node', namespace: null, name: 'node-1' })]);
    expect(row.hint).toBe('Node · cluster-scoped');
  });

  it('keys rows by kind + namespace + name so colliding names stay distinct', () => {
    const rows = objectCommands([
      hit({ resourceId: 'services', kind: 'Service', namespace: 'a', name: 'postgresql' }),
      hit({ resourceId: 'services', kind: 'Service', namespace: 'b', name: 'postgresql' }),
    ]);
    expect(new Set(rows.map((r) => r.key)).size).toBe(2);
  });
});

describe('mergeCommands', () => {
  it('caps navigation rows once objects arrive so objects are still visible', () => {
    const merged = mergeCommands(nav(20), objectCommands([hit(), hit({ name: 'sonarqube-db' })]));
    expect(merged.filter((c) => c.kind === 'nav')).toHaveLength(5);
    expect(merged.filter((c) => c.kind === 'object')).toHaveLength(2);
  });

  it('lifts the cap when there is nothing to make room for', () => {
    expect(mergeCommands(nav(20), [])).toHaveLength(20);
  });

  it('never interleaves — arriving results only append', () => {
    // Object hits land ~200ms after the keystroke. If they were merged by score the armed
    // row would change identity under the reader's fingers just as they press Enter.
    const merged = mergeCommands(nav(3), objectCommands([hit()]));
    expect(merged.map((c) => c.kind)).toEqual(['nav', 'nav', 'nav', 'object']);
  });

  it('honours the overall limit', () => {
    expect(
      mergeCommands(nav(20), objectCommands(Array.from({ length: 40 }, (_, i) => hit({ name: `d${i}` }))), 30),
    ).toHaveLength(30);
  });
});

describe('scopeNotes', () => {
  it('reports truncation against the REAL total, not the cap (GH#157)', () => {
    const notes = scopeNotes(response({ total: 137, truncated: true }), 20);
    expect(notes[0].text).toBe('showing 20 of 137');
  });

  it('states the match count when nothing was truncated', () => {
    expect(scopeNotes(response({ total: 1 }), 1)[0].text).toBe('1 match');
    expect(scopeNotes(response({ total: 4 }), 4)[0].text).toBe('4 matches');
  });

  it('names how many kinds were NOT searched, and which', () => {
    // The load-bearing half: a result set that omits CRDs while looking complete is the
    // failure mode. The count is on screen; the names are in the tooltip.
    const notes = scopeNotes(response({ unsearchedKinds: ['Replica Sets', 'Certificates'] }), 1);
    const unsearched = notes.find((n) => n.text.includes('not searched'));
    expect(unsearched?.text).toBe('2 kinds not searched');
    expect(unsearched?.detail).toContain('Replica Sets, Certificates');
    expect(unsearched?.detail).toContain('2 kinds');
  });

  it('says nothing about unsearched kinds when there are none', () => {
    expect(scopeNotes(response(), 1).some((n) => n.text.includes('not searched'))).toBe(false);
  });

  it('surfaces the namespace filter as a scope, not silently', () => {
    expect(scopeNotes(response({ namespace: 'ci' }), 1).some((n) => n.text === 'in ci')).toBe(true);
  });

  it('reports kinds that could not be listed rather than counting them as empty', () => {
    // An empty result for a kind RBAC refused is a claim about the cluster that is false.
    const notes = scopeNotes(response({ skippedKinds: [{ kind: 'Secrets', reason: 'forbidden' }] }), 1);
    const skipped = notes.find((n) => n.text.includes('could not be listed'));
    expect(skipped?.detail).toBe('Secrets: forbidden');
  });

  it('is empty before the first response', () => {
    expect(scopeNotes(null, 0)).toEqual([]);
  });
});
