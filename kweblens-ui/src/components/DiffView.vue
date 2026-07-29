<script setup lang="ts">
// Read-only side-by-side diff (original | edited) for the editor dialog's "Review Changes"
// tab — a last check before Apply. Built on @codemirror/merge with the shared YAML theme.
import { yaml } from '@codemirror/lang-yaml';
import { syntaxHighlighting } from '@codemirror/language';
import { MergeView } from '@codemirror/merge';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { onBeforeUnmount, onMounted, ref } from 'vue';

import { editorChrome, themeVariant, yamlHighlightStyle } from '../editor-theme';

const props = defineProps<{ original: string; modified: string }>();

const host = ref<HTMLDivElement | null>(null);
let mergeView: MergeView | null = null;

const sideExtensions = () => [
  yaml(),
  syntaxHighlighting(yamlHighlightStyle),
  editorChrome,
  themeVariant(),
  EditorView.editable.of(false),
  EditorState.readOnly.of(true),
  EditorView.lineWrapping,
];

onMounted(() => {
  if (!host.value) {
    return;
  }
  mergeView = new MergeView({
    parent: host.value,
    a: { doc: props.original, extensions: sideExtensions() },
    b: { doc: props.modified, extensions: sideExtensions() },
    gutter: true,
    highlightChanges: true,
    collapseUnchanged: { margin: 3, minSize: 4 },
  });
});
onBeforeUnmount(() => mergeView?.destroy());
</script>

<template>
  <div class="diff-view">
    <div class="diff-legend">
      <span class="diff-old">Current</span>
      <span class="diff-new">Edited</span>
    </div>
    <div v-if="original === modified" class="diff-empty">No changes.</div>
    <div v-show="original !== modified" ref="host" class="diff-host" />
  </div>
</template>
