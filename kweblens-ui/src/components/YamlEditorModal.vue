<script setup lang="ts">
// The pop-out editor dialog. One document (the YAML draft) edited through tabs:
//   Editor          — the CodeMirror YAML editor (schema completion/lint/hover)
//   Form            — a visual key/value editor for labels/annotations/data, synced to the YAML
//   Warnings        — the schema linter's findings as a list (badge shows the count)
//   Review Changes  — a read-only diff of the original vs the edited YAML, a last check before Apply
// Apply is a single server-side apply of the draft.
//
// Emits:
//   applied (text)   — apply succeeded; the parent refreshes its embedded view
//   auth-expired ()  — apply returned 401/403; the shell must re-prompt for creds
//   close ()         — dismissed, or apply succeeded (after a brief confirmation)
import { NButton, NModal, NTabPane, NTabs } from 'naive-ui';
import { shallowRef, computed, ref } from 'vue';

import { ApiError, api } from '../api';
import type { EditorDiagnostic } from '../types';
import DiffView from './DiffView.vue';
import FormFields from './FormFields.vue';
import YamlEditor from './YamlEditor.vue';

const props = defineProps<{
  cluster: string;
  title: string;
  initialText: string;
  schema?: Record<string, unknown> | null;
  // Read-only viewer (opened when signed out): the Editor tab is read-only, and the
  // editing tabs (Form / Warnings / Review) + Apply are hidden — just a big YAML viewer.
  readonly?: boolean;
}>();
const emit = defineEmits<{
  (e: 'applied', text: string): void;
  (e: 'auth-expired'): void;
  (e: 'close'): void;
}>();

const original = props.initialText;
const draft = ref(props.initialText);
const tab = ref<'editor' | 'form' | 'warnings' | 'review'>('editor');
const warnings = shallowRef<EditorDiagnostic[]>([]);
const busy = ref(false);
const msg = ref<string | null>(null);
const err = ref(false);

const errorCount = computed(() => warnings.value.filter((w) => w.severity === 'error').length);

// Size: a normal dialog, an expand-to-fill toggle, and drag-resize (CSS `resize` on the
// card — see .yaml-editor-modal in styles.css). Vue only patches style keys that actually
// change, so a size the user dragged sticks until they toggle expand.
const expanded = ref(false);
const modalStyle = computed(() =>
  expanded.value ? { width: '96vw', height: '94vh' } : { width: 'min(1100px, 92vw)', height: '72vh' },
);

const apply = async () => {
  busy.value = true;
  msg.value = null;
  err.value = false;
  try {
    const r = await api.apply(props.cluster, draft.value);
    emit('applied', draft.value);
    msg.value = `applied ${r.kind}/${r.name}`;
    window.setTimeout(() => emit('close'), 600);
  } catch (e) {
    err.value = true;
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

const onShow = (v: boolean) => {
  if (!v) {
    emit('close');
  }
};
</script>

<template>
  <NModal
    :show="true"
    preset="card"
    :title="title"
    :bordered="false"
    :mask-closable="false"
    class="yaml-editor-modal"
    :style="modalStyle"
    @update:show="onShow"
  >
    <template #header-extra>
      <button
        type="button"
        class="drawer-expand"
        :title="expanded ? 'Restore window size' : 'Expand to fill'"
        :aria-label="expanded ? 'Restore window size' : 'Expand to fill'"
        @click="expanded = !expanded"
      >
        {{ expanded ? '⤡' : '⤢' }}
      </button>
    </template>
    <NTabs v-model:value="tab" type="line" size="small" pane-class="editor-dialog-pane">
      <NTabPane name="editor" tab="Editor" display-directive="show">
        <YamlEditor v-model:value="draft" :schema="schema" :readonly="readonly" @diagnostics="(d) => (warnings = d)" />
      </NTabPane>
      <NTabPane v-if="!readonly" name="form" tab="Form" display-directive="if">
        <div class="dialog-scroll"><FormFields v-model="draft" :schema="schema" /></div>
      </NTabPane>
      <NTabPane v-if="!readonly" name="warnings" display-directive="if">
        <template #tab>
          Warnings
          <span v-if="warnings.length" class="warn-badge" :class="{ err: errorCount }">{{ warnings.length }}</span>
        </template>
        <div class="dialog-scroll warnings-list">
          <div v-if="!warnings.length" class="warnings-ok">No schema warnings.</div>
          <button
            v-for="(w, i) in warnings"
            :key="i"
            type="button"
            class="warning-row"
            :class="w.severity"
            @click="tab = 'editor'"
          >
            <span class="warning-loc">Line {{ w.line }}</span>
            <span class="warning-msg">{{ w.message }}</span>
          </button>
        </div>
      </NTabPane>
      <NTabPane v-if="!readonly" name="review" tab="Review Changes" display-directive="if">
        <DiffView :original="original" :modified="draft" />
      </NTabPane>
    </NTabs>
    <div v-if="msg" :class="'act-msg' + (err ? ' err' : '')">{{ msg }}</div>
    <template #footer>
      <div class="dialog-actions">
        <span v-if="errorCount" class="dialog-warn-hint"
          >{{ errorCount }} schema error(s) — review before applying</span
        >
        <NButton :disabled="busy" @click="emit('close')">{{ readonly ? 'Close' : 'Cancel' }}</NButton>
        <NButton v-if="!readonly" type="primary" :loading="busy" @click="apply">Apply</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.dialog-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}
.dialog-warn-hint {
  margin-right: auto;
  color: var(--warn, #d98a00);
  font-size: 12px;
}
</style>
