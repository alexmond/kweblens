import { describe, expect, it } from 'vitest';

import { navLabelParts } from './navLabel';

/**
 * What the rail paints when only `fits` characters survive — the CSS in character form.
 *
 * The head is weighted to shrink ~1000x harder than the tail, so it gives way first and takes
 * the ellipsis; only once it is gone does the tail start to lose its own end.
 */
function rendered(label: { head: string; tail: string }, fits: number): string {
  const whole = label.head + label.tail;
  if (whole.length <= fits) {
    return whole;
  }
  if (label.head.length > 0 && label.tail.length + 1 <= fits) {
    return label.head.slice(0, fits - label.tail.length - 1) + '…' + label.tail;
  }
  return label.tail.slice(0, Math.max(0, fits - 1)) + '…';
}

/** The property the whole module exists for: no two rows of one list read the same. */
function expectAllDistinct(labels: string[], fits: number): void {
  const parts = navLabelParts(labels);
  const painted = parts.map((p) => rendered(p, fits));
  expect(new Set(painted).size, `collided at ${fits} chars: ${painted.join(' / ')}`).toBe(painted.length);
}

describe('navLabelParts', () => {
  it('is index-aligned with its input', () => {
    expect(navLabelParts(['Pods', 'Jobs'])).toEqual([
      { head: '', tail: 'Pods' },
      { head: '', tail: 'Jobs' },
    ]);
  });

  it('leaves a label alone when nothing can collide with it', () => {
    const labels = ['Pods', 'Deployments', 'Stateful Sets', 'Daemon Sets', 'Replica Sets', 'Jobs', 'Cron Jobs'];
    expect(navLabelParts(labels).every((p) => p.head === '')).toBe(true);
  });

  it('protects the tail of the pair from #327 (camel-case CRD kinds)', () => {
    expect(navLabelParts(['VerticalPodAutoscaler', 'VerticalPodAutoscalerCheckpoint'])).toEqual([
      { head: 'VerticalPod', tail: 'Autoscaler' },
      { head: 'VerticalPodAutoscaler', tail: 'Checkpoint' },
    ]);
  });

  it('protects the tail of the pair from #327 (spaced built-in kinds)', () => {
    expect(navLabelParts(['Validating Admission Policies', 'Validating Admission Policy Bindings'])).toEqual([
      { head: 'Validating Admission ', tail: 'Policies' },
      { head: 'Validating Admission Policy ', tail: 'Bindings' },
    ]);
  });

  it('cuts at the last word boundary it can, not the first', () => {
    expect(navLabelParts(['IPAddressPool', 'IPAddressPoolStatus'])).toEqual([
      { head: 'IPAddress', tail: 'Pool' },
      { head: 'IPAddressPool', tail: 'Status' },
    ]);
  });

  it('splits an acronym run where the next word starts', () => {
    // `VMAlertmanager` has exactly one boundary — after the all-caps run, at `Alertmanager`.
    expect(navLabelParts(['VMAlertmanager', 'VMAlertmanagerConfig'])[0]).toEqual({
      head: 'VM',
      tail: 'Alertmanager',
    });
  });

  // ---- adversarial cases ----

  it('lengthens the tail when the shortest one is also another sibling ending', () => {
    // `Config` alone would not distinguish VMAlertmanagerConfig from VMScrapeConfig.
    const parts = navLabelParts(['VMAlertmanager', 'VMAlertmanagerConfig', 'VMScrapeConfig']);
    expect(parts[1]).toEqual({ head: 'VM', tail: 'AlertmanagerConfig' });
  });

  it('keeps a shared suffix out of the tail when the labels also share a prefix', () => {
    // Same head AND same tail: only the middle tells them apart, so the tail must reach it.
    expectAllDistinct(['SuperLongPrefixAlphaSuffix', 'SuperLongPrefixBetaSuffix'], 14);
    expect(navLabelParts(['SuperLongPrefixAlphaSuffix', 'SuperLongPrefixBetaSuffix'])).toEqual([
      { head: 'SuperLongPrefix', tail: 'AlphaSuffix' },
      { head: 'SuperLongPrefix', tail: 'BetaSuffix' },
    ]);
  });

  it('handles one label being a prefix of another', () => {
    expect(navLabelParts(['Ingress', 'IngressClass'])).toEqual([
      { head: '', tail: 'Ingress' },
      { head: 'Ingress', tail: 'Class' },
    ]);
  });

  it('handles one label being a suffix of another', () => {
    // `Policies` cannot be reached by truncating `Network Policies` from the right, so there
    // is nothing to protect — and protecting it would make both rows read `…Policies`.
    expect(navLabelParts(['Policies', 'Network Policies'])).toEqual([
      { head: '', tail: 'Policies' },
      { head: '', tail: 'Network Policies' },
    ]);
  });

  it('refuses to promise a distinction it cannot make', () => {
    // `Bindings` and `Role Bindings` are both endings of siblings, so neither can be the tail;
    // the label stays unsplit rather than rendering an ambiguous `…Bindings`.
    const parts = navLabelParts(['Cluster Roles', 'Roles', 'Cluster Role Bindings', 'Role Bindings']);
    expect(parts[2]).toEqual({ head: '', tail: 'Cluster Role Bindings' });
  });

  it('survives duplicate labels without throwing or inventing a tail', () => {
    expect(navLabelParts(['Pods', 'Pods'])).toEqual([
      { head: '', tail: 'Pods' },
      { head: '', tail: 'Pods' },
    ]);
  });

  it('survives empty, single and one-character sets', () => {
    expect(navLabelParts([])).toEqual([]);
    expect(navLabelParts(['Pods'])).toEqual([{ head: '', tail: 'Pods' }]);
    expect(navLabelParts(['', 'A'])).toEqual([
      { head: '', tail: '' },
      { head: '', tail: 'A' },
    ]);
  });

  it('handles a very long CRD kind with no sibling', () => {
    const long = 'ClusterServiceVersionInstallPlanApprovalRequest';
    expect(navLabelParts([long])).toEqual([{ head: '', tail: long }]);
  });

  it('keeps every sibling of a long real-world group distinct once truncated', () => {
    const traefik = [
      'IngressRoute',
      'IngressRouteTCP',
      'IngressRouteUDP',
      'Middleware',
      'MiddlewareTCP',
      'ServersTransport',
      'ServersTransportTCP',
      'TLSOption',
      'TLSStore',
      'TraefikService',
    ];
    // 12 characters is the fewest the gate is designed to hold for; 8 is past the point
    // where the rail is readable at all, and is here only to show where the guarantee ends.
    for (const fits of [24, 18, 14, 12]) {
      expectAllDistinct(traefik, fits);
    }
  });

  it('keeps the two pairs from #327 distinct at every width that fits 12 characters', () => {
    for (let fits = 12; fits <= 36; fits += 1) {
      expectAllDistinct(['VerticalPodAutoscaler', 'VerticalPodAutoscalerCheckpoint'], fits);
      expectAllDistinct(['Validating Admission Policies', 'Validating Admission Policy Bindings'], fits);
    }
  });

  it('would have failed on the unsplit labels — the control for the check above', () => {
    const unsplit = (labels: string[]) => labels.map((label) => ({ head: '', tail: label }));
    const painted = unsplit(['VerticalPodAutoscaler', 'VerticalPodAutoscalerCheckpoint']).map((p) =>
      // An unsplit label is head-only as far as the browser is concerned: it cuts the end.
      p.tail.length <= 15 ? p.tail : p.tail.slice(0, 15) + '…',
    );
    expect(painted[0]).toBe(painted[1]);
  });

  it('never loses a character: head + tail is always the original label', () => {
    const labels = [
      'Validating Admission Policies',
      'Validating Admission Policy Bindings',
      'VerticalPodAutoscaler',
      'VerticalPodAutoscalerCheckpoint',
      'Persistent Volume Claims',
      'Persistent Volumes',
      'API',
      'APIAuth',
      'APIPortal',
      'APIPortalAuth',
    ];
    expect(navLabelParts(labels).map((p) => p.head + p.tail)).toEqual(labels);
  });
});
