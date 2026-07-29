<script setup lang="ts">
import { NSelect } from 'naive-ui';
import { computed } from 'vue';

import type { HelmRelease } from '../types';

// The top brand bar: namespace + Helm-release filters (Naive NSelect) and the sign-in box.
// Emits: update:namespace(v|null), update:helmRelease({namespace,name}|null), sign-in(), sign-out(), toggle-theme()
const props = defineProps<{
  cluster: string | null;
  namespace: string | null;
  namespaces: string[];
  helmRelease: { namespace: string; name: string } | null;
  helmReleaseList: HelmRelease[];
  authUser: string | null;
  dark: boolean;
}>();
const emit = defineEmits<{
  (e: 'update:namespace', v: string | null): void;
  (e: 'update:helmRelease', v: { namespace: string; name: string } | null): void;
  (e: 'sign-in'): void;
  (e: 'sign-out'): void;
  (e: 'toggle-theme'): void;
}>();

const nsOptions = computed(() => [
  { label: 'All namespaces', value: '' },
  ...props.namespaces.map((n) => ({ label: n, value: n })),
]);
const helmOptions = computed(() => [
  { label: 'All releases', value: '' },
  ...props.helmReleaseList.map((r) => ({ label: `${r.name} · ${r.namespace}`, value: `${r.namespace}/${r.name}` })),
]);
const helmValue = computed(() => (props.helmRelease ? `${props.helmRelease.namespace}/${props.helmRelease.name}` : ''));

// public/ asset — referenced via BASE_URL (bound src) so Vite serves it instead of bundling it.
const logoUrl = import.meta.env.BASE_URL + 'favicon.svg';

const onHelm = (v: string) => {
  if (!v) {
    emit('update:helmRelease', null);
    return;
  }
  const slash = v.indexOf('/');
  emit('update:helmRelease', { namespace: v.slice(0, slash), name: v.slice(slash + 1) });
};
</script>

<template>
  <header class="brandbar">
    <div class="brand">
      <img class="logo" :src="logoUrl" alt="" width="24" height="24" />
      <span class="name">kweb<span class="accent">lens</span></span>
      <span class="tag">web Kubernetes IDE</span>
    </div>
    <div v-if="cluster" class="bar-filters">
      <label class="bar-filter">
        <span>Namespace</span>
        <NSelect
          :value="namespace ?? ''"
          :options="nsOptions"
          size="small"
          style="width: 180px"
          @update:value="(v) => emit('update:namespace', v || null)"
        />
      </label>
      <label class="bar-filter">
        <span>Helm</span>
        <NSelect :value="helmValue" :options="helmOptions" size="small" style="width: 220px" @update:value="onHelm" />
      </label>
    </div>
    <div class="bar-right">
      <button
        class="linkbtn theme-toggle"
        :title="dark ? 'Switch to light' : 'Switch to dark'"
        @click="emit('toggle-theme')"
      >
        {{ dark ? '☀' : '☾' }}
      </button>
      <span v-if="authUser" class="authbox">
        <i class="user-dot" /> {{ authUser }}
        <button class="linkbtn" @click="emit('sign-out')">Sign out</button>
      </span>
      <button v-else class="linkbtn" @click="emit('sign-in')">Sign in</button>
    </div>
  </header>
</template>
