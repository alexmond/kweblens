<script setup lang="ts">
import { NConfigProvider, darkTheme } from 'naive-ui';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { api } from './api';
import { failureNotice } from './apiFailure';
import type { Command } from './commandPalette';
import { filesFeature } from './podFiles';
import { signIn } from './session';
import { useAppActions } from './composables/useAppActions';
import { useClusterScope } from './composables/useClusterScope';
import { useClusters } from './composables/useClusters';
import { useDock } from './composables/useDock';
import { useNavigation } from './composables/useNavigation';
import { useAccess } from './composables/useAccess';
import { useResourceData } from './composables/useResourceData';
import { defaultHiddenCols } from './columns';
import { useDialog } from './dialog';
import { clusterListEmpty } from './emptyState';
import { objKey } from './kube';
import { withStatusTerm } from './objectFilter';
import type { PaneFailure } from './paneFailure';
import { actionFailed, bulkDeleteIncomplete, mayRetry, readMessage } from './paneFailure';
import { loadDark, loadHiddenCols, loadKeptCols, loadNamespace, saveCluster, saveDark, saveNamespace } from './prefs';
import { HELM_VIEW_IDS, NAV, filterObjects, isSynthetic } from './shell';
import { buildResourceColumns } from './table';
import type { KubeObject, NavItem } from './types';

import AppFooter from './components/AppFooter.vue';
import BrandBar from './components/BrandBar.vue';
import CategoryOverview from './components/CategoryOverview.vue';
import ClusterOverview from './components/ClusterOverview.vue';
import ClustersPage from './components/ClustersPage.vue';
import CommandPalette from './components/CommandPalette.vue';
import CreateModal from './components/CreateModal.vue';
import Detail from './components/Detail.vue';
import DiagnosticsModal from './components/DiagnosticsModal.vue';
import { overviewCategoryOf } from './components/overviewCategories';
import DialogHost from './components/DialogHost.vue';
import DockArea from './components/DockArea.vue';
import FailureNotice from './components/FailureNotice.vue';
import ForwardModal from './components/ForwardModal.vue';
import HelmView from './components/HelmView.vue';
import LoginModal from './components/LoginModal.vue';
import PortForwards from './components/PortForwards.vue';
import ResourceListView from './components/ResourceListView.vue';
import Sidebar from './components/Sidebar.vue';

// --- UI state (the shell owns selection/detail/query/auth/modals; data comes from composables) ---
/**
 * The shell's one failure slot — and the reason it is a union rather than a string.
 *
 * The roadmap framed R3's error half as "classify each site". This site cannot be classified,
 * because six different code paths write to it and they are not the same kind of thing: the
 * clusters fetch, the nav fetch, the object-list fetch and opening a search hit are READS,
 * while every row action (delete, drain, scale, rollout-restart) and bulk delete are WRITES.
 * A Retry button here would have re-run whichever of those failed last, so on a failed Drain
 * it would have offered to drain the node again — unattended, and against the standing rule
 * that remediation is suggest → approve → apply. The classification belongs to the writer.
 */
const failure = ref<PaneFailure | null>(null);
const namespace = ref<string | null>(null);
const helmRelease = ref<{ namespace: string; name: string } | null>(null);
const selected = ref<NavItem | null>(null);
const detail = ref<{ resourceId: string; obj: KubeObject; edit?: boolean } | null>(null);
const hiddenCols = ref<Set<string>>(new Set());
// The other two thirds of "which columns are on screen" (#238): what the user pinned against
// the width rule, and what the width took away. The table computes the second — it is the only
// thing that knows how wide it is — and reports it here so the Columns picker can say so.
const keptCols = ref<Set<string>>(new Set());
const autoHiddenCols = ref<Set<string>>(new Set());
const selection = ref<Set<string>>(new Set());
const query = ref('');
const authUser = ref<string | null>(null);
const showLogin = ref(false);
const showCreate = ref(false);
const showDiagnostics = ref(false);
const forward = ref<{ kind: string; namespace: string; name: string; ports: number[] } | null>(null);
const helmTarget = ref<{ namespace: string; name: string } | null>(null);

/** A composable's read failure, already rendered by `failureNotice` on its way up. */
const setError = (e: string | null) => (failure.value = e === null ? null : readMessage(e));
/** A row action that did not complete — never cleared by a later read, and never retryable. */
const reportFailure = (title: string, e: unknown) => (failure.value = actionFailed(title, e));
/** A bulk delete's own summary of what it did and did not delete. */
const reportOutcome = (message: string) => (failure.value = bulkDeleteIncomplete(message));

/**
 * Whether the thing on screen is a failed READ — which is a different question from "is there
 * an error", and the one the two guards below actually mean.
 *
 * A failed delete does not make the cluster list unknown, so it must not suppress the
 * zero-cluster empty state, and it does not make the cluster overview wrong, so it must not
 * blank the page the operator was looking at when they pressed the button.
 */
const readFailed = computed(() => mayRetry(failure.value));

/**
 * Re-run every read the shell owns.
 *
 * A single Retry has to cover all of them because a single slot showed all of them: the
 * clusters list, the nav tree, the namespace list, the Helm scope and the object list each
 * write here. `reloadReads` is a nonce the cluster-scoped watches take as a dependency, so
 * bumping it re-runs exactly the requests a cluster change would.
 */
const reloadReads = ref(0);

const dialog = useDialog();

// Theme: Naive UI light/dark, toggled from the brand bar and persisted.
const dark = ref(loadDark());
const theme = computed(() => (dark.value ? darkTheme : null));
/**
 * Point Naive's primary at the app's accent.
 *
 * Naive's default primary is GREEN, which made the drawer's active tab, focus rings and
 * switches read as a different product from the blue shell — and collided with meaning,
 * since green is the "healthy" tone in the semantic palette. Setting it once here fixes
 * every Naive control rather than restyling them one at a time.
 *
 * The values match --accent / a darker press state per theme; they cannot be `var(--accent)`
 * because Naive derives shades from the value and needs a real colour.
 */
const themeOverrides = computed(() => {
  const accent = dark.value ? '#3d9be0' : '#0a7ac2';
  const hover = dark.value ? '#57abe8' : '#1a8ad2';
  const pressed = dark.value ? '#2f89cc' : '#086aab';
  return {
    common: {
      primaryColor: accent,
      primaryColorHover: hover,
      primaryColorPressed: pressed,
      primaryColorSuppl: hover,
    },
    // `<NTag type="info">` paints Naive's info blue on a 12% tint of ITSELF, which in the
    // light theme is rgb(32,128,240) on rgb(228,240,253) — 3.36:1, under AA, on the drawer's
    // "Managed By … Helm" marker (#269). Same-hue-on-its-own-tint is the mistake the nav and
    // the command palette each made with the accent (3.80:1 / 3.02:1, #200/#201); here the
    // pair came from the component library rather than from our palette, so the fix belongs
    // in the theme override and not in styles.css — there is no literal of ours to change.
    //
    // The value is the relationship `.chip` uses (the theme's accent mixed toward the theme's
    // text), so the drawer's Naive tag and the drawer's hand-rolled chips read as one thing.
    // Unlike `primaryColor` above, Naive derives no shades from `textColorInfo` — it is
    // applied verbatim — so a computed colour is safe here where it is not there.
    Tag: { textColorInfo: 'color-mix(in srgb, var(--accent) 45%, var(--text))' },
  };
});
const toggleTheme = () => {
  dark.value = !dark.value;
  saveDark(dark.value);
  document.documentElement.classList.toggle('kw-dark', dark.value);
};
document.documentElement.classList.toggle('kw-dark', dark.value);

const { clusters, cluster, loaded: clustersLoaded, refresh: refreshClusters } = useClusters(setError);
const retryRead = () => {
  failure.value = null;
  reloadReads.value += 1;
  void refreshClusters();
};
// Closed-mode: the session cookie outlives the in-memory creds. On load, restore an existing
// session (so write controls appear + data loads after a reload) and re-fetch once authed.
// This call carries no credentials, so it asks "is there still a session?" and nothing more —
// which is exactly what it should ask, and is why signing out has to end that session on the
// server rather than in the tab (#320). A reload after Sign out now restores nothing.
api
  .verifySession()
  .then((r) => {
    authUser.value = r.user;
    void refreshClusters();
    // Same reason as after an interactive sign-in, plus a race: the startup `about()` below
    // is fired concurrently with this, so on a restored session it can be answered before
    // the session is established and come back withholding `allowedRoots`.
    loadAbout();
  })
  .catch(() => undefined);
// Ask once, at startup, whether the pod file browser is on. Without this the Files tab can
// only learn by making a request that fails, so a drawer opened before that first failure
// shows a tab whose sole content is an explanation that it does not work. A public GET, so
// it works signed out too; a server that omits the field leaves the state unknown and the
// old learn-from-failure path still applies.
// Re-asked after a sign-in, not only at startup: the server withholds `allowedRoots` from an
// unauthenticated caller on purpose (DiagnosticsService sends `List.of()`), and the whole
// pod-file family is authenticated even in open-mode. Seeding once at startup therefore left a
// confined deployment permanently looking unconfined, so the browser opened at `/` — the one
// path such a deployment always refuses, which is exactly what allowedRoots exists to avoid.
const loadAbout = (): void => {
  api
    .about()
    .then((info) => filesFeature.noteAbout(info))
    .catch(() => undefined);
};
loadAbout();

// Reset the view when the active cluster changes.
//
// Declared BEFORE the cluster-scoped composables below, not after, because Vue runs watchers in
// creation order and this one has to win (GH#323). Registered after them, the Helm-scope watch
// saw the new cluster while `helmRelease` still held the previous cluster's release, and asked
// the new cluster for the resources of a release it has never heard of — a wasted request and a
// flash of "could not scope to release …" for a filter that was already on its way out.
watch(cluster, (id) => {
  selected.value = null;
  helmRelease.value = null;
  // Namespace is remembered PER CLUSTER: namespaces are cluster-local, so carrying one over
  // would filter on a namespace that may not exist here and silently show an empty list.
  namespace.value = id ? loadNamespace(id) : null;
  saveCluster(id);
});

const { nav, counts, helmCounts, namespaces, helmReleaseList, favorites, helmScope } = useClusterScope(
  cluster,
  namespace,
  helmRelease,
  setError,
  reloadReads,
);
const { objects, setObjects, loading, failed, live, cols, usage, nodeDisk } = useResourceData(
  cluster,
  selected,
  namespace,
  setError,
  reloadReads,
);
// What the deployment's SERVICE ACCOUNT may do with the selected kind here (#354). One
// request per surface; `null` means "we could not tell", which every consumer renders as
// enabled. It gates nothing — the server still refuses a write it should refuse.
const access = useAccess(cluster, selected, namespace);

const {
  sessions: dockSessions,
  active: activeSession,
  setActive,
  openDock,
  openLogs,
  closeDock,
  toggleFloat,
} = useDock(cluster);

/**
 * A filter a navigation arrived WITH — an overview state opening exactly the objects it counted
 * (#338) — held for the reset below rather than assigned to `query` at the call site, because
 * that reset clears the box on every kind/namespace change and runs after the navigation call
 * returns.
 *
 * <b>It carries the kind it was meant for, and that is not decoration.</b> The first version
 * cleared it on `nextTick` instead, which measured as the filter never arriving at all:
 * `requestQuery` ran BEFORE the mutation that queues the reset, so there was no flush promise to
 * chain onto yet and the clear resolved first. Matching on the kind removes the question — the
 * reset applies a pending filter only when it is the reset that navigation caused, and clears it
 * either way, so no ordering between the two can either lose it or leak it into a later
 * navigation.
 */
let pendingQuery: { kind: string; query: string } | null = null;

// Reset the view when the selected kind/namespace changes. The cluster watch that feeds this
// one is declared further up, for the ordering reason recorded there.
watch([selected, namespace], () => {
  detail.value = null;
  // `pendingQuery !== null` first, and not merely for the type checker: written as
  // `pendingQuery?.kind === selected.value?.kind` it is TRUE when both are absent, which is
  // every reset that had no pending filter and no selection.
  const arriving = pendingQuery !== null && pendingQuery.kind === selected.value?.kind;
  query.value = arriving ? (pendingQuery?.query ?? '') : '';
  pendingQuery = null;
  // Restore this kind's saved column choice, falling back to its defaults (e.g. Nodes offers
  // more columns than fit — the extras stay available in the Columns ▾ picker). Previously
  // this always re-seeded from the defaults, so enabling an opt-in column was lost on the
  // next navigation.
  hiddenCols.value = loadHiddenCols(selected.value?.id, defaultHiddenCols(selected.value?.id));
  keptCols.value = loadKeptCols(selected.value?.id);
  autoHiddenCols.value = new Set();
  selection.value = new Set();
  if (cluster.value && namespace.value !== undefined) {
    saveNamespace(cluster.value, namespace.value);
  }
});

const { navigateToKind, navigateToPortForwards, navigateToHelmRelease, knowsKind, resourceIdForKind } = useNavigation(
  nav,
  {
    setSelected: (i) => (selected.value = i),
    setDetail: (d) => (detail.value = d),
    setNamespace: (ns) => (namespace.value = ns),
    setHelmTarget: (t) => (helmTarget.value = t),
    currentNamespace: () => namespace.value,
  },
);

/**
 * Open a kind's list already filtered to one state (#338).
 *
 * The namespace is deliberately not passed: `navigateToKind` keeps the filter already in force,
 * so the list is scoped to the same slice of the cluster the card counted from (#158). Passing
 * one would be the same value, spelled a second way.
 */
const navigateToState = (kind: string, filterQuery: string) => {
  pendingQuery = { kind, query: filterQuery };
  navigateToKind(kind);
};

// What the drawer is showing: the row the table highlights, and the object a delete has to
// close the drawer for (#233 put delete inside the drawer). One declaration, two consumers.
const selectedKey = computed(() => (detail.value ? objKey(detail.value.obj) : null));

// The drawer's verdicts — but only when the drawer is showing the kind they are ABOUT.
// A drawer can be opened on a pod from a node's Pods tab or on a search hit, and those are
// a different kind from the list behind them; handing over the list's answer would grey out
// (or fail to grey out) a control on the strength of a verdict about something else.
const detailAccess = computed(() =>
  detail.value && selected.value && detail.value.resourceId === selected.value.id ? access.value : null,
);

const { signOut, fetchPods, handleRowAction, toggleFavorite, toggleCol, bulkDelete } = useAppActions({
  cluster,
  authUser,
  selected,
  selection,
  objects,
  hiddenCols,
  keptCols,
  autoHiddenCols,
  favorites,
  dialog,
  openDock,
  openLogs,
  setForward: (f) => (forward.value = f),
  setDetail: (d) => (detail.value = d),
  reportFailure,
  reportOutcome,
  setObjects,
  setShowLogin: (v) => (showLogin.value = v),
  setAuthUser: (v) => (authUser.value = v),
  detailKey: selectedKey,
});

// A pod clicked in a node's Pods tab: swap the drawer to that pod's detail. `pods` is the
// nav id for the Pod kind, so the YAML/actions in the drawer resolve against the right kind.
const openPodDetail = (obj: KubeObject) => {
  detail.value = { resourceId: 'pods', obj };
};

// Command palette (Ctrl/Cmd-K). Bound at the window so it opens from anywhere in the
// shell; the guard lets the shortcut through only when focus is not in a text field, so it
// cannot steal the key from the YAML editor or a filter box.
const paletteOpen = ref(false);
const onPaletteKey = (e: KeyboardEvent) => {
  if (e.key !== 'k' || !(e.ctrlKey || e.metaKey)) {
    return;
  }
  const el = e.target as HTMLElement | null;
  if (el && (el.isContentEditable || ['INPUT', 'TEXTAREA'].includes(el.tagName))) {
    return;
  }
  e.preventDefault();
  paletteOpen.value = true;
};
onMounted(() => window.addEventListener('keydown', onPaletteKey));
onBeforeUnmount(() => window.removeEventListener('keydown', onPaletteKey));

// The Clusters page (GH#141 W1) spans every cluster rather than sitting inside one, so it
// is reached from the rail rather than the per-cluster nav tree.
const showClusters = ref(false);
const openClusterFromPage = (id: string) => {
  cluster.value = id;
  showClusters.value = false;
};

/**
 * The zero-cluster state (GH#298).
 *
 * Every other content surface needs a cluster, and used to sit — Clusters page included —
 * behind one `v-if="cluster"`. With none registered the fetch SUCCEEDS, so `error` stays
 * null and `cluster` stays null, and the whole pane rendered nothing at all: measured
 * `childElementCount: 0`, with the rail's "All clusters" tile setting a flag whose page was
 * inside the guard it could not escape. The server logs the explanation
 * (`ClusterBootstrap`); the browser said none of it.
 *
 * This is a supported state — runtime cluster-add is a shipped feature, and removing your
 * last cluster from the Clusters page lands here — so the Clusters page is now the landing
 * page whenever there is nothing to land on. It is the one surface that must work without a
 * cluster, because it is the only one that can create one.
 */
const clustersEmptyCopy = computed(() =>
  clusterListEmpty({
    loaded: clustersLoaded.value,
    // A failed READ, specifically: a delete that came back 403 leaves the cluster list exactly
    // as trustworthy as it was, so it must not suppress this page's own explanation.
    failed: readFailed.value,
    count: clusters.value.length,
    canWrite: authUser.value !== null,
  }),
);
const showClustersPage = computed(() => showClusters.value || clustersEmptyCopy.value !== null);

const runCommand = (command: Command) => {
  paletteOpen.value = false;
  if (command.kind === 'page') {
    showClusters.value = true;
  } else if (command.kind === 'cluster') {
    cluster.value = command.target;
  } else if (command.kind === 'object' && command.hit) {
    void openSearchHit(command.hit);
  } else if (command.item) {
    selected.value = command.item;
  }
};

/**
 * Open a global-search hit (GH#259).
 *
 * The hit is addressed by its OWN kind — `hit.resourceId` — never by whatever list happens to be
 * on screen. Addressing a row by the list's kind is the bug GH#187 fixed for expanded workload
 * rows, and a search result is that same shape of mistake waiting to happen: the reader is by
 * definition somewhere else when they pick one.
 *
 * The list is navigated to first, so closing the drawer leaves the reader somewhere that makes
 * sense rather than on the page they searched from. The object itself is fetched rather than
 * waited for: the list load is a separate request that may not have landed, and picking the row
 * out of it once it does would make opening a drawer depend on a race.
 */
const openSearchHit = async (hit: { resourceId: string; kind: string; namespace: string | null; name: string }) => {
  const clusterId = cluster.value;
  if (!clusterId) {
    return;
  }
  navigateToKind(hit.kind, hit.namespace ?? undefined);
  try {
    const obj = await api.object(clusterId, hit.resourceId, hit.name, hit.namespace);
    detail.value = { resourceId: hit.resourceId, obj };
  } catch (e) {
    setError(`Could not open ${hit.kind} ${hit.name}: ${failureNotice(e)}`);
  }
};

const activeCluster = computed(() => clusters.value.find((c) => c.id === cluster.value) ?? null);
const filtered = computed(() => filterObjects(objects.value, query.value, helmScope.value));
// What the header's status chips count: the same narrowing MINUS the status term itself, so a
// chip's number is the number of rows its click produces rather than a number about a set
// nobody is looking at (GH#341). Computed here because the Helm scope is the shell's, and the
// two go through the one `filterObjects` — a second filtering mechanism is how the count and
// the list start disagreeing.
const statusRows = computed(() => filterObjects(objects.value, withStatusTerm(query.value, null), helmScope.value));
const tableCols = computed(() => buildResourceColumns(selected.value?.id, cols.value, usage.value, nodeDisk.value));
const visibleCols = computed(() => tableCols.value.filter((c) => !hiddenCols.value.has(c.key)));
const mergedCounts = computed(() => ({ ...counts.value, ...helmCounts.value }));

const id = computed(() => selected.value?.id);
// Hidden when a READ failed — there is nothing to summarise — but NOT when an action did: a
// failed Restart used to blank the dashboard the operator was standing on, so the only trace
// of what they had just done was an error over an empty page.
const showClusterOverview = computed(() => (!selected.value || id.value === NAV.overviewCluster) && !readFailed.value);
/** Which category dashboard to render, if the selected nav item is one. */
const overviewCategory = computed(() => overviewCategoryOf(id.value));
const showHelm = computed(() => id.value !== undefined && HELM_VIEW_IDS.includes(id.value));
const helmViewName = computed(() =>
  id.value === NAV.helmCharts ? 'charts' : id.value === NAV.helmRepositories ? 'repositories' : 'releases',
);
const showList = computed(() => selected.value && !isSynthetic(selected.value.id));
const fetchChildrenFn = computed(() => (selected.value?.expandable ? fetchPods : undefined));

/**
 * The resource id to address an opened row by.
 *
 * A row is not always the kind of the list it sits in: an expanded workload carries its child
 * pods as rows, and addressing one of those as the parent's kind asks the server for a
 * Deployment by a pod's name, which it rightly refuses. The row's own kind wins; the selected
 * nav item is the fallback for rows that do not carry one.
 */
const resourceIdFor = (obj: KubeObject): string =>
  (obj.kind ? resourceIdForKind(obj.kind) : null) ?? selected.value?.id ?? '';

// The verdict is the server's (`session.ts` — it is the only thing that can check a
// password); this only reacts to it.
const loginSubmit = async (user: string, pass: string): Promise<boolean> => {
  if (!(await signIn(user, pass))) {
    return false;
  }
  authUser.value = user;
  showLogin.value = false;
  void refreshClusters();
  loadAbout();
  return true;
};
const onCreateAuthExpired = () => {
  void signOut();
  showCreate.value = false;
  showLogin.value = true;
};
const onForwardStarted = () => {
  forward.value = null;
  navigateToPortForwards();
};
</script>

<template>
  <NConfigProvider :theme="theme" :theme-overrides="themeOverrides" class="app-theme-root">
    <div class="app">
      <BrandBar
        :cluster="cluster"
        :namespace="namespace"
        :namespaces="namespaces"
        :helm-release="helmRelease"
        :helm-release-list="helmReleaseList"
        :auth-user="authUser"
        :dark="dark"
        @update:namespace="(v) => (namespace = v)"
        @update:helm-release="(v) => (helmRelease = v)"
        @sign-in="showLogin = true"
        @sign-out="signOut"
        @toggle-theme="toggleTheme"
        @show-diagnostics="showDiagnostics = true"
      />
      <DiagnosticsModal v-model="showDiagnostics" :cluster="cluster" />

      <div class="body">
        <Sidebar
          :clusters="clusters"
          :cluster="cluster"
          :active-cluster="activeCluster"
          :nav="nav"
          :counts="mergedCounts"
          :favorites="favorites"
          :selected="selected"
          @set-cluster="
            (idv) => {
              cluster = idv;
              showClusters = false;
            }
          "
          :clusters-page-open="showClustersPage"
          @show-clusters="showClusters = true"
          @select="(i) => (selected = i)"
          @toggle-favorite="toggleFavorite"
        />

        <div class="content-col">
          <main class="content">
            <FailureNotice v-if="failure" :failure="failure" @retry="retryRead" />
            <!-- OUTSIDE the cluster guard, deliberately (GH#298): this is the page that adds
                 one, so gating it on a cluster existing made the empty install a dead end. -->
            <ClustersPage
              v-if="showClustersPage"
              :clusters="clusters"
              :current="cluster"
              :can-write="!!authUser"
              :empty-copy="clustersEmptyCopy"
              @select="openClusterFromPage"
              @changed="refreshClusters"
              @require-auth="showLogin = true"
            />
            <template v-else-if="cluster">
              <ClusterOverview
                v-if="showClusterOverview"
                :cluster="cluster"
                :name="activeCluster?.name ?? cluster"
                :master-url="activeCluster?.masterUrl"
                :namespace="namespace"
                :knows-kind="knowsKind"
                :authed="!!authUser"
                @navigate="navigateToKind"
                @navigate-state="navigateToState"
                @require-auth="showLogin = true"
              />
              <CategoryOverview
                v-else-if="overviewCategory"
                :cluster="cluster"
                :category="overviewCategory"
                :namespace="namespace"
                @navigate="navigateToKind"
                @navigate-state="navigateToState"
              />
              <HelmView
                v-else-if="showHelm"
                :cluster="cluster"
                :view="helmViewName"
                :authed="!!authUser"
                :open-resources="helmTarget"
                @navigate="navigateToKind"
                @resources-consumed="helmTarget = null"
                @require-auth="showLogin = true"
                @auth-expired="signOut"
              />
              <PortForwards
                v-else-if="id === NAV.portForwards"
                :cluster="cluster"
                :authed="!!authUser"
                @require-auth="showLogin = true"
              />
            </template>
            <!-- The list is a sibling of the block above rather than one of its branches, so
                 it needs the same exclusion: the Clusters page spans every cluster, and
                 rendering a per-cluster list underneath it puts two pages in one pane. -->
            <ResourceListView
              v-if="!showClustersPage && showList && selected"
              :selected="selected"
              :filtered="filtered"
              :objects="objects"
              :status-rows="statusRows"
              :query="query"
              :live="live"
              :table-cols="tableCols"
              :visible-cols="visibleCols"
              :hidden-cols="hiddenCols"
              :kept-cols="keptCols"
              :auto-hidden-cols="autoHiddenCols"
              :selection="selection"
              :selected-key="selectedKey"
              :loading="loading"
              :failed="failed"
              :scope="helmRelease ? helmRelease.name : null"
              :namespace="selected.namespaced ? namespace : null"
              :fetch-children="fetchChildrenFn"
              :access="access"
              @update:query="(v) => (query = v)"
              @toggle-col="toggleCol"
              @auto-hidden="(keys) => (autoHiddenCols = new Set(keys))"
              @clear-selection="selection = new Set()"
              @bulk-delete="bulkDelete"
              @update:selection="(keys) => (selection = new Set(keys))"
              @open="(o) => (detail = { resourceId: resourceIdFor(o), obj: o })"
              @namespace-click="(ns) => (namespace = ns)"
              @create="authUser ? (showCreate = true) : (showLogin = true)"
              @row-action="(a, o, c) => handleRowAction(selected!.id, a, o, c)"
            />
          </main>
          <DockArea
            v-if="cluster && dockSessions.length > 0"
            :cluster="cluster"
            :sessions="dockSessions"
            :active="activeSession"
            @activate="setActive"
            @close="closeDock"
            @toggle-float="toggleFloat"
          />
        </div>

        <Detail
          v-if="cluster && detail"
          :key="detail.resourceId + '/' + objKey(detail.obj)"
          :cluster="cluster"
          :resource-id="detail.resourceId"
          :obj="detail.obj"
          :initial-edit="detail.edit ?? false"
          :authed="authUser !== null"
          :access="detailAccess"
          @navigate="navigateToKind"
          @helm-release="navigateToHelmRelease"
          @auth-expired="signOut"
          @require-auth="showLogin = true"
          @open-object="openPodDetail"
          @row-action="(a, o, c) => handleRowAction(detail!.resourceId, a, o, c)"
          @close="detail = null"
        />
      </div>

      <AppFooter />

      <LoginModal v-if="showLogin" :on-submit="loginSubmit" @cancel="showLogin = false" />
      <CreateModal
        v-if="showCreate && cluster"
        :cluster="cluster"
        @close="showCreate = false"
        @auth-expired="onCreateAuthExpired"
      />
      <ForwardModal
        v-if="cluster && forward"
        :cluster="cluster"
        :kind="forward.kind"
        :namespace="forward.namespace"
        :name="forward.name"
        :ports="forward.ports"
        @close="forward = null"
        @started="onForwardStarted"
        @auth-expired="signOut"
      />

      <CommandPalette
        :show="paletteOpen"
        :clusters="clusters"
        :nav="nav"
        :active-cluster="cluster"
        :namespace="namespace"
        @pick="runCommand"
        @cancel="paletteOpen = false"
      />

      <DialogHost />
    </div>
  </NConfigProvider>
</template>
