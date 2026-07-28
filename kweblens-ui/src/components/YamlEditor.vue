<script setup lang="ts">
/**
 * A CodeMirror 6 YAML editor (Phase A of the editor redesign — replaces the plain
 * textarea). Line numbers, folding, undo history, bracket matching and YAML syntax
 * highlighting come from `basicSetup`; the chrome (background, gutter, selection, cursor)
 * is themed through the app's CSS variables so it follows the light/dark palette. The
 * `dark` flag is read from the <html> `kw-dark` class and kept in sync with a
 * MutationObserver, so CodeMirror's own dark-mode defaults match the app theme.
 *
 * Schema-aware completion/lint (codemirror-json-schema) lands in Phase B; this component
 * exposes the same `v-model:value` contract the old NInput used, so wiring it up is a
 * drop-in swap.
 */
import { yaml } from '@codemirror/lang-yaml';
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { basicSetup } from 'codemirror';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

const props = defineProps<{ value: string }>();
const emit = defineEmits<{ (e: 'update:value', v: string): void }>();

const host = ref<HTMLDivElement | null>(null);
let view: EditorView | null = null;
const themeCompartment = new Compartment();

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
        yaml(),
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
