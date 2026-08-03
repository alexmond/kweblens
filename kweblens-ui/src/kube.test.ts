import { describe, expect, it } from 'vitest';

import {
  ageToSeconds,
  containerNames,
  eventObjectKind,
  gib,
  initials,
  needsFullObject,
  objKey,
  objName,
  objNs,
  objSpec,
  objStatus,
  objectPorts,
  parseCpuCores,
  parseMemBytes,
  stripManagedFields,
  tileLabels,
  toNum,
} from './kube';

describe('kube accessors', () => {
  it('objSpec returns spec or {}', () => {
    expect(objSpec({ spec: { a: 1 } })).toEqual({ a: 1 });
    expect(objSpec({})).toEqual({});
  });

  it('objStatus returns status or {}', () => {
    expect(objStatus({ status: { phase: 'Running' } })).toEqual({ phase: 'Running' });
    expect(objStatus({})).toEqual({});
  });

  it('toNum coerces non-numbers to 0', () => {
    expect(toNum(5)).toBe(5);
    expect(toNum('x')).toBe(0);
    expect(toNum(undefined)).toBe(0);
    expect(toNum(null)).toBe(0);
  });

  it('objName/objNs/objKey read metadata defensively', () => {
    const o = { metadata: { name: 'web', namespace: 'prod' } };
    expect(objName(o)).toBe('web');
    expect(objNs(o)).toBe('prod');
    expect(objKey(o)).toBe('prod/web');
    expect(objName({})).toBe('');
    expect(objNs({})).toBeUndefined();
    expect(objKey({ metadata: { name: 'n' } })).toBe('/n');
  });

  it('initials builds a two-letter avatar, splitting on word boundaries', () => {
    expect(initials('default')).toBe('DE');
    expect(initials('x')).toBe('X');
    // The whole point of splitting: a shared prefix is the normal naming convention, and
    // `id.slice(0, 2)` made every member of one collapse to the same two letters.
    expect(initials('prod-eu')).toBe('PE');
    expect(initials('prod-us')).toBe('PU');
    expect(initials('k3s_test')).toBe('KT');
  });

  it('tileLabels never gives two clusters the same label', () => {
    // The acceptance criterion from #252, stated directly: five clusters that all rendered
    // as "KI" before, plus the shapes word-splitting alone cannot separate.
    const ids = ['kind-a', 'kind-a2', 'kind', 'kinder', 'kind-a3', 'prod-eu', 'prod-us', 'default'];
    const labels = tileLabels(ids);
    expect(labels.size).toBe(ids.length);
    expect(new Set(labels.values()).size).toBe(ids.length);
    for (const l of labels.values()) {
      expect(l).toHaveLength(2);
    }
  });

  it('tileLabels keeps the natural label for the first of a colliding set', () => {
    const labels = tileLabels(['kind', 'kinetic']);
    expect(labels.get('kind')).toBe('KI');
    expect(labels.get('kinetic')).not.toBe('KI');
  });

  it('tileLabels does not relabel existing clusters when one is appended', () => {
    // A rail whose letters shuffle when you add a cluster is worse than one with
    // duplicates, so only the later colliding id may change.
    const before = tileLabels(['kind', 'kinetic']);
    const after = tileLabels(['kind', 'kinetic', 'kindly']);
    expect(after.get('kind')).toBe(before.get('kind'));
    expect(after.get('kinetic')).toBe(before.get('kinetic'));
    expect(new Set(after.values()).size).toBe(3);
  });

  it('tileLabels still separates ids that differ only in punctuation', () => {
    const labels = tileLabels(['a-b', 'a.b', 'a_b']);
    expect(new Set(labels.values()).size).toBe(3);
  });

  it('containerNames lists non-empty names', () => {
    expect(containerNames({ spec: { containers: [{ name: 'a' }, { name: '' }, { name: 'b' }] } })).toEqual(['a', 'b']);
    expect(containerNames({})).toEqual([]);
  });

  it('objectPorts collects service ports or container ports', () => {
    expect(objectPorts('Service', { spec: { ports: [{ port: 80 }, { port: 443 }, {}] } })).toEqual([80, 443]);
    expect(
      objectPorts('Pod', {
        spec: { containers: [{ ports: [{ containerPort: 8080 }] }, { ports: [{ containerPort: 8080 }] }] },
      }),
    ).toEqual([8080]);
    expect(objectPorts('Pod', {})).toEqual([]);
  });

  it('parseCpuCores handles m/u/n suffixes', () => {
    expect(parseCpuCores('2')).toBe(2);
    expect(parseCpuCores('250m')).toBeCloseTo(0.25);
    expect(parseCpuCores('500u')).toBeCloseTo(0.0005);
    expect(parseCpuCores('1000000000n')).toBeCloseTo(1);
    expect(parseCpuCores(undefined)).toBe(0);
    expect(parseCpuCores('nope')).toBe(0);
  });

  it('parseMemBytes handles binary + decimal units', () => {
    expect(parseMemBytes('1Ki')).toBe(1024);
    expect(parseMemBytes('1Mi')).toBe(1024 ** 2);
    expect(parseMemBytes('2Gi')).toBe(2 * 1024 ** 3);
    expect(parseMemBytes('1M')).toBe(1e6);
    expect(parseMemBytes(undefined)).toBe(0);
    expect(parseMemBytes('bad')).toBe(0);
  });

  it('gib formats bytes as Gi', () => {
    expect(gib(1024 ** 3)).toBe('1.0Gi');
  });

  it('ageToSeconds parses compact ages', () => {
    expect(ageToSeconds('45s')).toBe(45);
    expect(ageToSeconds('5m')).toBe(300);
    expect(ageToSeconds('2h')).toBe(7200);
    expect(ageToSeconds('3d')).toBe(259200);
    expect(ageToSeconds('nope')).toBe(0);
  });

  it('stripManagedFields drops the managedFields block', () => {
    const yaml = [
      'metadata:',
      '  name: x',
      '  managedFields:',
      '  - manager: a',
      '    op: Update',
      'spec:',
      '  n: 1',
    ].join('\n');
    const out = stripManagedFields(yaml);
    expect(out).not.toContain('managedFields');
    expect(out).not.toContain('manager: a');
    expect(out).toContain('name: x');
    expect(out).toContain('spec:');
  });
});

// GH#276. The predicate that decides whether opening a drawer costs an extra request. Both
// halves matter: a false negative renders a Secret's Data section as dashes forever, and a
// false positive puts a request on every Pod drawer for nothing.
describe('needsFullObject', () => {
  it('is true when a data map has a value the list did not ship', () => {
    expect(needsFullObject({ kind: 'Secret', data: { password: null } })).toBe(true);
    expect(needsFullObject({ kind: 'ConfigMap', data: { 'app.conf': null, 'other.conf': null } })).toBe(true);
    expect(needsFullObject({ kind: 'Secret', stringData: { 'api-key': null } })).toBe(true);
    expect(needsFullObject({ kind: 'ConfigMap', binaryData: { blob: null } })).toBe(true);
  });

  it('is false for the kinds nothing was stripped from — they must not pay for a re-fetch', () => {
    expect(needsFullObject({ kind: 'Pod', spec: { containers: [] } })).toBe(false);
    expect(needsFullObject({ kind: 'Deployment', metadata: { name: 'web' } })).toBe(false);
    expect(needsFullObject({})).toBe(false);
  });

  it('is false once the whole object has been fetched, so it cannot loop', () => {
    expect(needsFullObject({ kind: 'Secret', data: { 'ca.crt': 'c3VwZXJzZWNyZXQ=' } })).toBe(false);
  });

  it('is false for an empty data map, and for a value that is genuinely the empty string', () => {
    expect(needsFullObject({ kind: 'ConfigMap', data: {} })).toBe(false);
    expect(needsFullObject({ kind: 'ConfigMap', data: { blank: '' } })).toBe(false);
  });
});

describe('eventObjectKind', () => {
  it('takes the kind from the leading segment', () => {
    expect(eventObjectKind('Pod/web-7d9f')).toBe('Pod');
  });

  it('keeps the first segment when the name itself contains a slash', () => {
    expect(eventObjectKind('Ingress/team/site')).toBe('Ingress');
  });

  it('returns null rather than guessing when there is no kind', () => {
    // An inert row is better than one that navigates somewhere arbitrary.
    expect(eventObjectKind('')).toBeNull();
    expect(eventObjectKind('/orphan')).toBeNull();
    expect(eventObjectKind(null)).toBeNull();
  });
});
