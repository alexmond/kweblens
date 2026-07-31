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
// Emits: select (cluster id), changed () — the list was mutated, refetch upstream
import { NButton, NInput, NPopconfirm } from 'naive-ui';
import { computed, ref } from 'vue';

import { api } from '../api';
import { filterRows, summarise, toRows } from '../clustersPage';
import type { ClusterInfo, ClusterDefinition } from '../types';
import ClusterEditModal from './ClusterEditModal.vue';
import ErrorNotice from './ErrorNotice.vue';

const props = defineProps<{ clusters: ClusterInfo[]; current: string | null; canWrite: boolean }>();
const emit = defineEmits<{ (e: 'select', id: string): void; (e: 'changed'): void }>();

const query = ref('');
const error = ref<string | null>(null);
const busy = ref<string | null>(null);
const editing = ref<{ definition: ClusterDefinition; isNew: boolean } | null>(null);

const rows = computed(() => toRows(props.clusters, props.current));
const shown = computed(() => filterRows(rows.value, query.value));

const startAdd = () => {
  editing.value = { definition: { id: '', name: '', context: null, kubeconfig: '' }, isNew: true };
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
        <p class="cp-sub">{{ summarise(rows) }}</p>
      </div>
      <div class="cp-actions">
        <NInput v-model:value="query" placeholder="Filter by name, id or server" clearable style="width: 260px" />
        <NButton v-if="canWrite" type="primary" @click="startAdd">Add cluster</NButton>
      </div>
    </header>

    <ErrorNotice v-if="error" :message="error" @retry="error = null" />

    <p v-if="!canWrite" class="cp-note">
      Sign in to add, edit or remove clusters — every change is a write, so it needs the admin login.
    </p>

    <table class="cp-table">
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
        <tr v-if="!shown.length">
          <td colspan="5" class="cp-empty">
            {{ rows.length ? `No cluster matches “${query}”.` : 'No clusters configured.' }}
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
