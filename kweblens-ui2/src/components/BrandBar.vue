<script setup lang="ts">
import type { HelmRelease } from '../types';

// The top brand bar: namespace + Helm-release filters and the sign-in / sign-out box.
// Emits: update:namespace(v|null), update:helmRelease({namespace,name}|null), sign-in(), sign-out()
defineProps<{
  cluster: string | null;
  namespace: string | null;
  namespaces: string[];
  helmRelease: { namespace: string; name: string } | null;
  helmReleaseList: HelmRelease[];
  authUser: string | null;
}>();
const emit = defineEmits<{
  (e: 'update:namespace', v: string | null): void;
  (e: 'update:helmRelease', v: { namespace: string; name: string } | null): void;
  (e: 'sign-in'): void;
  (e: 'sign-out'): void;
}>();

const onNamespace = (e: Event) => emit('update:namespace', (e.target as HTMLSelectElement).value || null);
const onHelm = (e: Event) => {
  const v = (e.target as HTMLSelectElement).value;
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
      <span class="logo">◆</span> kweblens
      <span class="tag">web Kubernetes IDE · SPA</span>
    </div>
    <div v-if="cluster" class="bar-filters">
      <label class="bar-filter">
        <span>Namespace</span>
        <select :value="namespace ?? ''" @change="onNamespace">
          <option value="">All namespaces</option>
          <option v-for="n in namespaces" :key="n" :value="n">{{ n }}</option>
        </select>
      </label>
      <label class="bar-filter">
        <span>Helm</span>
        <select :value="helmRelease ? `${helmRelease.namespace}/${helmRelease.name}` : ''" @change="onHelm">
          <option value="">All releases</option>
          <option v-for="r in helmReleaseList" :key="`${r.namespace}/${r.name}`" :value="`${r.namespace}/${r.name}`">
            {{ r.name }} · {{ r.namespace }}
          </option>
        </select>
      </label>
    </div>
    <div class="bar-right">
      <span v-if="authUser" class="authbox">
        <i class="user-dot" /> {{ authUser }}
        <button class="linkbtn" @click="emit('sign-out')">Sign out</button>
      </span>
      <button v-else class="linkbtn" @click="emit('sign-in')">Sign in</button>
      <a class="switch" href="/">Classic UI ↗</a>
    </div>
  </header>
</template>
