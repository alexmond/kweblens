<script setup lang="ts">
// The drawer's structured (form) editor: edit labels, annotations and — for ConfigMaps and
// Secrets — data, without touching raw YAML. Saves a JSON Merge Patch of only the changed
// sections (removed keys sent as null), so nothing else on the object is clobbered. Secret
// values are shown decoded and re-encoded (base64, UTF-8) on save. Live updates refresh the
// original values, so `dirty` clears once the server reflects the change.
//
// Emits: auth-expired () — a save came back 401/403; the shell must re-prompt for creds.
import { NButton } from 'naive-ui';
import { computed, ref, watch } from 'vue';

import { ApiError, api } from '../api';
import { objName, objNs } from '../kube';
import type { KubeObject } from '../types';
import KeyValueEditor from './KeyValueEditor.vue';

const props = defineProps<{ cluster: string; resourceId: string; obj: KubeObject; authed: boolean }>();
const emit = defineEmits<{ (e: 'auth-expired'): void }>();

const kind = computed(() => props.obj.kind ?? '');
const isSecret = computed(() => kind.value === 'Secret');
const hasData = computed(() => kind.value === 'ConfigMap' || isSecret.value);

// UTF-8-safe base64 for Secret data (best-effort: non-text values fall back to the raw string).
const decodeB64 = (b64: string): string => {
  try {
    return new TextDecoder().decode(Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)));
  } catch {
    return b64;
  }
};
const encodeB64 = (text: string): string => btoa(String.fromCharCode(...new TextEncoder().encode(text)));

const origLabels = computed<Record<string, string>>(() => props.obj.metadata?.labels ?? {});
const origAnnotations = computed<Record<string, string>>(() => props.obj.metadata?.annotations ?? {});
const origData = computed<Record<string, string>>(() => {
  const raw = (props.obj.data as Record<string, string> | undefined) ?? {};
  return isSecret.value ? Object.fromEntries(Object.entries(raw).map(([k, v]) => [k, decodeB64(v)])) : raw;
});

const labels = ref<Record<string, string>>({});
const annotations = ref<Record<string, string>>({});
const data = ref<Record<string, string>>({});
const busy = ref(false);
const msg = ref<string | null>(null);
const msgErr = ref(false);

const resetForm = () => {
  labels.value = { ...origLabels.value };
  annotations.value = { ...origAnnotations.value };
  data.value = { ...origData.value };
  msg.value = null;
};

// Re-seed when a different object is shown (not on every live tick of the same one).
watch(() => [objName(props.obj), objNs(props.obj), kind.value] as const, resetForm, { immediate: true });

const changed = (a: Record<string, string>, b: Record<string, string>) => JSON.stringify(a) !== JSON.stringify(b);

// Merge-patch a map: every edited entry, plus null for keys that were removed.
const mapPatch = (orig: Record<string, string>, edited: Record<string, string>, transform?: (v: string) => string) => {
  const patch: Record<string, string | null> = {};
  for (const k of Object.keys(orig)) {
    if (!(k in edited)) {
      patch[k] = null;
    }
  }
  for (const [k, v] of Object.entries(edited)) {
    patch[k] = transform ? transform(v) : v;
  }
  return patch;
};

const dirty = computed(
  () =>
    changed(origLabels.value, labels.value) ||
    changed(origAnnotations.value, annotations.value) ||
    (hasData.value && changed(origData.value, data.value)),
);

const save = async () => {
  busy.value = true;
  msg.value = null;
  msgErr.value = false;
  const patch: Record<string, unknown> = {};
  const meta: Record<string, unknown> = {};
  if (changed(origLabels.value, labels.value)) {
    meta.labels = mapPatch(origLabels.value, labels.value);
  }
  if (changed(origAnnotations.value, annotations.value)) {
    meta.annotations = mapPatch(origAnnotations.value, annotations.value);
  }
  if (Object.keys(meta).length) {
    patch.metadata = meta;
  }
  if (hasData.value && changed(origData.value, data.value)) {
    patch.data = mapPatch(origData.value, data.value, isSecret.value ? encodeB64 : undefined);
  }
  try {
    await api.patch(props.cluster, props.resourceId, objNs(props.obj), objName(props.obj), patch);
    msg.value = 'saved';
  } catch (e) {
    msgErr.value = true;
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      emit('auth-expired');
      msg.value = 'Authentication failed — sign in again.';
    } else {
      msg.value = String(e);
    }
  } finally {
    busy.value = false;
  }
};
</script>

<template>
  <div class="form-tab">
    <section class="form-section">
      <h4>Labels</h4>
      <KeyValueEditor v-model="labels" key-placeholder="label" value-placeholder="value" />
    </section>
    <section class="form-section">
      <h4>Annotations</h4>
      <KeyValueEditor v-model="annotations" key-placeholder="annotation" value-placeholder="value" />
    </section>
    <section v-if="hasData" class="form-section">
      <h4>{{ isSecret ? 'Data — values shown decoded' : 'Data' }}</h4>
      <KeyValueEditor v-model="data" key-placeholder="key" value-placeholder="value" :secret="isSecret" />
    </section>
    <div class="form-actions">
      <NButton type="primary" size="small" :loading="busy" :disabled="!authed || !dirty || busy" @click="save">
        Save
      </NButton>
      <NButton size="small" :disabled="!dirty || busy" @click="resetForm">Reset</NButton>
      <span v-if="!authed" class="form-hint">Sign in to edit.</span>
      <span v-if="msg" :class="'act-msg' + (msgErr ? ' err' : '')">{{ msg }}</span>
    </div>
  </div>
</template>
