<script setup lang="ts">
// The pop-out editor dialog. One document (the YAML draft) edited through tabs:
//   Editor          — the CodeMirror YAML editor (schema completion/lint/hover)
//   Form            — a visual key/value editor for labels/annotations/data, synced to the YAML
//   Warnings        — the schema linter's findings as a list (badge shows the count)
//   Review Changes  — TWO diffs: the edit against what was loaded, and (from `dryRun=All`)
//                     what the cluster says it would actually store — defaulting, other
//                     managers' fields and admission all show up here rather than after the write
// Apply is a single server-side apply of the draft.
//
// Emits:
//   applied (text)   — apply succeeded; the parent refreshes its embedded view
//   auth-expired ()  — apply returned 401/403; the shell must re-prompt for creds
//   close ()         — dismissed, or apply succeeded (after a brief confirmation)
import { NButton, NModal, NTabPane, NTabs } from 'naive-ui';
import { shallowRef, computed, ref, watch } from 'vue';

import { ApiError, api } from '../api';
import type { EditorDiagnostic } from '../types';
import { stripManagedFields } from '../kube';
import { requestServerPreview, serverPreviewCaption, type ServerPreviewState } from '../serverPreview';
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

// The second diff: what the CLUSTER would store, not just what was typed (T1).
//
// Fetched when the Review tab is opened rather than on every keystroke — it is a real
// round-trip to the API server, and the answer is only interesting once you have stopped
// editing. Re-asked whenever the draft has moved on since the last answer, so what is on
// screen is never an answer about a manifest that no longer exists.
const preview = ref<ServerPreviewState>({ status: 'idle' });
const previewedText = ref<string | null>(null);

const askCluster = async () => {
  const asked = draft.value;
  preview.value = { status: 'loading' };
  const result = await requestServerPreview((m) => api.applyDryRun(props.cluster, m), asked);
  // Ignore an answer about a draft the user has already edited past.
  if (draft.value !== asked) {
    return;
  }
  previewedText.value = asked;
  preview.value = result;
};

watch(tab, (t) => {
  if (t === 'review' && (preview.value.status === 'idle' || previewedText.value !== draft.value)) {
    void askCluster();
  }
});

// BOTH sides go through the same normalisation, or the diff is not a diff.
//
// The server's answer carries `managedFields` — a per-manager record of which field each
// one owns, rewritten on every apply — while the YAML the editor loaded has them stripped
// for display. Diffing one against the other buries the actual change under a block of
// ownership bookkeeping that nobody edited and nobody can act on. Measured against a real
// cluster: a two-line ConfigMap came back with nine lines of managedFields.
const liveNormalised = computed(() => stripManagedFields(original));
const wouldBe = computed(() => (preview.value.status === 'ready' ? stripManagedFields(preview.value.yaml) : ''));
const previewCaption = computed(() => serverPreviewCaption(preview.value, wouldBe.value !== liveNormalised.value));

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
        <div class="review-split">
          <section class="review-half">
            <h4 class="review-h">Your edit</h4>
            <p class="review-cap">Edited YAML against what was loaded. This is what you changed.</p>
            <DiffView :original="original" :modified="draft" />
          </section>
          <section class="review-half">
            <h4 class="review-h">
              What the cluster would store
              <button v-if="preview.status !== 'loading'" type="button" class="review-recheck" @click="askCluster">
                Re-check
              </button>
            </h4>
            <p :class="'review-cap' + (preview.status === 'refused' ? ' review-cap-refused' : '')">
              {{ previewCaption }}
            </p>
            <!-- The refusal IS the result, so it gets the space the diff would have had
                 rather than a toast that scrolls away. -->
            <pre v-if="preview.status === 'refused'" class="review-refused">{{ preview.message }}</pre>
            <p v-else-if="preview.status === 'failed'" class="review-cap">{{ preview.message }}</p>
            <DiffView v-else-if="preview.status === 'ready'" :original="liveNormalised" :modified="wouldBe" />
          </section>
        </div>
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
