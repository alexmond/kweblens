<script setup lang="ts">
import { NButton, NDataTable, NInput } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { computed, h, ref } from 'vue';

import { api } from '../api';
import { failureNotice, isSessionExpiry } from '../apiFailure';
import { useAsyncData } from '../composables/useAsyncData';
import { useDialog } from '../dialog';
import ErrorNotice from './ErrorNotice.vue';
import KebabMenu from './KebabMenu.vue';
import type { KebabItem } from './helm-types';

// Configured chart repositories: add / edit (remove+re-add) / refresh / remove. All writes
// are auth-gated; a failed login surfaces via the auth-expired event.
//
// Events emitted:
//   (e: 'require-auth'): void    — an unauthenticated user attempted a write
//   (e: 'auth-expired'): void    — kweblens refused the login (401, or a bodyless 403); a
//                                  coded 403 is the cluster's verdict and stays in the pane
const props = defineProps<{ authed: boolean }>();
const emit = defineEmits<{ (e: 'require-auth'): void; (e: 'auth-expired'): void }>();

const dialog = useDialog();
const refreshKey = ref(0);
// Three states, not two: `loading` stops whether the request succeeds or fails, so an error
// can never be shown over a spinner that never stops.
const {
  data: repos,
  loading,
  error,
  reload,
} = useAsyncData(
  () => refreshKey.value,
  () => api.helmRepos(),
);
const name = ref('');
const url = ref('');
const busy = ref(false);

const fail = (e: unknown) => {
  if (isSessionExpiry(e)) {
    emit('auth-expired');
  }
  error.value = failureNotice(e);
};

const add = () => {
  if (!props.authed) {
    emit('require-auth');
    return;
  }
  if (!name.value.trim() || !url.value.trim()) {
    return;
  }
  busy.value = true;
  error.value = null;
  api
    .helmAddRepo(name.value.trim(), url.value.trim())
    .then(() => {
      name.value = '';
      url.value = '';
      refreshKey.value += 1;
    })
    .catch(fail)
    .finally(() => (busy.value = false));
};

const remove = (repo: string) => {
  if (!props.authed) {
    emit('require-auth');
    return;
  }
  dialog
    .confirm({
      title: 'Remove repository',
      message: `Remove repository ${repo}?`,
      confirmLabel: 'Remove',
      danger: true,
    })
    .then((ok) => {
      if (!ok) {
        return;
      }
      busy.value = true;
      api
        .helmRemoveRepo(repo)
        .then(() => (refreshKey.value += 1))
        .catch(fail)
        .finally(() => (busy.value = false));
    });
};

const editRepo = (repo: string, currentUrl: string) => {
  if (!props.authed) {
    emit('require-auth');
    return;
  }
  dialog
    .prompt({
      title: 'Edit repository',
      message: `New URL for repository "${repo}":`,
      label: 'URL',
      initial: currentUrl,
      placeholder: 'https://charts.example.com',
      confirmLabel: 'Save',
    })
    .then((next) => {
      if (!next || !next.trim() || next.trim() === currentUrl) {
        return;
      }
      busy.value = true;
      api
        .helmRemoveRepo(repo)
        .then(() => api.helmAddRepo(repo, next.trim()))
        .then(() => (refreshKey.value += 1))
        .catch(fail)
        .finally(() => (busy.value = false));
    });
};

const refresh = (repo: string) => {
  if (!props.authed) {
    emit('require-auth');
    return;
  }
  busy.value = true;
  api
    .helmRefreshRepo(repo)
    .then(() => (refreshKey.value += 1))
    .catch(fail)
    .finally(() => (busy.value = false));
};

const items = (r: { name: string; url: string }): KebabItem[] => [
  { label: 'Edit', onClick: () => editRepo(r.name, r.url) },
  { label: 'Refresh', onClick: () => refresh(r.name) },
  { label: 'Remove', danger: true, onClick: () => remove(r.name) },
];

const cmp = (k: 'name' | 'url') => (a: { name: string; url: string }, b: { name: string; url: string }) =>
  a[k].localeCompare(b[k], undefined, { numeric: true });

const columns = computed<DataTableColumns<{ name: string; url: string }>>(() => [
  { title: 'Name', key: 'name', sorter: cmp('name'), defaultSortOrder: 'ascend', render: (r) => r.name },
  { title: 'URL', key: 'url', sorter: cmp('url'), className: 'mono', render: (r) => r.url },
  { title: '', key: '_menu', width: 44, render: (r) => h(KebabMenu, { items: items(r) }) },
]);

const rowKey = (r: { name: string; url: string }) => r.name;
</script>

<template>
  <div class="content-head">
    <span class="count">{{ repos ? `${repos.length} repositories` : '' }}</span>
  </div>
  <ErrorNotice v-if="error" :message="error" :retrying="loading" @retry="reload" />
  <div v-if="authed" class="repo-add">
    <NInput v-model:value="name" placeholder="name" :disabled="busy" style="max-width: 200px" />
    <NInput v-model:value="url" placeholder="https://charts.example.com" :disabled="busy" class="repo-url" />
    <NButton type="primary" :disabled="busy || !name.trim() || !url.trim()" @click="add">Add repository</NButton>
  </div>
  <NDataTable :columns="columns" :data="repos ?? []" :row-key="rowKey" :loading="loading" size="small">
    <template #empty>
      {{ loading ? 'Loading…' : error ? 'Could not load repositories.' : 'No repositories. Add one above.' }}
    </template>
  </NDataTable>
</template>
