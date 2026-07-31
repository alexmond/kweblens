/**
 * What each category overview is asking, in the words shown on the page.
 *
 * The copy is data rather than markup because it is the part that has to be got right. Each
 * overview makes a claim about the cluster, and a claim needs its bounds stated where it is read
 * — "not referenced in this namespace" invites deletion unless the page also says what the scan
 * could not see. Keeping it here means one component renders every category and the wording is
 * reviewable in one place.
 */
export interface OverviewCategory {
  /** Page heading. */
  title: string;
  /** Heading over the table of what is wrong — phrased as the question the check answers. */
  attention: string;
  /** Shown when nothing is wrong. Says what was checked, so an empty result is not ambiguous. */
  clean: string;
  /** Caveats that qualify the result. Rendered with the findings, not hidden behind a tooltip. */
  notes?: string[];
  /**
   * True when findings are candidates for review rather than faults, so they are not coloured
   * as problems.
   *
   * Config is advisory because "nothing in this namespace references it" is routinely true of
   * objects that are entirely in use — control-plane config, secrets named by a custom resource
   * or a webhook, certificates read by an ingress controller. Measured on a real cluster that is
   * over half of all ConfigMaps. Painting those red is the false alarm that teaches people to
   * ignore the screen, which then makes it useless when something is actually broken.
   */
  advisory?: boolean;
}

export const OVERVIEW_CATEGORIES: Record<string, OverviewCategory> = {
  workloads: {
    title: 'Workloads',
    attention: 'Needs attention',
    clean: 'Everything is healthy.',
  },
  network: {
    title: 'Network',
    attention: 'Services with nothing behind them',
    clean: 'Every Service has at least one ready endpoint.',
    notes: ['ExternalName Services are excluded — they resolve by DNS and have no endpoints by design.'],
  },
  storage: {
    title: 'Storage',
    attention: 'Claims that are not bound',
    clean: 'Every claim is bound.',
    notes: [
      // The gap is now narrower but still real, so it is still named: silence here would let a
      // reader assume every volume was measured.
      'Capacity is checked only where the provisioner reports per-volume usage. Where kubelet reports the whole backing disk instead — common for hostPath, local-path and plain NFS — the claim is not flagged on a figure that is not about it.',
      'Needs a Prometheus-compatible metrics backend; without one, only binding is checked.',
    ],
  },
  config: {
    title: 'Config',
    attention: 'Not referenced in this namespace',
    clean: 'Everything is referenced.',
    advisory: true,
    notes: [
      'Checked against pods, service accounts and ingresses in this namespace — including volumes, envFrom, single key references, image-pull secrets and TLS secrets.',
      'Not evidence that an object is unused: a ConfigMap or Secret named by a workload template whose pods do not exist yet, by another namespace, or by a custom resource will appear here while still being needed.',
      'Service-account tokens and Helm release records are excluded, because nothing mounting them says nothing about whether they are needed.',
    ],
  },
};

/** The category an `overview:<name>` nav id refers to, or null when it is not a category overview. */
export function overviewCategoryOf(navId: string | undefined): string | null {
  if (!navId?.startsWith('overview:')) {
    return null;
  }
  const name = navId.slice('overview:'.length);
  return name in OVERVIEW_CATEGORIES ? name : null;
}
