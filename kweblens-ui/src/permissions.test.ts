import { describe, expect, it } from 'vitest';

import { controlAccess, deniedReason, isDenied, verdictFor } from './permissions';
import { rowActionOptions } from './rowActions';
import type { KindAccess, KubeObject } from './types';

const report = (verbs: KindAccess['verbs'], namespace: string | null = 'ns1'): KindAccess => ({
  kind: 'Pod',
  namespace,
  verbs,
});

const pod: KubeObject = { kind: 'Pod', metadata: { name: 'api-0', namespace: 'ns1' }, spec: { containers: [] } };

// ---- Fail-open. First, because it is the assertion that matters most: every one of these
// goes red if `unknown` is ever folded into `denied`, or if a control is disabled on
// anything short of a real refusal. ----

describe('an answer we do not have leaves the control enabled', () => {
  it('treats no report at all as unknown', () => {
    expect(verdictFor(null, 'delete')).toBe('unknown');
    expect(isDenied(null, 'delete')).toBe(false);
    expect(controlAccess(null, 'delete')).toEqual({ disabled: false, reason: null });
  });

  it('treats undefined — the prop nobody passed — as unknown', () => {
    expect(isDenied(undefined, 'delete')).toBe(false);
  });

  it('treats a verb the server did not report on as unknown', () => {
    expect(verdictFor(report({ patch: { verdict: 'denied', reason: null } }), 'delete')).toBe('unknown');
    expect(isDenied(report({ patch: { verdict: 'denied', reason: null } }), 'delete')).toBe(false);
  });

  it('treats an action with no verb of its own as unknown, never as refused', () => {
    // `drain` deletes Pods and `trigger` creates a Job — a verdict about THIS kind cannot
    // answer either, so they carry no verb and must stay enabled.
    expect(isDenied(report({ delete: { verdict: 'denied', reason: null } }), null)).toBe(false);
  });

  it('leaves the control enabled when the review itself came back unknown', () => {
    const unknown = report({ delete: { verdict: 'unknown', reason: 'the access review could not be run' } });
    expect(isDenied(unknown, 'delete')).toBe(false);
    expect(controlAccess(unknown, 'delete').disabled).toBe(false);
  });

  it('does not disable a row action when the whole report is missing', () => {
    const menu = rowActionOptions(pod, null);
    expect(menu.every((o) => !o.disabled)).toBe(true);
  });
});

// ---- The positive control. Without it, a module that answered "unknown" to everything
// would satisfy every assertion above. ----

describe('a refusal is still a refusal', () => {
  const denied = report({ delete: { verdict: 'denied', reason: 'RBAC: no rules authorize this' } });

  it('disables the control', () => {
    expect(verdictFor(denied, 'delete')).toBe('denied');
    expect(isDenied(denied, 'delete')).toBe(true);
    expect(controlAccess(denied, 'delete').disabled).toBe(true);
  });

  it('names the service account and not the operator — there is one shared identity', () => {
    const reason = deniedReason(denied, 'delete');
    expect(reason).toContain('service account');
    expect(reason).not.toMatch(/\byou\b/i);
  });

  it('names the kind and the namespace the verdict is about', () => {
    expect(deniedReason(denied, 'delete')).toContain('Pod');
    expect(deniedReason(denied, 'delete')).toContain('in ns1');
  });

  it("passes on the cluster's own words, which are usually the whole answer", () => {
    expect(deniedReason(denied, 'delete')).toContain('RBAC: no rules authorize this');
  });

  it('omits the namespace when the verdict was cluster-wide, rather than inventing one', () => {
    const clusterWide = report({ delete: { verdict: 'denied', reason: null } }, null);
    expect(deniedReason(clusterWide, 'delete')).not.toContain(' in ');
  });
});

describe('rowActionOptions with a verdict', () => {
  const denied = report({
    delete: { verdict: 'denied', reason: 'RBAC: no rules authorize this' },
    patch: { verdict: 'allowed', reason: null },
  });

  it('disables Delete and Force Delete, and says why on each', () => {
    const menu = rowActionOptions(pod, denied);
    for (const key of ['delete', 'forceDelete']) {
      const option = menu.find((o) => o.key === key);
      expect(option?.disabled).toBe(true);
      expect(option?.deniedReason).toContain('cannot delete Pod');
    }
  });

  it('leaves everything the refusal did not cover alone', () => {
    const menu = rowActionOptions(pod, denied);
    // Logs is a read and carries no verb; Edit only opens the editor, whose Apply is where
    // the write — and the check — actually happens.
    expect(menu.find((o) => o.key === 'logs')?.disabled).toBeUndefined();
    expect(menu.find((o) => o.key === 'edit')?.disabled).toBeUndefined();
  });

  it('never disables an action without giving a reason to render', () => {
    const menu = rowActionOptions(pod, denied);
    for (const option of menu.filter((o) => o.disabled)) {
      expect(option.deniedReason).toBeTruthy();
    }
  });
});
