<script setup lang="ts">
/**
 * A CodeMirror 6 YAML editor. Line numbers, folding, undo history, bracket matching and
 * YAML syntax highlighting come from `basicSetup`; the chrome (background, gutter,
 * selection, cursor) is themed through the app's CSS variables so it follows the
 * light/dark palette, kept in sync with a MutationObserver on the <html> `kw-dark` class.
 *
 * When a `schema` prop is supplied (the kind's JSON Schema, from the cluster's OpenAPI),
 * codemirror-json-schema adds schema-aware completion, lint and hover. Exposes a
 * `v-model:value` contract.
 */
import { yaml } from '@codemirror/lang-yaml';
import { syntaxHighlighting } from '@codemirror/language';
import { forEachDiagnostic } from '@codemirror/lint';
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { basicSetup } from 'codemirror';
import { yamlSchema } from 'codemirror-json-schema/yaml';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { editorChrome, themeVariant, yamlHighlightStyle } from '../editor-theme';
import type { EditorDiagnostic } from '../types';

// `schema` (a JSON Schema for the object's kind, from the cluster's OpenAPI) turns on
// schema-aware completion, lint and hover; without it the editor is plain YAML.
const value = defineModel<string>('value', { required: true });
const props = defineProps<{ schema?: Record<string, unknown> | null; readonly?: boolean }>();
const emit = defineEmits<{
  (e: 'diagnostics', d: EditorDiagnostic[]): void;
}>();

// The schema linter runs asynchronously; surface its diagnostics (for the Warnings tab)
// whenever they change, de-duped so cursor moves don't re-emit.
let lastSig = '';
const emitDiagnostics = (v: EditorView) => {
  const list: EditorDiagnostic[] = [];
  forEachDiagnostic(v.state, (d, from) => {
    list.push({ severity: d.severity, message: d.message, line: v.state.doc.lineAt(from).number, from });
  });
  const sig = JSON.stringify(list);
  if (sig !== lastSig) {
    lastSig = sig;
    emit('diagnostics', list);
  }
};

const host = ref<HTMLDivElement | null>(null);
let view: EditorView | null = null;
const themeCompartment = new Compartment();
const langCompartment = new Compartment();

// yamlSchema() bundles the yaml() language + linter + completion + hover + schema state, so
// it REPLACES the plain yaml() language when a schema is present (never add both).
const langExtension = () => (props.schema ? yamlSchema(props.schema as Parameters<typeof yamlSchema>[0]) : [yaml()]);

function build() {
  if (!host.value) {
    return;
  }
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: value.value,
      extensions: [
        basicSetup,
        langCompartment.of(langExtension()),
        syntaxHighlighting(yamlHighlightStyle),
        editorChrome,
        themeCompartment.of(themeVariant()),
        ...(props.readonly ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : []),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) {
            value.value = u.state.doc.toString();
          }
          emitDiagnostics(u.view);
        }),
      ],
    }),
  });
}

// Reflect an external value change (e.g. Cancel/reset, reload) without wiping the cursor
// on echoes of our own edits.
watch(value, (next) => {
  if (!view) {
    return;
  }
  const current = view.state.doc.toString();
  if (next !== current) {
    view.dispatch({ changes: { from: 0, to: current.length, insert: next } });
  }
});

// The schema is fetched asynchronously — switch the language/schema extension in when it
// arrives (or back to plain YAML if it's cleared).
watch(
  () => props.schema,
  () => view?.dispatch({ effects: langCompartment.reconfigure(langExtension()) }),
);

let observer: MutationObserver | null = null;
onMounted(() => {
  build();
  observer = new MutationObserver(() => view?.dispatch({ effects: themeCompartment.reconfigure(themeVariant()) }));
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
});
onBeforeUnmount(() => {
  observer?.disconnect();
  view?.destroy();
  view = null;
});
</script>

<template>
  <div ref="host" class="cm-yaml" />
</template>
