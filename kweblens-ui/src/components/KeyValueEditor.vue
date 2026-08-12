<script setup lang="ts">
import { NButton, NInput } from 'naive-ui';
import { ref, watch } from 'vue';

import { afterRemove, afterReseed, toggleReveal } from '../secretReveal';
import { initialRows } from '../textareaRows';

// A key/value grid: edit, add and remove entries. v-model is a Record<string, string>.
// Internally it holds an ordered row list so keys can be edited freely (including transient
// blank/duplicate keys) and emits the reconstructed record (blank keys dropped, last wins).
//
// The VALUE is a resizable textarea, the KEY is not. A key is one token by definition — a label
// name, an annotation name, a ConfigMap key — while a value routinely holds a PEM certificate,
// a JSON manifest (`last-applied-configuration` is an annotation) or a whole config file, and a
// single-line input renders those as a one-character peephole. `rows` opens the box at the
// content's own line count; past that the reader pulls the corner.
const model = defineModel<Record<string, string>>({ required: true });
defineProps<{
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  secret?: boolean;
}>();

interface Row {
  k: string;
  v: string;
}
const rows = ref<Row[]>([]);

// Which secret values the reader has chosen to see, by row index; the index bookkeeping is in
// `secretReveal.ts` because getting it wrong unmasks the wrong secret.
//
// Masked and multiline are a genuine conflict — a `<textarea>` cannot be `type="password"`, and
// the CSS that fakes it (`-webkit-text-security`) fails OPEN, which on this surface means
// printing the secret in clear text on any engine that does not implement it. So the mask keeps
// the single-line password input it has always had, and the resizable textarea appears only once
// the reader has explicitly revealed the value — at which point it is on screen anyway and a
// taller box exposes nothing further. The toggle is ours rather than naive's built-in eye,
// because that eye exists only for `type="password"` and cannot survive the swap to a textarea.
//
// Declared ABOVE the re-seed watch on purpose: that watch is `immediate`, so its callback runs
// during setup, and a `const` declared after it is still in the temporal dead zone — seeding a
// ConfigMap that actually had data threw `ReferenceError` before this moved up.
const revealed = ref<Set<number>>(new Set());

const asRecord = (): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const row of rows.value) {
    if (row.k) {
      out[row.k] = row.v;
    }
  }
  return out;
};

// Re-seed the rows only when the model structurally differs from what they represent, so a
// parent re-render (or our own echoed emit) doesn't wipe an in-progress edit.
watch(
  model,
  (next) => {
    if (JSON.stringify(asRecord()) !== JSON.stringify(next ?? {})) {
      rows.value = Object.entries(next ?? {}).map(([k, v]) => ({ k, v }));
      revealed.value = afterReseed();
    }
  },
  { immediate: true, deep: true },
);

const publish = () => (model.value = asRecord());
const addRow = () => rows.value.push({ k: '', v: '' });
const removeRow = (i: number) => {
  rows.value.splice(i, 1);
  revealed.value = afterRemove(revealed.value, i);
  publish();
};

const onToggleReveal = (i: number) => (revealed.value = toggleReveal(revealed.value, i));

// Rows track the value as it is typed, which reads as a box that grows with its content. A pull
// still wins: the drag writes an explicit height on naive's input wrapper, and `rows` only sets
// the textarea's intrinsic height inside it. Measured, both before and after the pull
// (`scripts/resize-check.mjs`) — this is exactly the "drawn but not durable" failure that check
// exists to catch, and it does not occur here.
const valueRows = (v: string) => initialRows(v, 1, 12);
</script>

<template>
  <div class="kv-editor">
    <div v-for="(row, i) in rows" :key="i" class="kv-row">
      <NInput v-model:value="row.k" size="small" :placeholder="keyPlaceholder ?? 'key'" @update:value="publish" />
      <NInput
        v-if="secret && !revealed.has(i)"
        v-model:value="row.v"
        size="small"
        type="password"
        :placeholder="valuePlaceholder ?? 'value'"
        @update:value="publish"
      />
      <NInput
        v-else
        v-model:value="row.v"
        size="small"
        type="textarea"
        :rows="valueRows(row.v)"
        :placeholder="valuePlaceholder ?? 'value'"
        @update:value="publish"
      />
      <div class="kv-actions">
        <NButton
          v-if="secret"
          size="small"
          quaternary
          :title="revealed.has(i) ? 'Hide value' : 'Reveal value'"
          @click="onToggleReveal(i)"
        >
          {{ revealed.has(i) ? 'Hide' : 'Show' }}
        </NButton>
        <NButton size="small" quaternary type="error" title="Remove" @click="removeRow(i)">✕</NButton>
      </div>
    </div>
    <NButton size="small" dashed @click="addRow">+ Add</NButton>
  </div>
</template>
