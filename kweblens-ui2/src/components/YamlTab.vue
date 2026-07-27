<script setup lang="ts">
// The drawer's YAML tab: lazy-loads the manifest, view/edit + server-side apply.
//
// Emits (mirrors the React `onAuthExpired` callback prop):
//   auth-expired ()   — an apply came back 401/403; the shell must re-prompt for creds
import { computed, ref, watch } from 'vue';

import { ApiError, api } from '../api';
import { stripManagedFields } from '../kube';
import YamlView from './YamlView.vue';

const props = defineProps<{
  cluster: string;
  resourceId: string;
  name: string;
  ns: string;
  initialEdit: boolean;
  authed: boolean;
}>();
const emit = defineEmits<{ (e: 'auth-expired'): void }>();

const yaml = ref<string | null>(null);
const yamlError = ref<string | null>(null);
const hideManaged = ref(true);
const copied = ref(false);
const editing = ref(false);
const draft = ref('');
const busy = ref(false);
const msg = ref<string | null>(null);
const msgErr = ref(false);

watch(
  () => [props.cluster, props.resourceId, props.name, props.ns],
  (_now, _prev, onCleanup) => {
    let cancelled = false;
    onCleanup(() => (cancelled = true));
    api
      .yaml(props.cluster, props.resourceId, props.name, props.ns || undefined)
      .then((t) => !cancelled && (yaml.value = t))
      .catch((e) => !cancelled && (yamlError.value = String(e)));
  },
  { immediate: true },
);

const displayYaml = computed(() =>
  yaml.value === null ? null : hideManaged.value ? stripManagedFields(yaml.value) : yaml.value,
);

// Opened via the row "Edit" action → drop straight into edit once the manifest loads.
const autoEditDone = ref(false);
watch(
  () => [props.initialEdit, yaml.value, yamlError.value, displayYaml.value] as const,
  () => {
    if (props.initialEdit && !autoEditDone.value && yaml.value !== null && !yamlError.value) {
      autoEditDone.value = true;
      draft.value = displayYaml.value ?? '';
      editing.value = true;
    }
  },
  { immediate: true },
);

const copy = () => {
  if (displayYaml.value) {
    navigator.clipboard?.writeText(displayYaml.value).then(
      () => {
        copied.value = true;
        window.setTimeout(() => (copied.value = false), 1200);
      },
      () => undefined,
    );
  }
};

const startEdit = () => {
  draft.value = displayYaml.value ?? '';
  editing.value = true;
};

const applyYaml = async () => {
  busy.value = true;
  msg.value = null;
  msgErr.value = false;
  try {
    const r = await api.apply(props.cluster, draft.value);
    yaml.value = draft.value;
    editing.value = false;
    msg.value = `applied ${r.kind}/${r.name}`;
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
  <div class="yaml-pane">
    <div class="yaml-toolbar">
      <template v-if="editing">
        <button class="btn primary" :disabled="busy" @click="applyYaml">Apply</button>
        <button class="btn" :disabled="busy" @click="editing = false">Cancel</button>
      </template>
      <template v-else>
        <button class="btn" :disabled="!yaml" @click="copy">{{ copied ? 'Copied' : 'Copy' }}</button>
        <button v-if="authed" class="btn" :disabled="!yaml" @click="startEdit">Edit</button>
        <label class="yaml-toggle" title="Hide the verbose metadata.managedFields block">
          <input v-model="hideManaged" type="checkbox" />
          <span class="switch" />
          Hide Managed Fields
        </label>
      </template>
    </div>
    <div v-if="yamlError" class="error">{{ yamlError }}</div>
    <div v-if="!yamlError && yaml === null" class="empty">Loading…</div>
    <YamlView v-if="displayYaml !== null && !editing" :text="displayYaml" />
    <textarea v-if="editing" v-model="draft" class="yaml-edit" :spellcheck="false" />
    <div v-if="msg" :class="'act-msg' + (msgErr ? ' err' : '')">{{ msg }}</div>
  </div>
</template>
