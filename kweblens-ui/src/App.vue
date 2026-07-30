<script setup lang="ts">
import { NConfigProvider, darkTheme } from 'naive-ui';
import { computed, ref, watch } from 'vue';

import { api } from './api';
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
import { HELM_VIEW_IDS, NAV, filterObjects, isSynthetic } from './shell';
import { buildResourceColumns } from './table';
import type { KubeObject, NavItem } from './types';

import AppFooter from './components/AppFooter.vue';
import BrandBar from './components/BrandBar.vue';
import ClusterOverview from './components/ClusterOverview.vue';
import CreateModal from './components/CreateModal.vue';
import Detail from './components/Detail.vue';
import DialogHost from './components/DialogHost.vue';
import DockArea from './components/DockArea.vue';
import ForwardModal from './components/ForwardModal.vue';
import HelmView from './components/HelmView.vue';
import LoginModal from './components/LoginModal.vue';
import PortForwards from './components/PortForwards.vue';
import ResourceListView from './components/ResourceListView.vue';
import Sidebar from './components/Sidebar.vue';
import WorkloadsOverview from './components/WorkloadsOverview.vue';

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
const forward = ref<{ kind: string; namespace: string; name: string; ports: number[] } | null>(null);
const helmTarget = ref<{ namespace: string; name: string } | null>(null);

const setError = (e: string | null) => (error.value = e);
const dialog = useDialog();

// Theme: Naive UI light/dark, toggled from the brand bar and persisted.
const dark = ref(localStorage.getItem('kw-theme') !== 'light');
const theme = computed(() => (dark.value ? darkTheme : null));
const toggleTheme = () => {
  dark.value = !dark.value;
  localStorage.setItem('kw-theme', dark.value ? 'dark' : 'light');
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
watch(cluster, () => {
  selected.value = null;
  namespace.value = null;
  helmRelease.value = null;
});
watch([selected, namespace], () => {
  detail.value = null;
  query.value = '';
  // Seed from the kind's defaultHidden columns (e.g. Nodes offers more than fits — the extras
  // stay available in the Columns ▾ picker).
  hiddenCols.value = defaultHiddenCols(selected.value?.id);
  selection.value = new Set();
});

const { navigateToKind, navigateToPortForwards, navigateToHelmRelease } = useNavigation(nav, {
  setSelected: (i) => (selected.value = i),
  setDetail: (d) => (detail.value = d),
  setNamespace: (ns) => (namespace.value = ns),
  setHelmTarget: (t) => (helmTarget.value = t),
});

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
});

// A pod clicked in a node's Pods tab: swap the drawer to that pod's detail. `pods` is the
// nav id for the Pod kind, so the YAML/actions in the drawer resolve against the right kind.
const openPodDetail = (obj: KubeObject) => {
  detail.value = { resourceId: 'pods', obj };
};

const activeCluster = computed(() => clusters.value.find((c) => c.id === cluster.value) ?? null);
const filtered = computed(() => filterObjects(objects.value, query.value, helmScope.value));
const tableCols = computed(() => buildResourceColumns(selected.value?.id, cols.value, usage.value, nodeDisk.value));
const visibleCols = computed(() => tableCols.value.filter((c) => !hiddenCols.value.has(c.key)));
const mergedCounts = computed(() => ({ ...counts.value, ...helmCounts.value }));

const id = computed(() => selected.value?.id);
const showClusterOverview = computed(() => (!selected.value || id.value === NAV.overviewCluster) && !error.value);
const showHelm = computed(() => id.value !== undefined && HELM_VIEW_IDS.includes(id.value));
const helmViewName = computed(() =>
  id.value === NAV.helmCharts ? 'charts' : id.value === NAV.helmRepositories ? 'repositories' : 'releases',
);
const showList = computed(() => selected.value && !isSynthetic(selected.value.id));
const selectedKey = computed(() => (detail.value ? objKey(detail.value.obj) : null));
const fetchChildrenFn = computed(() => (selected.value?.expandable ? fetchPods : undefined));

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
  <NConfigProvider :theme="theme" class="app-theme-root">
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
      />

      <div class="body">
        <Sidebar
          :clusters="clusters"
          :cluster="cluster"
          :active-cluster="activeCluster"
          :nav="nav"
          :counts="mergedCounts"
          :favorites="favorites"
          :selected="selected"
          @set-cluster="(idv) => (cluster = idv)"
          @select="(i) => (selected = i)"
          @toggle-favorite="toggleFavorite"
        />

        <div class="content-col">
          <main class="content">
            <div v-if="error" class="error">{{ error }}</div>
            <template v-if="cluster">
              <ClusterOverview
                v-if="showClusterOverview"
                :cluster="cluster"
                :name="activeCluster?.name ?? cluster"
                :master-url="activeCluster?.masterUrl"
                :namespace-count="namespaces.length"
              />
              <WorkloadsOverview v-else-if="id === NAV.overviewWorkloads" :cluster="cluster" />
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
              @open="(o) => (detail = { resourceId: selected!.id, obj: o })"
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
          @open-object="openPodDetail"
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

      <DialogHost />
    </div>
  </NConfigProvider>
</template>
