// ---- The responsive primitive: named CONTAINER breakpoints (#231) ----
//
// The detail drawer is 520px wide or 1605px wide at the SAME viewport width, because the
// user expanded it (#230). A viewport `@media` query cannot see that, so responsive rules
// in this app are CSS **container** queries and this module is the one place that says what
// the names mean.
//
// Two names, one boundary. A pane is either:
//   narrow — under 900px: one column, the way everything renders today.
//   wide   — 900px and up: room for a ~560px main column, a 24px gutter and a ~300px aside.
//
// 900 rather than a rounder number because below it the split costs more than it buys: the
// widest table in the drawer (Containers: name, image, ports, requests, ready, restarts)
// needs ~560px before the image column starts wrapping, and a metadata aside is not worth
// having under ~300px. Under 900 those two do not both fit, so one column is the better
// answer — including at the drawer's 520px default, which stays narrow.
//
// HOW A COMPONENT ASKS "AM I WIDE?" — it does not measure. It sits inside an element that
// carries the `kw-pane` class (which is what `container-type: inline-size` is attached to)
// and writes a container query against that pane:
//
//     @container kw-pane (min-width: 900px) { … }        /* wide   */
//     @container kw-pane not (min-width: 900px) { … }    /* narrow */
//
// or, in a template, uses the `kw-when-wide` / `kw-when-narrow` helpers in styles.css.
// No ResizeObserver, no width prop threaded down, and — because the query is answered by
// the pane's own width — it is correct whether the drawer is 520px or expanded to 1605px.
//
// `paneWidth()` below is the same answer in TypeScript, for code that already holds a width
// in px and must branch on it. Nothing needs it yet; it exists so that a future caller
// cannot invent a second threshold. `responsive.test.ts` fails if styles.css and this file
// disagree about what "wide" is.

/** The container name every pane declares, and every `@container` rule queries. */
export const PANE_CONTAINER = 'kw-pane';

/** The single boundary between `narrow` and `wide`, in CSS pixels. */
export const PANE_WIDE_PX = 900;

/** The two — and only two — named pane widths. */
export type PaneWidth = 'narrow' | 'wide';

/** Which named width a pane of `px` is. The TS twin of the container query. */
export function paneWidth(px: number): PaneWidth {
  return px >= PANE_WIDE_PX ? 'wide' : 'narrow';
}
