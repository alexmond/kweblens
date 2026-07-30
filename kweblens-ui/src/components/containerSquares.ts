// The Containers column's squares, as data. Kept out of the .vue so it can be tested without a
// DOM — the same split as overview.ts / Overview.vue.
import type { KubeObject } from '../types';

/** How a container participates in the pod lifecycle — drives order, shape and label. */
type ContainerRole = 'init' | 'app' | 'ephemeral';

export interface ContainerSquare {
  key: string;
  role: ContainerRole;
  /** Colour tone: ok · warn · err · done · wait. */
  tone: string;
  /** Hover text: name (+ role), state, start time, restarts. */
  title: string;
  /** True on the last init square — renders the separator before the app containers. */
  gap: boolean;
}

const arr = (v: unknown): Record<string, unknown>[] => (Array.isArray(v) ? (v as Record<string, unknown>[]) : []);

const roleSuffix = (role: ContainerRole): string => {
  if (role === 'init') {
    return ' (init)';
  }
  return role === 'ephemeral' ? ' (debug)' : '';
};

function square(cs: Record<string, unknown>, role: ContainerRole): { tone: string; title: string } {
  const name = String(cs.name ?? '');
  const state = (cs.state as Record<string, Record<string, unknown>>) ?? {};
  const ready = Boolean(cs.ready);
  const restarts = Number(cs.restartCount ?? 0);
  let tone = 'wait';
  const lines = [name + roleSuffix(role)];
  if (state.running) {
    tone = ready ? 'ok' : 'warn';
    lines.push(ready ? 'Running' : 'Running (not ready)');
    if (state.running.startedAt) {
      lines.push('Started At  ' + String(state.running.startedAt));
    }
  } else if (state.terminated) {
    const t = state.terminated;
    tone = t.exitCode === 0 ? 'done' : 'err';
    lines.push(`Terminated · ${String(t.reason ?? '')} (exit ${Number(t.exitCode ?? 0)})`);
  } else if (state.waiting) {
    const reason = String(state.waiting.reason ?? 'Waiting');
    tone = /crashloop|imagepull|errimage|error|invalid/i.test(reason) ? 'err' : 'wait';
    lines.push('Waiting · ' + reason);
  }
  if (restarts > 0) {
    lines.push('Restarts: ' + restarts);
  }
  return { tone, title: lines.join('\n') };
}

/**
 * Statuses for one role, falling back to the spec so a pod that has not started yet still shows
 * a square per declared container rather than an em dash.
 */
function statusesFor(obj: KubeObject, statusKey: string, specKey: string): Record<string, unknown>[] {
  const statuses = arr((obj.status as Record<string, unknown>)?.[statusKey]);
  if (statuses.length > 0) {
    return statuses;
  }
  return arr((obj.spec as Record<string, unknown>)?.[specKey]).map((c) => ({ name: c.name, ready: false, state: {} }));
}

/**
 * Squares in POD LIFECYCLE ORDER: init containers, then app containers, then any ephemeral
 * (debug) containers. Left-to-right therefore reads as time, matching kubectl's ordering.
 *
 * Init containers used to be omitted entirely, which was a diagnostic hole rather than a
 * cosmetic gap: a pod stuck in `Init:CrashLoopBackOff` showed nothing wrong here, because every
 * app-container square was merely in its pre-start "waiting" tone — so a broken pod looked like
 * a slow one.
 *
 * Ephemeral containers are included deliberately: a debug container left attached to a pod is
 * worth noticing, not hiding.
 */
export function containerSquares(obj: KubeObject): ContainerSquare[] {
  const groups: { role: ContainerRole; list: Record<string, unknown>[] }[] = [
    { role: 'init', list: statusesFor(obj, 'initContainerStatuses', 'initContainers') },
    { role: 'app', list: statusesFor(obj, 'containerStatuses', 'containers') },
    { role: 'ephemeral', list: statusesFor(obj, 'ephemeralContainerStatuses', 'ephemeralContainers') },
  ];
  const out: ContainerSquare[] = [];
  for (const { role, list } of groups) {
    list.forEach((cs, i) => {
      out.push({
        key: `${role}:${String(cs.name ?? i)}`,
        role,
        gap: role === 'init' && i === list.length - 1,
        ...square(cs, role),
      });
    });
  }
  return out;
}
