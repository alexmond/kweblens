import { HighlightStyle } from '@codemirror/language';
import { EditorView } from '@codemirror/view';
import { tags as t } from '@lezer/highlight';

// Shared CodeMirror theming for the YAML editor and the diff (review) view, so both match
// the app's light/dark palette. Chrome colours come from CSS variables; syntax colours come
// from the .cm-* variables set per-theme in styles.css (the default basicSetup highlight
// style is tuned for light backgrounds and is unreadable on the dark panel).

export const editorChrome = EditorView.theme({
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

export const yamlHighlightStyle = HighlightStyle.define([
  { tag: [t.definition(t.propertyName), t.propertyName, t.attributeName], color: 'var(--cm-key)' },
  { tag: [t.string, t.special(t.string)], color: 'var(--cm-string)' },
  { tag: [t.number], color: 'var(--cm-number)' },
  { tag: [t.bool, t.null, t.keyword, t.atom], color: 'var(--cm-atom)' },
  { tag: [t.comment, t.lineComment, t.blockComment], color: 'var(--cm-comment)', fontStyle: 'italic' },
  { tag: [t.meta, t.documentMeta, t.punctuation, t.separator], color: 'var(--cm-meta)' },
]);

/** Toggles CodeMirror's own dark-mode class, driven by the app's <html> `kw-dark` flag. */
const isDarkTheme = (): boolean => document.documentElement.classList.contains('kw-dark');
export const themeVariant = (): ReturnType<typeof EditorView.theme> => EditorView.theme({}, { dark: isDarkTheme() });
