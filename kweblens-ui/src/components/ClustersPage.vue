<script setup lang="ts">
// The Clusters page: every cluster with its origin and API server, plus add / edit / remove
// for the ones added at runtime.
//
// This is what the cluster-selection design review recommended instead of growing the rail
// (docs/design/cluster-selection.md). The rail cannot scale for two separate reasons: its
// tiles are `id.slice(0, 2)` with no collision handling — on this box six clusters render as
// DE K3 KI KI KI KI — and `.rail` has no overflow, so past a viewport's worth the extras are
// unreachable. A page has room for the full name, the API server and the controls.
//
// Since GH#298 this page renders OUTSIDE the shell's `v-if="cluster"` guard, and is the
// landing page when there is no cluster: it is the only surface that can add one, so putting
// it behind "a cluster exists" made a zero-cluster install unrecoverable from the browser.
// That is also why `emptyCopy` is a prop — the reasons a list can be empty (still loading, the
// fetch failed, genuinely none) are known upstream, and each needs a different sentence.
//
// Emits: select (cluster id), changed () — the list was mutated, refetch upstream —
//        require-auth () — the reader has to sign in before they can add anything
import { NButton, NInput, NPopconfirm } from 'naive-ui';
import { computed, ref } from 'vue';

import { api } from '../api';
import { filterRows, summarise, toRows } from '../clustersPage';
import { noMatchEmpty, type EmptyStateCopy } from '../emptyState';
import type { ClusterInfo, ClusterDefinition } from '../types';
import ClusterEditModal from './ClusterEditModal.vue';
import EmptyState from './EmptyState.vue';
import ErrorNotice from './ErrorNotice.vue';

const props = defineProps<{
  clusters: ClusterInfo[];
  current: string | null;
  canWrite: boolean;
  emptyCopy?: EmptyStateCopy | null;
}>();
const emit = defineEmits<{ (e: 'select', id: string): void; (e: 'changed'): void; (e: 'require-auth'): void }>();

const query = ref('');
const error = ref<string | null>(null);
const busy = ref<string | null>(null);
const editing = ref<{ definition: ClusterDefinition; isNew: boolean } | null>(null);

const rows = computed(() => toRows(props.clusters, props.current));
const shown = computed(() => filterRows(rows.value, query.value));

// Which empty state, if any. "You have no clusters" and "your filter hid them all" are
// different situations with different exits, so they are never collapsed into one line.
const empty = computed<EmptyStateCopy | null>(() => {
  if (shown.value.length > 0) {
    return null;
  }
  return rows.value.length ? noMatchEmpty(query.value, 'cluster') : (props.emptyCopy ?? null);
});

const startAdd = () => {
  editing.value = { definition: { id: '', name: '', context: null, kubeconfig: '' }, isNew: true };
};

const runEmptyAction = (kind: 'add-cluster' | 'sign-in') => {
  if (kind === 'add-cluster') {
    startAdd();
  } else {
    emit('require-auth');
  }
};

const startEdit = (id: string, name: string) => {
  // The kubeconfig is deliberately absent: the server never returns it, and leaving it
  // blank on update means "keep the stored one".
  editing.value = { definition: { id, name, context: null }, isNew: false };
};

const remove = (id: string) => {
  busy.value = id;
  error.value = null;
  api
    .removeCluster(id)
    .then(() => emit('changed'))
    .catch((e: unknown) => (error.value = String(e)))
    .finally(() => (busy.value = null));
};

const onSaved = () => {
  editing.value = null;
  emit('changed');
};
</script>

<template>
  <div class="clusters-page">
    <header class="cp-head">
      <div>
        <h2 class="cp-title">Clusters</h2>
        <!-- With no clusters at all the empty state below carries the message; a subtitle
             saying the same thing twice is how a page reads as boilerplate. -->
        <p v-if="rows.length" class="cp-sub">{{ summarise(rows) }}</p>
      </div>
      <!-- Nothing to filter, and the empty state below already carries the add — a header
           offering both would put two "Add cluster" buttons on one screen. -->
      <div v-if="rows.length" class="cp-actions">
        <NInput v-model:value="query" placeholder="Filter by name, id or server" clearable style="width: 260px" />
        <NButton v-if="canWrite" type="primary" @click="startAdd">Add cluster</NButton>
      </div>
    </header>

    <ErrorNotice v-if="error" :message="error" @retry="error = null" />

    <p v-if="!canWrite && !empty" class="cp-note">
      Sign in to add, edit or remove clusters — every change is a write, so it needs the admin login.
    </p>

    <EmptyState v-if="empty" :title="empty.title" :body="empty.body">
      <template v-if="empty.action" #action>
        <NButton type="primary" @click="runEmptyAction(empty.action.kind)">{{ empty.action.label }}</NButton>
      </template>
    </EmptyState>

    <table v-else class="cp-table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Id</th>
          <th>API server</th>
          <th>Source</th>
          <th class="cp-right">Actions</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in shown" :key="r.id" :class="r.current ? 'cp-row current' : 'cp-row'">
          <td>
            <button type="button" class="cp-link" @click="emit('select', r.id)">{{ r.name }}</button>
            <span v-if="r.current" class="cp-current-tag">current</span>
          </td>
          <td class="cp-mono">{{ r.id }}</td>
          <td class="cp-mono cp-url">{{ r.masterUrl }}</td>
          <td>
            <span :title="r.lockedReason ?? ''">{{ r.editable ? 'Added at runtime' : 'Configuration' }}</span>
          </td>
          <td class="cp-right">
            <template v-if="canWrite && r.editable">
              <NButton size="tiny" @click="startEdit(r.id, r.name)">Edit</NButton>
              <NPopconfirm @positive-click="remove(r.id)">
                <template #trigger>
                  <NButton size="tiny" :loading="busy === r.id">Remove</NButton>
                </template>
                Remove “{{ r.name }}”? Its stored credential is deleted. The cluster itself is untouched.
              </NPopconfirm>
            </template>
            <span v-else-if="canWrite" class="cp-locked" :title="r.lockedReason ?? ''">Read-only</span>
          </td>
        </tr>
      </tbody>
    </table>

    <ClusterEditModal
      v-if="editing"
      :definition="editing.definition"
      :is-new="editing.isNew"
      :existing-ids="rows.map((r) => r.id)"
      @saved="onSaved"
      @cancel="editing = null"
    />
  </div>
</template>
