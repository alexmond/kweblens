import { describe, expect, it } from 'vitest';

import type { KubeObject } from '../types';
import { WORKLOAD_KINDS, cronJobState, jobState, tally } from './workloadHealth';

// These pin two bugs that were live: a RUNNING Job was reported unhealthy, and a CronJob could
// never be unhealthy. The in-between states are the whole point — anyone can get "finished"
// and "failed" right; the failures were in "still running" and "deliberately off".

const kind = (id: string) => {
  const k = WORKLOAD_KINDS.find((x) => x.id === id);
  if (!k) {
    throw new Error(`no kind ${id}`);
  }
  return k;
};

const job = (over: Record<string, unknown>): KubeObject => ({
  kind: 'Job',
  metadata: { name: 'j' },
  spec: {},
  ...over,
});

describe('Job health', () => {
  it('does NOT flag a running job — the bug this fixes', () => {
    // succeeded is 0 for the entire duration of a running Job, so the old
    // `succeeded > 0` predicate turned every in-flight Job red.
    expect(jobState(job({ status: { active: 1, succeeded: 0 } }))).toBe('ok');
  });

  it('does not flag a job that has not started yet', () => {
    expect(jobState(job({ status: {} }))).toBe('ok');
  });

  it('flags a failed job', () => {
    expect(jobState(job({ status: { failed: 3, conditions: [{ type: 'Failed', status: 'True' }] } }))).toBe(
      'attention',
    );
  });

  it('treats a completed job as ok, by condition or by count', () => {
    expect(jobState(job({ status: { conditions: [{ type: 'Complete', status: 'True' }] } }))).toBe('ok');
    expect(jobState(job({ status: { succeeded: 1 } }))).toBe('ok');
  });

  it('ignores a condition that is present but False', () => {
    expect(jobState(job({ status: { conditions: [{ type: 'Failed', status: 'False' }] } }))).toBe('ok');
  });
});

describe('CronJob health', () => {
  it('reports a suspended cronjob as SUSPENDED, not as broken', () => {
    // A suspended CronJob is a deliberate operator choice. Colouring it red alongside real
    // failures is how a health signal earns its way into being ignored.
    expect(cronJobState({ kind: 'CronJob', metadata: {}, spec: { suspend: true } })).toBe('suspended');
  });

  it('reports a normal cronjob as ok — and was previously ALWAYS ok, which hid suspension', () => {
    expect(cronJobState({ kind: 'CronJob', metadata: {}, spec: { suspend: false } })).toBe('ok');
    expect(cronJobState({ kind: 'CronJob', metadata: {}, spec: {} })).toBe('ok');
  });
});

describe('replica-based kinds', () => {
  const dep = (replicas: number | undefined, ready: number | undefined): KubeObject => ({
    kind: 'Deployment',
    metadata: { name: 'd' },
    spec: { replicas },
    status: { readyReplicas: ready },
  });

  it('treats scaled-to-zero as ok, deliberately', () => {
    // Intentionally scaled down is not failing; flagging it would light up every idle workload.
    expect(kind('deployments').state(dep(0, undefined))).toBe('ok');
  });

  it('flags a partially-ready deployment', () => {
    expect(kind('deployments').state(dep(3, 2))).toBe('attention');
    expect(kind('deployments').state(dep(3, 3))).toBe('ok');
  });

  it('uses the DaemonSet vocabulary for daemonsets', () => {
    const ds = (desired: number, ready: number): KubeObject => ({
      kind: 'DaemonSet',
      metadata: { name: 'ds' },
      status: { desiredNumberScheduled: desired, numberReady: ready },
    });
    expect(kind('daemonsets').state(ds(4, 4))).toBe('ok');
    expect(kind('daemonsets').state(ds(4, 3))).toBe('attention');
  });
});

describe('tally', () => {
  it('counts each state separately so suspended is not conflated with broken', () => {
    const cronjobs = kind('cronjobs');
    const t = tally(
      [
        { kind: 'CronJob', metadata: { name: 'a' }, spec: { suspend: true } },
        { kind: 'CronJob', metadata: { name: 'b' }, spec: {} },
        { kind: 'CronJob', metadata: { name: 'c' }, spec: {} },
      ],
      cronjobs,
    );
    expect(t).toEqual({ total: 3, ok: 2, attention: 0, suspended: 1 });
  });

  it('handles an empty list', () => {
    expect(tally([], kind('pods'))).toEqual({ total: 0, ok: 0, attention: 0, suspended: 0 });
  });
});
