<script setup lang="ts">
// The drawer's YAML tab: lazy-loads the manifest and shows it read-only (embedded).
// Editing happens in a separate pop-out window (YamlEditorModal), not inline in the drawer.
//
// Emits (mirrors the React `onAuthExpired` callback prop):
//   auth-expired ()   — an apply came back 401/403; the shell must re-prompt for creds
import { NButton, NSwitch } from 'naive-ui';
import { shallowRef, computed, ref, watch } from 'vue';

import { api } from '../api';
import { useAsyncData } from '../composables/useAsyncData';
import { stripManagedFields } from '../kube';
import { forwardsLabelClick } from '../labelForward';
import type { KindAccess } from '../types';
import ErrorNotice from './ErrorNotice.vue';
import LoadingNotice from './LoadingNotice.vue';
import YamlEditorModal from './YamlEditorModal.vue';
import YamlView from './YamlView.vue';

const props = defineProps<{
  cluster: string;
  resourceId: string;
  name: string;
  ns: string;
  initialEdit: boolean;
  authed: boolean;
  /**
   * What the deployment's service account may do with this kind here (#354). Passed straight
   * through to the editor, whose Apply is the only write on this tab; null leaves it enabled.
   */
  access?: KindAccess | null;
}>();
// `editing` tells the parent drawer the pop-out editor is open, so it can suppress its
// close-on-outside-click / Escape (the drawer is non-modal and would otherwise close —
// and take this editor, its child — the moment you click inside the editor).
const emit = defineEmits<{ (e: 'auth-expired'): void; (e: 'editing', v: boolean): void }>();

// Fetching the manifest is a read, so the failure gets a Retry. The APPLY that this tab can
// launch is not in this slot at all — it lives in the pop-out editor and reports through
// `msg` below — which is what keeps the Retry here honest: it re-runs a GET and nothing else.
const {
  data: yaml,
  loading: yamlLoading,
  error: yamlError,
  reload: reloadYaml,
} = useAsyncData(
  () => [props.cluster, props.resourceId, props.name, props.ns],
  () => api.yaml(props.cluster, props.resourceId, props.name, props.ns || undefined),
);
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

// The words beside the switch operate it (GH#506). `forwardsLabelClick` is what keeps a click on
// the SWITCH ITSELF from counting twice — it toggles on its own, and the same click then bubbles
// to this handler; a second toggle would put the state back and read as a switch that has
// stopped working. The decision lives in `labelForward.ts` so it is tested without a DOM.
const onToggleClick = (e: MouseEvent) => {
  if (forwardsLabelClick(e.target, e.currentTarget)) hideManaged.value = !hideManaged.value;
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
      <!-- Not a `<label>`: `NSwitch` renders a `<div role="switch">`, which is not labelable, so
           the browser had nothing to forward a click to (`label.control === null`) while
           `.yaml-toggle` painted `cursor: pointer` over the words — GH#506. The forward is
           explicit now, and the switch names itself for assistive tech, which the dead label
           never did. -->
      <span class="yaml-toggle" title="Hide the verbose metadata.managedFields block" @click="onToggleClick">
        <NSwitch v-model:value="hideManaged" size="small" aria-label="Hide Managed Fields" />
        Hide Managed Fields
      </span>
    </div>
    <ErrorNotice v-if="yamlError" :message="yamlError" :retrying="yamlLoading" @retry="reloadYaml" />
    <LoadingNotice v-if="!yamlError && yamlLoading" />
    <YamlView v-if="displayYaml !== null" :text="displayYaml" />
    <div v-if="msg" :class="'act-msg' + (msgErr ? ' err' : '')">{{ msg }}</div>

    <YamlEditorModal
      v-if="showEditor"
      :cluster="cluster"
      :title="editorTitle"
      :initial-text="draft"
      :schema="schema"
      :readonly="!authed"
      :access="access"
      @applied="onApplied"
      @auth-expired="emit('auth-expired')"
      @close="closeEditor"
    />
  </div>
</template>
