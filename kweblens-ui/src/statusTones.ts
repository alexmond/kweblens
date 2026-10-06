import type { StatusTone } from './columns';

/**
 * What a status pill PAINTS, per tone — the one place a tone becomes a colour.
 *
 * <p>Lifted out of `StatusBadge.vue` so it can be asserted on without a DOM (GH#393). Two
 * invariants live here and are pinned by `statusTones.test.ts`, because both had already gone
 * wrong silently:
 *
 * <ul>
 * <li><b>A tone with a colour but no way to be asked for is a colour nobody can measure.</b>
 * `ok` sat in this map for as long as the map existed and could never render: every caller
 * went through `badgeTone`, which mapped `ok` to `''` by the #240 convention (a pill marks
 * an exception; a healthy value is plain text). So the one tone that had never once been
 * contrast-measured was the one the app cannot paint — and nothing said so. The set of entries
 * here is exactly the set of tones `badgeTone` can hand over: change that convention and this
 * map must change with it, in the same commit. That is what just happened — `badgeTone` now
 * passes `ok` through, so `ok` is back in this map, and this time it is a tone the app really
 * paints. It is therefore a tone that must be MEASURED like the other two: `contrast-check.mjs`
 * carries a `.status-badge.tone-ok` scene, because an entry here is a promise about a colour
 * on a running page and this file can only pin its construction.
 * <li><b>The foreground is the tint's own `on-tint` token, never a re-picked literal.</b>
 * `--warn-fg` was designed to read on the PANEL, and on its own tint in the light theme it
 * measured 4.51:1 against a 4.5 floor — a pass by one hundredth, which any nudge to the tint
 * alpha or the panel colour would have taken under. `--warn-on-tint` is the derived token
 * `styles.css` already defines for text on a tint, so the pill and the list header's status
 * chips paint from one construction rather than two.
 * </ul>
 */
export const TONE_VARS: Partial<Record<StatusTone, { color: string; textColor: string }>> = {
  ok: { color: 'var(--ok-tint)', textColor: 'var(--ok-on-tint)' },
  warn: { color: 'var(--warn-tint)', textColor: 'var(--warn-on-tint)' },
  err: { color: 'var(--danger-tint)', textColor: 'var(--danger-on-tint)' },
};
