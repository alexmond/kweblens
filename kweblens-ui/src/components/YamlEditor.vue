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
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { tags as t } from '@lezer/highlight';
import { basicSetup } from 'codemirror';
import { yamlSchema } from 'codemirror-json-schema/yaml';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

// `schema` (a JSON Schema for the object's kind, from the cluster's OpenAPI) turns on
// schema-aware completion, lint and hover; without it the editor is plain YAML.
const props = defineProps<{ value: string; schema?: Record<string, unknown> | null }>();
const emit = defineEmits<{ (e: 'update:value', v: string): void }>();

const host = ref<HTMLDivElement | null>(null);
let view: EditorView | null = null;
const themeCompartment = new Compartment();
const langCompartment = new Compartment();

// yamlSchema() bundles the yaml() language + linter + completion + hover + schema state, so
// it REPLACES the plain yaml() language when a schema is present (never add both).
const langExtension = () => (props.schema ? yamlSchema(props.schema as Parameters<typeof yamlSchema>[0]) : [yaml()]);

// Chrome themed via CSS variables → follows the app's light/dark palette automatically.
const baseTheme = EditorView.theme({
  '&': { backgroundColor: 'var(--panel)', color: 'var(--text)', height: '100%' },
  '.cm-scroller': {
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '12.5px',
    lineHeight: '1.55',
    overflow: 'auto',
  },
  '.cm-gutters': {
    backgroundColor: 'var(--panel)',
    color: 'var(--muted)',
    border: 'none',
    borderRight: '1px solid var(--border)',
  },
  '.cm-activeLine': { backgroundColor: 'color-mix(in srgb, var(--accent) 7%, transparent)' },
  '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--text)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground, .cm-content ::selection': {
    backgroundColor: 'color-mix(in srgb, var(--accent) 24%, transparent)',
  },
  '.cm-cursor': { borderLeftColor: 'var(--text)' },
  '.cm-foldPlaceholder': { backgroundColor: 'transparent', border: 'none', color: 'var(--muted)' },
});

// Syntax colours via CSS variables (see .cm-syntax-* in styles.css) so both light and dark
// themes get readable tokens. The default basicSetup highlight style is tuned for light
// backgrounds — its dark-navy keys are near-invisible on the app's dark panel — so this
// overrides it. Added after basicSetup, so it takes precedence.
const highlightStyle = HighlightStyle.define([
  { tag: [t.definition(t.propertyName), t.propertyName, t.attributeName], color: 'var(--cm-key)' },
  { tag: [t.string, t.special(t.string)], color: 'var(--cm-string)' },
  { tag: [t.number], color: 'var(--cm-number)' },
  { tag: [t.bool, t.null, t.keyword, t.atom], color: 'var(--cm-atom)' },
  { tag: [t.comment, t.lineComment, t.blockComment], color: 'var(--cm-comment)', fontStyle: 'italic' },
  { tag: [t.meta, t.documentMeta, t.punctuation, t.separator], color: 'var(--cm-meta)' },
]);

const isDark = () => document.documentElement.classList.contains('kw-dark');
const themeFor = () => EditorView.theme({}, { dark: isDark() });

function build() {
  if (!host.value) {
    return;
  }
  view = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.value,
      extensions: [
        basicSetup,
        langCompartment.of(langExtension()),
        syntaxHighlighting(highlightStyle),
        baseTheme,
        themeCompartment.of(themeFor()),
        EditorView.updateListener.of((u) => {
          if (u.docChanged) {
            emit('update:value', u.state.doc.toString());
          }
        }),
      ],
    }),
  });
}

// Reflect an external value change (e.g. Cancel/reset, reload) without wiping the cursor
// on echoes of our own edits.
watch(
  () => props.value,
  (next) => {
    if (!view) {
      return;
    }
    const current = view.state.doc.toString();
    if (next !== current) {
      view.dispatch({ changes: { from: 0, to: current.length, insert: next } });
    }
  },
);

// The schema is fetched asynchronously — switch the language/schema extension in when it
// arrives (or back to plain YAML if it's cleared).
watch(
  () => props.schema,
  () => view?.dispatch({ effects: langCompartment.reconfigure(langExtension()) }),
);

let observer: MutationObserver | null = null;
onMounted(() => {
  build();
  observer = new MutationObserver(() => view?.dispatch({ effects: themeCompartment.reconfigure(themeFor()) }));
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
