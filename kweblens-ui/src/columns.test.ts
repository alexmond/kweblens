import { describe, expect, it } from 'vitest';

import { age, columnsFor, printerColumnDefs, readyTone, statusTone } from './columns';
import type { KubeObject, PrinterColumn } from './types';

describe('statusTone', () => {
  it('classifies status vocab into tones', () => {
    expect(statusTone('Running')).toBe('ok');
    expect(statusTone('Pending')).toBe('warn');
    expect(statusTone('CrashLoopBackOff')).toBe('err');
    expect(statusTone('')).toBe('');
    expect(statusTone('—')).toBe('');
    expect(statusTone('Whatever')).toBe('');
  });
});

describe('readyTone', () => {
  it('classifies ready ratios', () => {
    expect(readyTone('2/2')).toBe('ok');
    expect(readyTone('0/2')).toBe('err');
    expect(readyTone('1/2')).toBe('warn');
    expect(readyTone('0/0')).toBe('');
    expect(readyTone('not-a-ratio')).toBe('');
  });
});

describe('age', () => {
  const ago = (ms: number) => new Date(Date.now() - ms).toISOString();

  it('formats an ISO timestamp into a compact age', () => {
    expect(age(undefined)).toBe('—');
    expect(age('not-a-date')).toBe('—');
    expect(age(ago(5_000))).toMatch(/^\d+s$/);
    expect(age(ago(5 * 60_000))).toBe('5m');
    expect(age(ago(3 * 3_600_000))).toBe('3h');
    expect(age(ago(2 * 86_400_000))).toBe('2d');
  });
});

describe('columnsFor', () => {
  const pod: KubeObject = {
    kind: 'Pod',
    metadata: { name: 'p', namespace: 'default', creationTimestamp: new Date().toISOString() },
    spec: { nodeName: 'node-1', containers: [{ name: 'c' }], ports: [{ port: 80, protocol: 'TCP' }] },
    status: { phase: 'Running', containerStatuses: [{ name: 'c', ready: true, restartCount: 2 }] },
  };

  it('returns column defs whose render() never throws, across kinds', () => {
    expect(columnsFor('pods').length).toBeGreaterThan(0);
    const kinds = [
      'pods',
      'deployments',
      'statefulsets',
      'daemonsets',
      'replicasets',
      'jobs',
      'cronjobs',
      'nodes',
      'services',
      'ingresses',
      'configmaps',
      'secrets',
      'namespaces',
      'persistentvolumeclaims',
      'persistentvolumes',
      'storageclasses',
      'events',
      'customresourcedefinitions',
    ];
    for (const id of kinds) {
      for (const c of columnsFor(id)) {
        expect(() => c.render(pod)).not.toThrow();
      }
    }
  });

  it('returns [] for an unknown kind', () => {
    expect(columnsFor('does-not-exist')).toEqual([]);
  });
});

describe('printerColumnDefs', () => {
  it('builds defs and resolves simple + filtered json paths', () => {
    const cols: PrinterColumn[] = [
      { name: 'Phase', type: 'string', jsonPath: '.status.phase' },
      { name: 'Age', type: 'date', jsonPath: '.metadata.creationTimestamp' },
      { name: 'Ready', type: 'string', jsonPath: '.status.conditions[?(@.type == "Ready")].status' },
    ];
    const defs = printerColumnDefs(cols);
    expect(defs.map((d) => d.header)).toEqual(['Phase', 'Age', 'Ready']);
    expect(defs[0].render({ status: { phase: 'Running' } })).toBe('Running');
    expect(defs[0].render({})).toBe('—');
    expect(defs[1].render({ metadata: { creationTimestamp: new Date().toISOString() } })).toMatch(/^\d+[smhd]$/);
    expect(defs[2].render({ status: { conditions: [{ type: 'Ready', status: 'True' }] } })).toBe('True');
  });
});
