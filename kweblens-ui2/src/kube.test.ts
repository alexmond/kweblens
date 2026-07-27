import { describe, expect, it } from 'vitest';

import {
  ageToSeconds,
  containerNames,
  gib,
  initials,
  objKey,
  objName,
  objNs,
  objSpec,
  objStatus,
  objectPorts,
  parseCpuCores,
  parseMemBytes,
  stripManagedFields,
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

  it('initials builds a two-letter avatar', () => {
    expect(initials('default')).toBe('DE');
    expect(initials('x')).toBe('X');
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
