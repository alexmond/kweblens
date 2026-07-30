// Per-kind health for the Workloads overview cards.
//
// Extracted from WorkloadsOverview.vue so the predicates are testable without a DOM — the same
// split already used for overview.ts, containerSquares.ts and relations.ts. The predicates are
// the part worth testing: two of them were wrong, and a wrong health signal is worse than none.
import { objSpec, objStatus, toNum } from '../kube';
import type { KubeObject } from '../types';

/**
 * Three states, not a boolean.
 *
 * `suspended` exists because a suspended CronJob is a deliberate operator choice, not a fault.
 * Colouring it red alongside genuine failures is how a health signal earns its way into being
 * ignored — which then makes it useless when something is actually broken.
 */
export type WorkloadState = 'ok' | 'attention' | 'suspended';

export interface WorkloadKind {
  id: string;
  label: string;
  state: (o: KubeObject) => WorkloadState;
}

const num = toNum;

const conditionIsTrue = (o: KubeObject, type: string): boolean => {
  const conditions = objStatus(o).conditions;
  if (!Array.isArray(conditions)) {
    return false;
  }
  return (conditions as Record<string, unknown>[]).some((c) => c.type === type && c.status === 'True');
};

/**
 * Replica-based workloads. Scaled to zero counts as OK on purpose: it is an intentional scale
 * down, not a failure, and flagging it would light up every deliberately-idle workload.
 */
const replicasReady = (o: KubeObject): WorkloadState =>
  num(objStatus(o).readyReplicas) === num(objSpec(o).replicas) ? 'ok' : 'attention';

/**
 * A Job needs attention when it has FAILED — not when it merely has not finished.
 *
 * The previous predicate was `succeeded > 0`, which is false for the entire duration of a
 * running Job, so every in-flight Job was reported unhealthy. That is the false-alarm failure
 * mode: it trains people to ignore the card.
 */
export const jobState = (o: KubeObject): WorkloadState => {
  if (conditionIsTrue(o, 'Failed')) {
    return 'attention';
  }
  if (conditionIsTrue(o, 'Complete') || num(objStatus(o).succeeded) > 0) {
    return 'ok';
  }
  // Active (or not yet started) — in progress, which is not a problem.
  return 'ok';
};

/**
 * A CronJob is `suspended` when it is, and otherwise OK.
 *
 * Deliberately NO "last run failed" heuristic. A CronJob's own status carries only
 * lastScheduleTime / lastSuccessfulTime / active, so inferring failure means comparing
 * timestamps against a parsed cron expression — fragile, and a wrong guess reintroduces exactly
 * the false alarms this issue exists to remove. A failed run surfaces as a failed *Job*, which
 * `jobState` now reports correctly, so the information is not lost — it is reported by the
 * object that actually knows.
 */
export const cronJobState = (o: KubeObject): WorkloadState => (objSpec(o).suspend === true ? 'suspended' : 'ok');

export const WORKLOAD_KINDS: WorkloadKind[] = [
  {
    id: 'pods',
    label: 'Pods',
    state: (o) => {
      const phase = objStatus(o).phase;
      return phase === 'Running' || phase === 'Succeeded' ? 'ok' : 'attention';
    },
  },
  { id: 'deployments', label: 'Deployments', state: replicasReady },
  { id: 'statefulsets', label: 'Stateful Sets', state: replicasReady },
  {
    id: 'daemonsets',
    label: 'Daemon Sets',
    state: (o) => (num(objStatus(o).numberReady) === num(objStatus(o).desiredNumberScheduled) ? 'ok' : 'attention'),
  },
  { id: 'replicasets', label: 'Replica Sets', state: replicasReady },
  { id: 'jobs', label: 'Jobs', state: jobState },
  { id: 'cronjobs', label: 'Cron Jobs', state: cronJobState },
];

export interface KindTally {
  total: number;
  ok: number;
  attention: number;
  suspended: number;
}

export function tally(objects: KubeObject[], kind: WorkloadKind): KindTally {
  const t: KindTally = { total: objects.length, ok: 0, attention: 0, suspended: 0 };
  for (const o of objects) {
    t[kind.state(o)] += 1;
  }
  return t;
}
