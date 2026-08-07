/**
 * Copy for panes that have nothing to render.
 *
 * <p>An empty pane is a claim — "there is nothing here" — and the reader cannot tell it apart
 * from "this failed silently" unless the pane says which. GH#298 is the extreme case: a
 * kweblens started with no cluster rendered a literally empty `main.content`
 * (`childElementCount: 0`), while the server had already written the explanation to its own
 * log — *"No clusters registered — set kweblens.clusters[*] or provide a kubeconfig."* The
 * browser is where that sentence is needed.
 *
 * <p>Kept apart from the components so the wording and the branching are testable without a
 * DOM. Roadmap R3 wants one empty state everywhere; this is its logic half, introduced for
 * the state that had none rather than as a sweep.
 */

/**
 * One rendered empty state: what happened, and the way forward when there is one.
 *
 * <p>`action.kind` is a name, not a callback — what a button does belongs to the surface that
 * renders it, and keeping this a plain value is what lets the branching be tested with no DOM.
 */
export interface EmptyStateCopy {
  title: string;
  body: string;
  /** The primary action, or null when the reader has nothing to press. */
  action: { label: string; kind: 'add-cluster' | 'sign-in' } | null;
}

/** The two sentences that describe a kweblens with nowhere to look — the server's log line, said out loud. */
const NO_CLUSTERS_BODY =
  'kweblens has no cluster to talk to: nothing configured under kweblens.clusters[*], and no kubeconfig was loaded.';

/**
 * The Clusters page's empty state, or null when the page has rows or has no business
 * claiming anything yet.
 *
 * <p>Three inputs decide it, and each one has to be separate from the others:
 *
 * <ul>
 * <li><b>loaded</b> — before the first response, zero clusters is not a fact. Saying "no
 * clusters registered" during the request would be a wrong answer that later corrects
 * itself, which is worse than a blank moment.
 * <li><b>failed</b> — a failed fetch also leaves the list empty, and "none are registered"
 * is then false. The error notice is the message in that case, so this returns null rather
 * than adding a confident second explanation next to it.
 * <li><b>canWrite</b> — adding a cluster is a write and needs the admin login (see the
 * security model in CLAUDE.md). Offering "Add cluster" to a signed-out reader would be an
 * action that 403s; the way forward for them is the login.
 * </ul>
 */
export function clusterListEmpty(state: {
  loaded: boolean;
  failed: boolean;
  count: number;
  canWrite: boolean;
}): EmptyStateCopy | null {
  if (!state.loaded || state.failed || state.count > 0) {
    return null;
  }
  if (state.canWrite) {
    return {
      title: 'No clusters registered',
      body: `${NO_CLUSTERS_BODY} Add one here — it is stored and usable straight away — or restart the server with a kubeconfig mounted.`,
      action: { label: 'Add cluster', kind: 'add-cluster' },
    };
  }
  return {
    title: 'No clusters registered',
    body: `${NO_CLUSTERS_BODY} Adding one is a write, so it needs the admin login; or restart the server with a kubeconfig mounted.`,
    action: { label: 'Sign in', kind: 'sign-in' },
  };
}

/**
 * A filter that matched nothing.
 *
 * <p>Distinct from {@link clusterListEmpty} on purpose: "you have no clusters" and "your
 * filter hid all of them" are different situations with different exits, and a single
 * "Nothing to show" for both sends the reader to the wrong one.
 */
export function noMatchEmpty(query: string, noun: string): EmptyStateCopy {
  return {
    title: `No ${noun} matches “${query.trim()}”`,
    body: 'Clear the filter to see everything again.',
    action: null,
  };
}
