<script setup lang="ts">
import { NButton, NInput } from 'naive-ui';
import { ref, watch } from 'vue';

// A key/value grid: edit, add and remove entries. v-model is a Record<string, string>.
// Internally it holds an ordered row list so keys can be edited freely (including transient
// blank/duplicate keys) and emits the reconstructed record (blank keys dropped, last wins).
const props = defineProps<{
  modelValue: Record<string, string>;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  secret?: boolean;
}>();
const emit = defineEmits<{ (e: 'update:modelValue', v: Record<string, string>): void }>();

interface Row {
  k: string;
  v: string;
}
const rows = ref<Row[]>([]);

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
  () => props.modelValue,
  (model) => {
    if (JSON.stringify(asRecord()) !== JSON.stringify(model ?? {})) {
      rows.value = Object.entries(model ?? {}).map(([k, v]) => ({ k, v }));
    }
  },
  { immediate: true, deep: true },
);

const publish = () => emit('update:modelValue', asRecord());
const addRow = () => rows.value.push({ k: '', v: '' });
const removeRow = (i: number) => {
  rows.value.splice(i, 1);
  publish();
};
</script>

<template>
  <div class="kv-editor">
    <div v-for="(row, i) in rows" :key="i" class="kv-row">
      <NInput v-model:value="row.k" size="small" :placeholder="keyPlaceholder ?? 'key'" @update:value="publish" />
      <NInput
        v-model:value="row.v"
        size="small"
        :type="secret ? 'password' : 'text'"
        :show-password-on="secret ? 'click' : undefined"
        :placeholder="valuePlaceholder ?? 'value'"
        @update:value="publish"
      />
      <NButton size="small" quaternary type="error" title="Remove" @click="removeRow(i)">✕</NButton>
    </div>
    <NButton size="small" dashed @click="addRow">+ Add</NButton>
  </div>
</template>
