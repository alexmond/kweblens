<script setup lang="ts">
// The drawer's YAML tab: lazy-loads the manifest and shows it read-only (embedded).
// Editing happens in a separate pop-out window (YamlEditorModal), not inline in the drawer.
//
// Emits (mirrors the React `onAuthExpired` callback prop):
//   auth-expired ()   — an apply came back 401/403; the shell must re-prompt for creds
import { NButton, NSwitch } from 'naive-ui';
import { shallowRef, computed, ref, watch } from 'vue';

import { api } from '../api';
import { stripManagedFields } from '../kube';
import YamlEditorModal from './YamlEditorModal.vue';
import YamlView from './YamlView.vue';

const props = defineProps<{
  cluster: string;
  resourceId: string;
  name: string;
  ns: string;
  initialEdit: boolean;
  authed: boolean;
}>();
// `editing` tells the parent drawer the pop-out editor is open, so it can suppress its
// close-on-outside-click / Escape (the drawer is non-modal and would otherwise close —
// and take this editor, its child — the moment you click inside the editor).
const emit = defineEmits<{ (e: 'auth-expired'): void; (e: 'editing', v: boolean): void }>();

const yaml = ref<string | null>(null);
const yamlError = ref<string | null>(null);
const hideManaged = ref(true);
const copied = ref(false);
const showEditor = ref(false);
const draft = ref('');
const schema = shallowRef<Record<string, unknown> | null>(null);
const msg = ref<string | null>(null);
const msgErr = ref(false);

// The kind's JSON Schema (cluster OpenAPI) powers editor completion/lint/hover. Fetched
// once per resource kind, best-effort — the editor works fine without it.
watch(
  () => props.resourceId,
  (id) => {
    schema.value = null;
    api
      .schema(props.cluster, id)
      .then((s) => (schema.value = s))
      .catch(() => undefined);
  },
  { immediate: true },
);

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

const editorTitle = computed(() => `${props.authed ? 'Edit' : 'View'} — ${props.name}`);

const openEditor = () => {
  draft.value = displayYaml.value ?? '';
  showEditor.value = true;
  emit('editing', true);
};
const closeEditor = () => {
  showEditor.value = false;
  emit('editing', false);
};

// Opened via the row "Edit" action → pop the editor open once the manifest loads.
const autoEditDone = ref(false);
watch(
  () => [props.initialEdit, yaml.value, yamlError.value, displayYaml.value] as const,
  () => {
    if (props.initialEdit && !autoEditDone.value && yaml.value !== null && !yamlError.value) {
      autoEditDone.value = true;
      openEditor();
    }
  },
  { immediate: true },
);

// Apply succeeded in the pop-out → refresh the embedded read-only view.
const onApplied = (text: string) => {
  yaml.value = text;
  msgErr.value = false;
  msg.value = 'applied';
};

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
</script>

<template>
  <div class="yaml-pane">
    <div class="yaml-toolbar">
      <NButton size="small" :disabled="!yaml" @click="copy">{{ copied ? 'Copied' : 'Copy' }}</NButton>
      <NButton size="small" :disabled="!yaml" @click="openEditor">{{ authed ? 'Edit ⤢' : 'View ⤢' }}</NButton>
      <label class="yaml-toggle" title="Hide the verbose metadata.managedFields block">
        <NSwitch v-model:value="hideManaged" size="small" />
        Hide Managed Fields
      </label>
    </div>
    <div v-if="yamlError" class="error">{{ yamlError }}</div>
    <div v-if="!yamlError && yaml === null" class="empty">Loading…</div>
    <YamlView v-if="displayYaml !== null" :text="displayYaml" />
    <div v-if="msg" :class="'act-msg' + (msgErr ? ' err' : '')">{{ msg }}</div>

    <YamlEditorModal
      v-if="showEditor"
      :cluster="cluster"
      :title="editorTitle"
      :initial-text="draft"
      :schema="schema"
      :readonly="!authed"
      @applied="onApplied"
      @auth-expired="emit('auth-expired')"
      @close="closeEditor"
    />
  </div>
</template>
