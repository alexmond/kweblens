<script setup lang="ts">
import { NConfigProvider, darkTheme } from 'naive-ui';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { api } from './api';
import type { Command } from './commandPalette';
import { filesFeature } from './podFiles';
import { auth } from './auth';
import { useAppActions } from './composables/useAppActions';
import { useClusterScope } from './composables/useClusterScope';
import { useClusters } from './composables/useClusters';
import { useDock } from './composables/useDock';
import { useNavigation } from './composables/useNavigation';
import { useResourceData } from './composables/useResourceData';
import { defaultHiddenCols } from './columns';
import { useDialog } from './dialog';
import { objKey } from './kube';
import { loadDark, loadHiddenCols, loadNamespace, saveCluster, saveDark, saveNamespace } from './prefs';
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
import ForwardModal from './components/ForwardModal.vue';
import HelmView from './components/HelmView.vue';
import LoginModal from './components/LoginModal.vue';
import PortForwards from './components/PortForwards.vue';
import ResourceListView from './components/ResourceListView.vue';
import Sidebar from './components/Sidebar.vue';

// --- UI state (the shell owns selection/detail/query/auth/modals; data comes from composables) ---
const error = ref<string | null>(null);
const namespace = ref<string | null>(null);
const helmRelease = ref<{ namespace: string; name: string } | null>(null);
const selected = ref<NavItem | null>(null);
const detail = ref<{ resourceId: string; obj: KubeObject; edit?: boolean } | null>(null);
const hiddenCols = ref<Set<string>>(new Set());
const selection = ref<Set<string>>(new Set());
const query = ref('');
const authUser = ref<string | null>(null);
const showLogin = ref(false);
const showCreate = ref(false);
const showDiagnostics = ref(false);
const forward = ref<{ kind: string; namespace: string; name: string; ports: number[] } | null>(null);
const helmTarget = ref<{ namespace: string; name: string } | null>(null);

const setError = (e: string | null) => (error.value = e);
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
  };
});
const toggleTheme = () => {
  dark.value = !dark.value;
  saveDark(dark.value);
  document.documentElement.classList.toggle('kw-dark', dark.value);
};
document.documentElement.classList.toggle('kw-dark', dark.value);

const { clusters, cluster, refresh: refreshClusters } = useClusters(setError);
// Closed-mode: the session cookie outlives the in-memory creds. On load, restore an existing
// session (so write controls appear + data loads after a reload) and re-fetch once authed.
api
  .verifySession()
  .then((r) => {
    authUser.value = r.user;
    void refreshClusters();
  })
  .catch(() => undefined);
// Ask once, at startup, whether the pod file browser is on. Without this the Files tab can
// only learn by making a request that fails, so a drawer opened before that first failure
// shows a tab whose sole content is an explanation that it does not work. A public GET, so
// it works signed out too; a server that omits the field leaves the state unknown and the
// old learn-from-failure path still applies.
api
  .about()
  .then((info) => filesFeature.noteAbout(info))
  .catch(() => undefined);
const { nav, counts, helmCounts, namespaces, helmReleaseList, favorites, helmScope } = useClusterScope(
  cluster,
  namespace,
  helmRelease,
  setError,
);
const { objects, setObjects, loading, live, cols, usage, nodeDisk } = useResourceData(
  cluster,
  selected,
  namespace,
  setError,
);
const {
  sessions: dockSessions,
  active: activeSession,
  setActive,
  openDock,
  openLogs,
  closeDock,
  toggleFloat,
} = useDock();

// Reset the view when the active cluster, or the selected kind/namespace, changes.
watch(cluster, (id) => {
  selected.value = null;
  helmRelease.value = null;
  // Namespace is remembered PER CLUSTER: namespaces are cluster-local, so carrying one over
  // would filter on a namespace that may not exist here and silently show an empty list.
  namespace.value = id ? loadNamespace(id) : null;
  saveCluster(id);
});
watch([selected, namespace], () => {
  detail.value = null;
  query.value = '';
  // Restore this kind's saved column choice, falling back to its defaults (e.g. Nodes offers
  // more columns than fit — the extras stay available in the Columns ▾ picker). Previously
  // this always re-seeded from the defaults, so enabling an opt-in column was lost on the
  // next navigation.
  hiddenCols.value = loadHiddenCols(selected.value?.id, defaultHiddenCols(selected.value?.id));
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

// What the drawer is showing: the row the table highlights, and the object a delete has to
// close the drawer for (#233 put delete inside the drawer). One declaration, two consumers.
const selectedKey = computed(() => (detail.value ? objKey(detail.value.obj) : null));

const { signOut, fetchPods, handleRowAction, toggleFavorite, toggleCol, bulkDelete } = useAppActions({
  cluster,
  authUser,
  selected,
  selection,
  objects,
  hiddenCols,
  favorites,
  dialog,
  openDock,
  openLogs,
  setForward: (f) => (forward.value = f),
  setDetail: (d) => (detail.value = d),
  setError,
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

const runCommand = (command: Command) => {
  paletteOpen.value = false;
  if (command.kind === 'cluster') {
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
    setError(`Could not open ${hit.kind} ${hit.name}: ${String(e)}`);
  }
};

const activeCluster = computed(() => clusters.value.find((c) => c.id === cluster.value) ?? null);
const filtered = computed(() => filterObjects(objects.value, query.value, helmScope.value));
const tableCols = computed(() => buildResourceColumns(selected.value?.id, cols.value, usage.value, nodeDisk.value));
const visibleCols = computed(() => tableCols.value.filter((c) => !hiddenCols.value.has(c.key)));
const mergedCounts = computed(() => ({ ...counts.value, ...helmCounts.value }));

const id = computed(() => selected.value?.id);
const showClusterOverview = computed(() => (!selected.value || id.value === NAV.overviewCluster) && !error.value);
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

const loginSubmit = async (user: string, pass: string): Promise<boolean> => {
  auth.set(user, pass);
  try {
    await api.verifySession();
    authUser.value = user;
    showLogin.value = false;
    void refreshClusters();
    return true;
  } catch {
    auth.clear();
    return false;
  }
};
const onCreateAuthExpired = () => {
  signOut();
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
          :clusters-page-open="showClusters"
          @show-clusters="showClusters = true"
          @select="(i) => (selected = i)"
          @toggle-favorite="toggleFavorite"
        />

        <div class="content-col">
          <main class="content">
            <div v-if="error" class="error">{{ error }}</div>
            <template v-if="cluster">
              <ClustersPage
                v-if="showClusters"
                :clusters="clusters"
                :current="cluster"
                :can-write="!!authUser"
                @select="openClusterFromPage"
                @changed="refreshClusters"
              />
              <ClusterOverview
                v-else-if="showClusterOverview"
                :cluster="cluster"
                :name="activeCluster?.name ?? cluster"
                :master-url="activeCluster?.masterUrl"
                :namespace-count="namespaces.length"
                :namespace="namespace"
                :knows-kind="knowsKind"
                :authed="!!authUser"
                @navigate="navigateToKind"
                @require-auth="showLogin = true"
              />
              <CategoryOverview
                v-else-if="overviewCategory"
                :cluster="cluster"
                :category="overviewCategory"
                :namespace="namespace"
                @navigate="navigateToKind"
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
            <ResourceListView
              v-if="showList && selected"
              :selected="selected"
              :filtered="filtered"
              :objects="objects"
              :query="query"
              :live="live"
              :table-cols="tableCols"
              :visible-cols="visibleCols"
              :hidden-cols="hiddenCols"
              :selection="selection"
              :selected-key="selectedKey"
              :loading="loading"
              :fetch-children="fetchChildrenFn"
              @update:query="(v) => (query = v)"
              @toggle-col="toggleCol"
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
