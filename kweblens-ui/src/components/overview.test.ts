import { describe, expect, it } from 'vitest';

import type { KubeObject } from '../types';
import { OVERVIEW_SECTIONS } from './overview';

// The Environment section encodes a SECURITY decision, so it is pinned here: a value sourced
// from a Secret is rendered as a REFERENCE (secret <name>/<key>) and the secret's value is
// never fetched or displayed. Literal values ARE shown, deliberately — they live in the pod
// spec, which the YAML tab already displays in full, so masking them here would be theatre.

const section = (title: string) => {
  const s = OVERVIEW_SECTIONS.find((x) => x.title === title);
  if (!s) {
    throw new Error(`no section titled ${title}`);
  }
  return s;
};
const rowsOf = (title: string, o: KubeObject): string[][] => {
  const body = section(title).body(o);
  if (body.type !== 'table') {
    throw new Error('expected a table body');
  }
  return body.rows.map((r) => r.map((c) => c.text));
};

const pod: KubeObject = {
  kind: 'Pod',
  metadata: { name: 'db-0', namespace: 'app' },
  spec: {
    containers: [
      {
        name: 'postgres',
        env: [
          { name: 'PGDATA', value: '/var/lib/postgresql/data' },
          { name: 'PASSWORD', valueFrom: { secretKeyRef: { name: 'db-creds', key: 'password' } } },
          { name: 'SETTING', valueFrom: { configMapKeyRef: { name: 'db-config', key: 'tuning' } } },
          { name: 'MY_NODE', valueFrom: { fieldRef: { fieldPath: 'spec.nodeName' } } },
          { name: 'NOTHING' },
        ],
        envFrom: [{ secretRef: { name: 'extra-creds' } }, { configMapRef: { name: 'extra-config' } }],
      },
    ],
    initContainers: [{ name: 'migrate', env: [{ name: 'MODE', value: 'up' }] }],
  },
  status: {
    containerStatuses: [
      {
        name: 'postgres',
        ready: true,
        restartCount: 3,
        state: { running: { startedAt: '2026-07-22T05:34:31Z' } },
        lastState: { terminated: { reason: 'OOMKilled', exitCode: 137 } },
      },
    ],
    initContainerStatuses: [
      { name: 'migrate', ready: true, restartCount: 0, state: { terminated: { reason: 'Completed', exitCode: 0 } } },
    ],
  },
};

describe('Environment section', () => {
  it('shows secret-sourced values as a reference, never a value', () => {
    const rows = rowsOf('Environment', pod);
    expect(rows).toContainEqual(['postgres', 'PASSWORD', 'secret db-creds/password']);
    // whole-map imports name the source object only
    expect(rows).toContainEqual(['postgres', '(all keys)', 'secret extra-creds']);
  });

  it('shows literal, configMap, field and empty sources', () => {
    const rows = rowsOf('Environment', pod);
    expect(rows).toContainEqual(['postgres', 'PGDATA', '/var/lib/postgresql/data']);
    expect(rows).toContainEqual(['postgres', 'SETTING', 'configMap db-config/tuning']);
    expect(rows).toContainEqual(['postgres', 'MY_NODE', 'field spec.nodeName']);
    expect(rows).toContainEqual(['postgres', 'NOTHING', '—']);
    expect(rows).toContainEqual(['postgres', '(all keys)', 'configMap extra-config']);
  });

  it('covers init containers too, and hides itself when there is no env', () => {
    expect(rowsOf('Environment', pod)).toContainEqual(['migrate', 'MODE', 'up']);
    const bare: KubeObject = { kind: 'Pod', metadata: { name: 'p' }, spec: { containers: [{ name: 'c' }] } };
    expect(section('Environment').applies(bare)).toBe(false);
    expect(section('Environment').applies(pod)).toBe(true);
  });
});

describe('Container Status section', () => {
  it('surfaces the previous run reason and exit code (the crashloop diagnostic)', () => {
    const rows = rowsOf('Container Status', pod);
    expect(rows).toContainEqual(['postgres', 'Yes', '3', 'Running since 2026-07-22T05:34:31Z', 'OOMKilled (exit 137)']);
  });

  it('includes init containers and renders terminated state', () => {
    expect(rowsOf('Container Status', pod)).toContainEqual(['migrate', 'Yes', '0', 'Completed (exit 0)', '—']);
  });
});
