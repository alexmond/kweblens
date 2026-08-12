/**
 * How tall a multiline field should START, in rows.
 *
 * Every free-text field that can hold multiline content is a resizable `<textarea>` rather than
 * a single-line input — a ConfigMap `data` value routinely holds a PEM certificate, an
 * annotation holds a whole JSON manifest, and a one-line input renders those as a 1-character
 * peephole. Resizable means the reader can pull the corner; this decides where the pull starts
 * from, so the common case needs no pull at all.
 *
 * It counts REAL lines — newline-separated — and nothing else. It deliberately does not try to
 * predict wrapped lines: how many visual rows a long line occupies is a function of the
 * rendered width and the font, neither of which exists here, and this project has already been
 * burned once by a glyph-width guess that was 20% out (`ui-measure`, 2026-08-02). A single very
 * long line therefore starts at `min` and wraps inside the box; growing it is one pull away.
 *
 * `max` exists so a 4 000-line ConfigMap does not open a field taller than the dialog. Past it
 * the textarea scrolls, which is what a reader expects of a box that is already large.
 */
export function initialRows(value: string | null | undefined, min: number, max: number): number {
  if (max < min) {
    throw new Error(`initialRows: max (${max}) is below min (${min})`);
  }
  // A trailing newline is how a text file ends, not a blank line the reader wants shown, so
  // it does not earn a row. Without this every YAML draft opened one row taller than its
  // content and the last row was always empty.
  const text = (value ?? '').replace(/\n$/, '');
  const lines = text === '' ? 1 : text.split('\n').length;
  return Math.min(max, Math.max(min, lines));
}
