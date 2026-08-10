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
 * DOM. Roadmap R3 wants one empty state everywhere; this is its logic half — first for the
 * state that had none (GH#298), then for the sweep that gave the other panes the same rule.
 *
 * <p><b>Three sentences, never one.</b> "Still loading", "this failed" and "there is genuinely
 * nothing here" are different claims, and the sweep found panes that rendered them
 * identically or, worse, rendered the wrong one:
 *
 * <ul>
 * <li>`HelmValuesModal` left "Loading…" on screen forever underneath the error, because the
 * loading branch keyed off `values === null` and a failure never fills `values`.
 * <li>`MetricChart` turned <i>every</i> failed `/metrics/graph` call into
 * "Graphs need a Prometheus / VictoriaMetrics backend" — a confident statement about the
 * cluster's configuration produced by a `.catch` that never looked at the error.
 * <li>`CommandPalette` said "No match for “x”" next to "Object search failed", i.e. answered
 * a question that had not been answered.
 * </ul>
 *
 * <p>So every builder below takes the fetch's state, not just its result, and returns `null`
 * whenever the pane has not earned the right to claim anything. `null` is not "render
 * nothing" — it is "this is not your sentence to say"; the surface renders the loading notice
 * or the error notice instead.
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

/**
 * The resource list's empty state — which of four situations it is in.
 *
 * <p>The table used to fall through to naive-ui's default "No Data" for all of them, which
 * is the same phrase for "this cluster has no CronJobs", "your search matched nothing" and
 * "the Helm release you scoped to owns none of these". Only the first is about the cluster;
 * the other two are about a control the reader set and can unset.
 *
 * <p>Returns null while loading and when a fetch failed: the shell renders the error above
 * the table (useResourceData sets it), and a confident second explanation underneath —
 * "there are no Pods here" — would contradict it. An unanswered question has no answer.
 */
export function resourceListEmpty(state: {
  loading: boolean;
  failed: boolean;
  /** Rows the server returned, before the search box and the Helm scope. */
  total: number;
  query: string;
  /** The Helm release the view is scoped to, when there is one. */
  scope: string | null;
  /** The kind, in the nav's own words ("Pods", "ConfigMaps"). */
  noun: string;
  /** The namespace filter, when one is set — naming it is what makes a zero readable. */
  namespace: string | null;
}): EmptyStateCopy | null {
  if (state.loading || state.failed) {
    return null;
  }
  const noun = state.noun.toLowerCase();
  if (state.query.trim()) {
    return {
      title: `No ${noun} match “${state.query.trim()}”`,
      body:
        state.scope !== null
          ? `${state.total} loaded, filtered by your search and by the Helm release ${state.scope}.`
          : `${state.total} loaded. Clear the search box to see them.`,
      action: null,
    };
  }
  if (state.scope !== null) {
    return {
      title: `The Helm release ${state.scope} manages no ${noun}`,
      body: `${state.total} are loaded from the cluster; the Helm filter in the top bar is hiding them. Clear it to see the lot.`,
      action: null,
    };
  }
  return state.namespace
    ? { title: `No ${noun} in ${state.namespace}`, body: 'The cluster returned none for this namespace.', action: null }
    : { title: `No ${noun} in this cluster`, body: 'The cluster returned none.', action: null };
}

/**
 * Whether a pane has earned the right to say "there is nothing here".
 *
 * <p>The whole rule, in one predicate, so the panes below cannot each get it subtly wrong:
 * an empty result is only a fact once a request was made <i>and</i> answered. In flight, the
 * answer is not in yet; after a failure there is no answer at all and the error notice is the
 * message. `clusterListEmpty` and `resourceListEmpty` above predate this and inline the same
 * three conditions — kept as they are because their tests pin the exact wording, and a shared
 * helper that changed a string would be a silent behaviour change dressed as a refactor.
 */
function mayClaimEmpty(state: { loading: boolean; failed: boolean; count: number }): boolean {
  return !state.loading && !state.failed && state.count === 0;
}

/**
 * The category overview's "needs attention" table.
 *
 * <p>`unavailable` is the reason this is not a one-liner. A category overview lists several
 * kinds and any of them can fail on its own, so "Everything is healthy." can be assembled out
 * of checks that never ran — the exact shape PR #306 forbade for a single check. When some
 * kind could not be listed the all-clear is withdrawn and replaced by what is actually known:
 * the checks that ran found nothing, and these ones did not run.
 */
export function attentionEmpty(state: {
  loading: boolean;
  failed: boolean;
  count: number;
  /** Labels of the kinds whose own check errored — an all-clear cannot cover these. */
  unavailable: string[];
  /** The category's own all-clear wording ("Every claim is bound."). */
  clean: string;
}): EmptyStateCopy | null {
  if (!mayClaimEmpty(state)) {
    return null;
  }
  if (state.unavailable.length > 0) {
    return {
      title: 'Nothing to report from the checks that ran',
      body: `This is not an all-clear: ${state.unavailable.join(', ')} could not be listed, so nothing is known about them.`,
      action: null,
    };
  }
  return { title: state.clean, body: 'Every kind on this page was checked and none needs attention.', action: null };
}

/**
 * The cluster overview's Warnings table.
 *
 * <p>The failed case is already handled a line above it by `uncheckedNote` — "Could not check
 * warnings — this is unknown, not clear" — and this must not add "No warnings" underneath it,
 * which is the contradiction `checkState.ts` was written to end.
 */
export function warningsEmpty(state: {
  loading: boolean;
  failed: boolean;
  count: number;
  namespace: string | null;
}): EmptyStateCopy | null {
  if (!mayClaimEmpty(state)) {
    return null;
  }
  return {
    title: 'No warnings',
    body: state.namespace
      ? `The cluster reported no Warning events in ${state.namespace}.`
      : 'The cluster reported no Warning events.',
    action: null,
  };
}

/**
 * A node's pod list.
 *
 * <p>A node with no pods is unusual enough to be worth a sentence rather than a blank: it is
 * normally cordoned or freshly joined, and naming those is more useful than the bare zero.
 */
export function nodePodsEmpty(state: {
  loading: boolean;
  failed: boolean;
  count: number;
  node: string;
}): EmptyStateCopy | null {
  return mayClaimEmpty(state)
    ? {
        title: `No pods scheduled on ${state.node}`,
        body: 'The node answered — it is running none. A cordoned or freshly joined node looks like this.',
        action: null,
      }
    : null;
}

/**
 * A directory in the pod file browser.
 *
 * <p>Names the path, because the browser walks and the reader's memory of which directory
 * they are in is exactly what a bare "empty" fails to correct. A directory that could not be
 * read is a `FileNotice`, never this.
 */
export function directoryEmpty(state: {
  loading: boolean;
  failed: boolean;
  count: number;
  path: string;
}): EmptyStateCopy | null {
  return mayClaimEmpty(state)
    ? {
        title: `${state.path} is empty`,
        body: 'The container listed this directory and it holds nothing.',
        action: null,
      }
    : null;
}

/**
 * A Helm release's stored values.
 *
 * <p>"No user-supplied values" is a claim about the release, and it was previously reachable
 * with no answer at all: the modal keyed its loading branch off `values === null`, which a
 * failed request also satisfies, so an error rendered the error *and* a permanent "Loading…".
 */
export function helmValuesEmpty(state: {
  loading: boolean;
  failed: boolean;
  /** True when the request answered with a blank document. */
  blank: boolean;
  release: string;
}): EmptyStateCopy | null {
  return mayClaimEmpty({ loading: state.loading, failed: state.failed, count: state.blank ? 0 : 1 })
    ? {
        title: `${state.release} was installed with no values of its own`,
        body: 'It is running on the chart defaults — helm get values returned an empty document.',
        action: null,
      }
    : null;
}

/**
 * A metric graph with no line on it, and why.
 *
 * <p>Two different nothings, and the component used to render them as one. `available: false`
 * is the server saying it found no Prometheus / VictoriaMetrics backend — a fact about the
 * deployment. Zero points is that backend answering with no samples in the window. A *failed*
 * call is neither, and returns null here so the surface can show the error and a retry: the
 * old `.catch` wrote `{ available: false }` and turned every timeout into a confident claim
 * that the operator had not installed a metrics stack.
 */
export function metricSeriesEmpty(state: {
  loading: boolean;
  failed: boolean;
  available: boolean;
  points: number;
}): EmptyStateCopy | null {
  if (state.loading || state.failed) {
    return null;
  }
  if (!state.available) {
    return {
      title: 'No metrics backend',
      body: 'Graphs need a Prometheus or VictoriaMetrics endpoint reachable from kweblens (kweblens.metrics).',
      action: null,
    };
  }
  return state.points === 0
    ? { title: 'No samples in the last hour', body: 'The backend answered, with nothing in the window.', action: null }
    : null;
}

/**
 * The command palette's result list.
 *
 * <p>Object search is a request, so the palette has all three states and used to show two of
 * them at once: `v-else-if="!searching"` printed "No match for “x”" beside "Object search
 * failed", which claims the cluster holds no such object on the strength of a request that
 * never came back.
 */
export function paletteEmpty(state: {
  loading: boolean;
  failed: boolean;
  count: number;
  query: string;
}): EmptyStateCopy | null {
  return mayClaimEmpty(state) ? noMatchEmpty(state.query, 'command or object') : null;
}

/**
 * Two documents that are the same.
 *
 * <p>No fetch behind it — both sides are already in hand — which is why it takes no state.
 * It is here anyway so that the editor's "nothing to see" says it in the same voice and the
 * same component as every other one, rather than in a class of its own.
 */
export function noChangesEmpty(): EmptyStateCopy {
  return { title: 'No changes', body: 'The edited document is byte-for-byte what was loaded.', action: null };
}
