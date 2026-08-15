#!/usr/bin/env node
//
// Measure WCAG contrast of rendered UI against a running kweblens, in BOTH themes.
//
// This exists because eyeballing colour has failed repeatedly here: the StatusBadge tag
// shipped at 1.93:1 (#169), Naive's primary collided with the semantic palette (#184), and
// the command palette's first two stylings measured 3.02:1 and 3.80:1 while looking fine
// (#200). Those were all caught only by measuring actual rendered pixels — which is what
// this does.
//
// THE BACKDROP COMES FROM THE RENDERED IMAGE, NOT FROM THE DOM.
//
// Earlier versions derived "what is behind this text" by walking `parentElement` upward and
// compositing translucent layers down to the first opaque ancestor. That is right about the
// cascade and wrong about paint order. Naive UI renders a select's white box
// (`.n-base-selection-label`) as a *sibling* of the input, with the input overlaid on top:
// the white is painted underneath but is not an ancestor, so the walk climbed straight past
// it to the dark top bar and called a real 12.16:1 a **1.21:1 FAIL** (#250). Anything
// painted by a sibling — overlays, absolutely-positioned layers, z-index stacking — was
// invisible to it. A false FAIL in a gating tool is worse than no tool, because it teaches
// people to ignore the real failures.
//
// So the backdrop is now read off the screen: hide every glyph, screenshot the viewport,
// decode the pixels under each measured text run, and take the modal colour. That is ground
// truth by construction — it cannot be wrong about what is on screen.
//
//   * Glyphs are hidden (`color: transparent`) rather than dodged. The other trap here is a
//     sample point landing ON a letter and reporting the text colour as the background; that
//     has burned this project before. With the text painted away, every pixel in the run's
//     own rect is backdrop, so there is no sample point to get wrong.
//   * The MODE over the whole text-run rect, not one pixel: a gradient, a focus ring or a
//     stray icon moves a single sample and cannot move the mode.
//   * The DOM walk is kept as a CROSS-CHECK and any disagreement is reported. That
//     disagreement is exactly what surfaced #250, and it is the cheapest available alarm on
//     this instrument being wrong again.
//
// Prove changes to any of that with `--self-test` (below), never by eyeballing the output.
//
// Usage:
//   scripts/dev-run.sh
//   node scripts/contrast-check.mjs                      # the default watchlist below
//   node scripts/contrast-check.mjs '.leaf.active' '.ov-card.danger'
//   PORT=8085 node scripts/contrast-check.mjs
//   PREPARE='press:Control+k' node scripts/contrast-check.mjs '.palette-row.active'
//   node scripts/contrast-check.mjs --self-test          # positive controls; no running app
//
// PREPARE runs simple actions before sampling, semicolon-separated:
//   press:<key>   click:<selector>   fill:<selector>=<text>   wait:<ms>
//   upload:<file-input selector>=<path on this machine>
//   scroll:<selector>            bring a below-the-fold surface into the viewport
//   hover:<selector>             park the pointer on an element and measure its :hover state
//   close                        shut an open drawer/modal if there is one (no argument)
//   leaf:<nav label>             open a nav leaf, expanding the rail and categories first
//                                `Category/Label` where the label is not unique — six
//                                categories have an `Overview`, and an ambiguous one throws
//                                rather than silently opening the first (e.g. `leaf:Network/Overview`)
//   drawer:<px>                  drag the open drawer to a width in its own 360..1400 range
//   signin:<password> / signout  the admin login, idempotent
//   deny / allow                 stub `GET …/access` with a refusal, and take it down
//   partial / full               stub `GET …/diagnose` with a scope where the audit could not
//                                see everything (an `info` truncation notice and an RBAC read
//                                failure), and take it down
//
// The last two pairs are the only faked responses in this file, and each is faked because it
// cannot be produced here: an admin kubeconfig is allowed everything, and no cluster on this
// box refuses to list its own bindings. Without them those selectors are `not present`
// forever, which this tool's own summary calls a failed measurement and a reader calls a pass.
//
// `scroll:` and `leaf:` exist because this tool measures a RENDERED PIXEL, so anything below
// the fold or behind the nav could not be measured at all — it reported `outside the viewport`
// or died in a 30s "element is not visible" retry. Between them the app has no un-measurable
// screen left:
//   PREPARE='leaf:Deployments;click:.n-data-table-tbody tr' node scripts/contrast-check.mjs '.mini th'
//   PREPARE='scroll:.warn-table' node scripts/contrast-check.mjs '.warn-table .n-data-table-td'
//
// Prefix a step with `?` to skip it when its selector is not on screen. PREPARE runs once
// per theme, so a step that only applies the first time — signing in — otherwise stalls the
// second pass until it times out and takes the whole run with it:
//   PREPARE='?click:.bar-btn:has-text("Sign in");?fill:.n-modal input[type=password]=admin;…'
//
// A SELECTOR THAT SAMPLES NOTHING IS A FAILED MEASUREMENT, AND IT HAS TO SAY WHICH KIND.
//
// Rows with no ratio are counted and printed, never passed — that rule is as old as the
// scenes. What it could not say is *which* nothing: an instance with the file browser off and
// a scene that walked to the wrong page printed the same `not present`. Two watchlist entries
// lived in that gap for months (GH#389). Both were meant to watch a Naive tag; one named a
// selector so broad it resolved to a table pill BEHIND the drawer it was supposed to be
// measuring, and reported that pill's ratio under the drawer's name — a green line for a
// surface never once on screen, which is the failure this file's own summary warns about, and
// strictly worse than the red line it looked like an improvement on.
//
// A TONE IS COVERED BY NAME OR IT IS COVERED BY LUCK.
//
// The row status pill is one component painting three colours, and the colour arrives INLINE
// (`NTag`'s `:color`), so until GH#393 no stylesheet named it and the finest selector available
// was `.n-tag`. This tool samples the first match carrying text, so the tone it measured was
// whichever the row order put first: the danger tone on this box's cluster (5.62 / 6.56), the
// warn tone on the simulator (4.51 / 8.06). Both looked like passes, every tone was covered
// only because the two environments disagreed, and the warn tone's 0.01 of margin sat under
// whichever name happened to sort ahead of it. `StatusBadge` now carries a `tone-*` class
// beside the inline colour — a LABEL, not a second source of the colour — so each tone is
// addressed and reported under its own name.
//
// Naming a tone is only half of it: a tone that is not on screen must not read as a pass. That
// is `REQUIRED_WHEN` (below), which is the answer to "absent, or absent because this cluster is
// healthy?" — each entry names an ORACLE already on the page that says the tone exists here. For
// the pills that oracle is the list header's own status chip: a chip is drawn only for a state
// some row carries, so a chip with no measurable pill of the same tone is a scene that failed,
// not a cluster that is well. Those rows are counted as FAILURES and exit 1.
//
// So `WHY_ABSENT` (below) carries a reason per selector, appended to the empty row. It changes
// no count and passes nothing: it only separates "not applicable in this instance" from "we
// failed to look", which are the two things a reader has to act on differently. A selector
// with no entry is one nobody has explained, and that is the signal. It is appended to
// `not present` and to nothing else — an element that is on the page but off screen or under
// another layer is a scene that failed, and must never be able to borrow an excuse.
//
// Exit code is 1 if anything falls under its floor — AA, or the higher one a selector carries
// in FLOOR_OVERRIDE — or if a REQUIRED_WHEN selector produced nothing while its oracle was on
// the page. So it can gate a change.
//
// Needs the shared Playwright install (see the global setup notes), not a local one:
//   NODE_PATH=$HOME/.local/lib/playwright/node_modules node scripts/contrast-check.mjs

import { createRequire } from 'module';

import { resizeDrawer, resolveLeaf, splitFill } from './lib/kw-playwright.mjs';

const require = createRequire(process.env.HOME + '/.local/lib/playwright/node_modules/');
const { chromium } = require('playwright');

const PORT = process.env.PORT || '8080';
const URL = process.env.URL || `http://localhost:${PORT}/`;
const AA_NORMAL = 4.5;
const AA_LARGE = 3.0;
// How far the decoded pixel and the DOM walk may differ, per channel, before it is worth
// printing. 1 is normal rounding noise (the verified three-layer case computed rgb(41,63,80)
// where the browser painted rgb(40,63,79)); anything past 2 means one of them is wrong.
const AGREE_TOLERANCE = 2;

// Text-bearing things whose colours have been wrong before, or are easy to get wrong.
const DEFAULT_SELECTORS = [
  '.leaf.active',
  // The Warnings card's tinted variant. It reported `not present` for its whole existence, and
  // only half of that was the styling's fault: `StatCard` applies `danger` when the cluster has
  // Warning events, and the simulator seeded none, so a cluster-free run could not reach it. It
  // can now. (A `.ov-card.warn` entry sat here too and was deleted rather than kept: no code
  // adds that class and no rule styles it, so it was not an unmeasured check — it was a
  // selector that could never match, which is the "permanently unmeasured row" this file's own
  // summary warns about, dressed as a pass.)
  '.ov-card.danger',
  '.nav-badge',
  // `.count` and `.acc-count` are the other two pills, and both are checked — in the scenes
  // below, on the list and the drawer where they actually render. They used to sit here, where
  // they could only ever report `not present`.
  // Plain .ov-card, not just its .danger variant: the variant matched a <div> and passed
  // while the clickable <button> cards sat at 1.34:1 in dark mode. Measure the base class.
  '.ov-card',
  '.ov-kind',
  '.ov-num',
  // The footer bar stays dark in BOTH themes while its text came from the active palette, so
  // the light theme painted light-theme greys on a dark bar (2.48:1 / 3.21:1, #244). A
  // surface whose colour does not follow the theme needs watching in the theme it does not
  // follow.
  '.app-footer .ver-line',
  '.app-footer .repo-link',
  // The secondary-text family that hard-coded slate-400 instead of --muted and failed the
  // LIGHT theme at 2.37:1 while looking correct in the dark one it was picked against (#265).
  // `.ov-scope-note` is on every cluster and category overview.
  '.ov-scope-note',
];

/**
 * Screens the default run walks to before sampling, each `{ prepare, selectors }`.
 *
 * A watchlist is only a watchlist for what the run can SEE. Everything the colour bugs of
 * #260/#264/#265 lived on — the drawer's chips, the read-only YAML tab, a cluster-scoped
 * list's pill, the diagnostics modal, a button's hover pad — sits behind a click, so adding
 * those selectors to `DEFAULT_SELECTORS` would have printed a column of `not present`: the
 * "green line with half the run unmeasured" this file's own summary warns about, which is
 * indistinguishable from a pass. Each scene carries the PREPARE that brings its surface on
 * screen, so `node scripts/contrast-check.mjs` with no arguments measures all of them.
 *
 * Scenes run in order, in each theme, from whatever the previous one left behind — hence the
 * leading `close` to shut a drawer or modal. Requires the simulator's fixtures
 * (annotations, a TLS Ingress); against a cluster without them those rows say `not present`,
 * which is honest and counted.
 */
const SCENES = [
  {
    // Pods rather than ConfigMaps: the same annotation chips, plus a Containers table for
    // `.mini th`, plus a YAML document with LISTS in it — without which the next scene's
    // `.yk-dash` is `not present` and the run is quietly one selector short.
    name: 'drawer chips',
    // Leads with `allow` so a run always starts with no stubbed response in place. The
    // refused-actions scenes below install one, and PREPARE re-runs from scene 1 for the
    // second theme — without this, every scene in the second pass would be measuring a page
    // told that the service account can do nothing.
    prepare:
      'close;allow;full;leaf:Pods;click:.n-data-table-tbody tr;wait:800;' +
      '?click:.n-collapse-item__header:has-text("Annotations");wait:400',
    // `.n-tag` used to sit here, meaning "the drawer's Helm marker", and never once measured
    // it — see the `Helm-managed object` scene below, which measures it for real.
    selectors: ['.chip.subtle', '.acc-count', '.mini th'],
  },
  {
    name: 'read-only YAML tab',
    prepare: '?click:.n-tabs-tab:has-text("YAML");wait:1200',
    // 1.42:1 in dark before #265: a fixed GitHub-LIGHT palette on a block that has a dark
    // override, so the whole tab was unreadable in the theme it was never checked in.
    selectors: ['.yaml .yk-key', '.yaml .yk-str', '.yaml .yk-num', '.yaml .yk-dash'],
  },
  {
    name: 'TLS chips on an Ingress',
    prepare: 'close;leaf:Ingresses;click:.n-data-table-tbody tr;wait:900',
    selectors: ['.chip'],
  },
  {
    // The drawer's `<NTag type="info">Helm</NTag>` — Naive's own info palette, which measured
    // 3.36:1 in the light theme (#269). It is the one colour on this watchlist that comes from
    // the component library rather than from `styles.css`: the fix is a `Tag.textColorInfo`
    // override in `App.vue`, so a naive-ui bump can move it without touching a line of ours.
    // That is precisely the kind of colour a run has to keep looking at.
    //
    // It was watched as a bare `.n-tag` in the drawer-chips scene above, and measured the tag
    // in NEITHER environment (GH#389). Two independent reasons, and each hid the other:
    //
    //   * `.n-tag` is not a drawer selector. It matches every Naive tag on the page, and the
    //     sampler takes the first match that carries text — which is a row's status pill in
    //     the table BEHIND the drawer. On the simulator both watchlist rows therefore reported
    //     the same element, `8.06:1` / `4.51:1`, twice: a green line for a surface that was
    //     never on screen, which is worse than the red one it replaced.
    //   * On a live cluster there was no tag to find in the first place. `Managed By` renders
    //     off `meta.helm.sh/release-name`, and Helm writes that annotation on the objects a
    //     chart declares — not on the Pods a Deployment then creates. Measured against this
    //     box's cluster: 0 of 93 pods carry it, against 8 of 100 ConfigMaps and 29 of 66
    //     Services. The scene was pointed at the one kind where the tag cannot exist.
    //
    // So: a kind Helm installs directly, narrowed by the app's own label filter to the objects
    // that carry Helm's own `managed-by` label. On this cluster that is `8 of 100` ConfigMaps
    // and the first of them opens a drawer with the tag at y=248; on the simulator every
    // seeded object carries both the label and the annotation, so the filter is a no-op and
    // the same row is reached. The selector is scoped to `.n-drawer .kv` so it can only ever
    // resolve to the summary list's tag — a bare `.n-tag` is what made this unmeasured for
    // months while reporting a number.
    //
    // `?click` rather than `click`: a cluster with no Helm-installed ConfigMap has no row to
    // open, and that is a fact about the cluster, not a broken scene. It then reports the
    // WHY note below rather than a bare `not present`.
    name: 'Helm-managed object (drawer)',
    prepare:
      'close;leaf:Config Maps;wait:800;' +
      'fill:.content-head input=app.kubernetes.io/managed-by=Helm;wait:900;' +
      '?click:.n-data-table-tbody tr td:nth-child(2);wait:1400',
    selectors: ['.n-drawer .kv .n-tag'],
  },
  {
    // A resource LIST. `.count`, the status pill and the ACTIVE leaf's count badge only exist
    // here — on the overview the base pass samples they are all `not present`, so leaving them
    // in `DEFAULT_SELECTORS` bought a permanently-unmeasured row rather than a check.
    //
    // `.badge` was dropped from the watchlist here rather than moved: this file carried it
    // from the era when StatusBadge had its own class, and it has rendered a Naive `NTag`
    // since — so the entry had been matching nothing for months while reporting the same
    // `not present` a healthy cluster legitimately produces. Its live form is watched as
    // `.n-tag` in the drawer scene.
    //
    // The row-level STATUS pill has a scene of its own below, because reaching it scrolls the
    // list and `.count` is in the header that scrolls away with it.
    name: 'resource list',
    prepare: 'close;leaf:Pods;wait:800',
    selectors: ['.count', '.leaf.active .nav-badge'],
  },
  {
    // The list header's status chips — every tone the rows carry, each one named.
    //
    // These are the SAME token pairs the row pill paints (a state's tint, with the derived
    // `--*-on-tint` foreground), on the one surface where all of them are on screen at once and
    // above the fold, and they had never been measured at all. They are also the only place the
    // `ok` tone can be measured: `badgeTone` maps `ok` to no pill by the #240 convention (a pill
    // marks an exception; a healthy value is plain text), so an `ok` STATUS PILL does not exist
    // in any environment and cannot be brought on screen in one — which is why a run pointed at
    // pills alone could never cover the third tone, on this box's cluster or the simulator.
    //
    // A chip is drawn only for a state some row carries, so a tone with no rows has no chip;
    // that is a fact about the cluster, and it is what the pill scenes below take as their
    // oracle for whether a pill of that tone must exist.
    name: 'list status chips (every tone, by name)',
    prepare: 'close;leaf:Pods;wait:900;?fill:.content-head input=;wait:400',
    selectors: [
      '.status-chip.tone-ok',
      '.status-chip.tone-warn',
      '.status-chip.tone-err',
      '.status-chip.tone-idle',
    ],
  },
  {
    // The row-level STATUS pill — `StatusBadge`'s `NTag`, tinted from the semantic tokens —
    // ONE SCENE PER TONE, because the tone is the thing under test and a scene that samples
    // "the first pill" samples whatever sorted first (GH#393).
    //
    // It was unwatched for exactly as long as the simulator was perfectly healthy: no seeded
    // pod reached a state `StatusBadge` tones, so there was nothing to measure. The seeder now
    // puts about one pod in six into CrashLoopBackOff, ImagePullBackOff, Pending, OOMKilled,
    // Evicted or Completed, so on a cluster-free run several pills sit in the first screenful.
    //
    // On a LIVE cluster they do not, and that is what left this reporting `outside the
    // viewport` in both themes (GH#389). A tinted pill is drawn only for a row that is not
    // healthy, a live list is sorted by name, and healthy is the common case: measured against
    // this box's cluster there was exactly ONE pill in 93 pods, in the last row of the table,
    // 17px below the fold.
    //
    // `?scroll:` was the answer then and is not enough now, for a reason the tone split makes
    // unavoidable: two tones are two rows, in general at two scroll positions, and the list
    // VIRTUALISES past 50 filtered rows — a pill in a state that exists but is 400 rows down is
    // not in the DOM to be scrolled to. So the scene uses the app's own affordance instead and
    // clicks the tone's status chip, which installs `status:<Label>` and lifts a row in that
    // state to the top of the table. Deterministic, on screen, no scrolling, and it works the
    // same on 93 pods as on 3 000.
    //
    // `[data-col-key=status]`, because the STATUS column is not the only one that renders this
    // component: the Ready column badges `1/2` warn and `0/1` err from the same tokens. Without
    // the column scope this scene sampled a READY pill — the first match in DOM order, since
    // Ready comes first — under the name "row status pill", and the chip filter it just
    // installed had no bearing on what was measured. Same shape as GH#389's `.n-tag`: a
    // selector that resolves anywhere on the row is not a selector for the column the scene
    // narrowed. (The colours are the same either way, which is exactly what makes it hard to
    // notice, and what makes the CONTROL the only way to know: filtering to `status:Running`
    // leaves the Ready pills on screen, so an unscoped selector never fails.)
    //
    // The leading `fill:…input=` clears the query first: a chip whose state is already selected
    // CLEARS the filter when clicked (that is what makes it a toggle), and PREPARE re-runs for
    // the second theme from wherever the first left the page.
    name: 'row status pill: warn, by name',
    prepare: 'close;leaf:Pods;wait:900;?fill:.content-head input=;wait:400;?click:.status-chip.tone-warn;wait:800',
    selectors: ['[data-col-key=status] .status-badge.tone-warn'],
  },
  {
    // The same, for the danger tone. A second scene rather than a second selector: each one
    // filters the list to its own tone, so they cannot share a page.
    name: 'row status pill: err, by name',
    prepare: 'close;leaf:Pods;wait:900;?fill:.content-head input=;wait:400;?click:.status-chip.tone-err;wait:800',
    selectors: ['[data-col-key=status] .status-badge.tone-err'],
  },
  {
    // Every tone of an overview card's state list, HOVERED. Named per tone rather than as a
    // bare `.ov-state-l`, because the sampler takes the first match and the first line of the
    // first card is whichever state is most populous — so one selector watches one colour and
    // silently stops watching the other three. The hover matters because #338 made each of
    // these lines a <button>: a button does not inherit `color` (that is how `.ov-card.danger`
    // shipped at 1.34:1), and a hover pad with no dark override is how `.btn` shipped at
    // 1.16:1. The hover here is deliberately an underline and adds no colour — this scene is
    // what keeps that true.
    name: 'overview card states (hovered)',
    prepare: 'close;leaf:Workloads/Overview;wait:900;?hover:.ov-state.tone-err .ov-state-line',
    selectors: [
      '.ov-state.tone-ok .ov-state-l',
      '.ov-state.tone-warn .ov-state-l',
      '.ov-state.tone-err .ov-state-l',
      '.ov-state.tone-idle .ov-state-l',
      '.ov-state.tone-err .ov-state-n',
      '.ov-head-link .ov-kind',
    ],
  },
  {
    // `.ns-note` is the "Cluster-scoped" pill, so it needs a cluster-scoped kind.
    name: 'cluster-scoped list',
    prepare: 'close;leaf:Namespaces;wait:800',
    selectors: ['.ns-note'],
  },
  {
    name: 'diagnostics modal',
    prepare: 'close;click:.bar-btn[title^="Diagnostics"];wait:900',
    selectors: ['.diag-dim', '.diag-detail', '.diag-kv dt', '.diag-caps li.off .diag-name', '.diag-warn'],
  },
  {
    // The diagnosis panel's severity badges — all three at once, INCLUDING the two findings
    // that say the audit did not see everything (#381).
    //
    // Nothing here had ever measured `.dx-sev`, and the reason is the usual one: a badge is
    // only on screen when a cluster has that severity of finding. `info` is the hardest of the
    // three to produce on purpose, and its two most important emitters cannot be produced on
    // this box at all — "RBAC grants could not be read" needs an identity that may not list
    // bindings (an admin kubeconfig may), and "Further container privileges not listed" needs
    // more than MAX_CONTAINER_FINDINGS root or privileged containers in one scope. Waiting for
    // a cluster to supply them is how a selector stays `not present` forever, which this file
    // already counts as a failed measurement rather than a pass.
    //
    // So `partial` stubs the ONE response the panel reads — `GET …/diagnose` — with four
    // findings whose titles and details are copied from the emitters (`PodDiagnosis`,
    // `PodSecurity`, `SecurityAuditService`) rather than invented. The objects are named the
    // way the simulator names things, because a payload committed to this repo must not carry
    // a real cluster's namespaces. Everything downstream is the real thing: the real panel,
    // the real `diagnosis.ts` grouping, the real stylesheet.
    // Two scenes rather than one because `scroll:` is `scrollIntoViewIfNeeded`, which does the
    // MINIMAL scroll: the target lands against an edge, so everything after it stays off
    // screen. Measured, the four stubbed cards are 115-154px each and the run that scrolled to
    // the last one left the first 17px above the fold — the badge is at the top of a card, so
    // that reported `outside the viewport`, which is a failed sample, not a pass. Scrolling to
    // the SECOND card puts the first two fully on screen; scrolling to the last puts both info
    // cards on screen. Between them every severity is sampled, and nothing is inferred.
    //
    // The `leaf:Pods` hop is load-bearing and cost a run to find: a route installed mid-walk
    // only affects requests made AFTER it, and the panel had already fetched this scope on
    // load. `useAsyncData` keeps a value whose deps have not changed, so navigating back to a
    // page you are already on refetches nothing — the run measured the LIVE diagnosis while
    // reporting the scene's name, and the two findings the scene exists for were never on
    // screen. Leaving the page unmounts the panel, so returning to it is a real refetch.
    name: 'diagnosis: critical and warning findings (stubbed)',
    prepare: 'close;partial;leaf:Pods;wait:500;leaf:Cluster/Overview;wait:1500;scroll:.dx-item.dx-warning',
    // The text selectors resolve to their FIRST match, which is the critical card — the one
    // this scroll puts fully on screen.
    selectors: ['.dx-sev-critical', '.dx-sev-warning', '.dx-item-title', '.dx-obj', '.dx-detail', '.dx-fix'],
  },
  {
    // The two findings that say the audit did not see everything. They are the reason this
    // pair of scenes exists at all: until #381 `info` had no rule in `styles.css`, so a
    // truncation notice and an RBAC read failure rendered in the neutral base — the same
    // treatment as a badge carrying no severity, on the two findings whose whole job is to
    // tell the reader that the list in front of them is partial.
    name: 'diagnosis: the audit did not see everything (stubbed)',
    prepare: 'close;partial;leaf:Pods;wait:500;leaf:Cluster/Overview;wait:1500;scroll:.dx-item:last-child',
    selectors: ['.dx-sev-info', '.dx-item.dx-info .dx-item-title', '.dx-item.dx-info .dx-detail'],
  },
  {
    // The same admission, said once about the LIST instead of twice about objects (#388).
    // `.dx-coverage` sits at the top of the panel beside the count, so this scrolls to the
    // strip itself rather than to a card — the two scenes above scroll DOWN past it, and a
    // selector that has left the viewport reports `outside the viewport`, which this file
    // counts as a failed sample. `.dx-count` rides along because the notice is only in the
    // right place if the line it sits beside is readable too.
    name: 'diagnosis: the list is partial, said in the header (stubbed)',
    prepare: 'close;partial;leaf:Pods;wait:500;leaf:Cluster/Overview;wait:1500;scroll:.dx-coverage',
    selectors: ['.dx-coverage', '.dx-count'],
  },
  {
    // The editor dialog's Review Changes tab — the second diff (T1), which asks the cluster
    // what it WOULD store. Behind the admin login: its tabs are `v-if="!readonly"`, so a
    // signed-out run does not merely fail to measure them, it never renders them.
    name: 'editor review (signed in)',
    // Clicks the NAME cell, not the row. `ResourceTable`'s `rowProps` ignores a click whose
    // target is inside a checkbox, button, anchor or dropdown — so a row-centre click lands
    // on the Namespace LINK and filters the list instead of opening anything, and `td` alone
    // is the checkbox column. Both leave the drawer shut, and every following `?` step then
    // skips silently: the run reports `not present` for a surface it never navigated to.
    prepare:
      'close;signin:admin;leaf:Config Maps;wait:900;' +
      'click:.n-data-table-tbody tr td:nth-child(2);wait:1200;' +
      '?click:.n-tabs-tab:has-text("YAML");wait:1200;?click:button:has-text("Edit");wait:1800;' +
      '?click:.n-tabs-tab:has-text("Review Changes");wait:2500',
    selectors: ['.review-h', '.review-cap', '.review-recheck'],
  },
  {
    // A `:hover` pad, which nothing here could reach before the `hover:` verb: `.btn:hover`
    // hard-coded a light background and put the button's own label at 1.16:1 in dark mode.
    // Needs the pod file browser (`dev-run.sh --files`); without it the `.btn` is absent and
    // this row is counted as unmeasured rather than passed.
    name: 'button hover',
    prepare:
      'close;leaf:Pods;click:.n-data-table-tbody tr;wait:800;' +
      '?click:.n-tabs-tab:has-text("Files");wait:1200;?hover:.btn',
    selectors: ['.btn'],
  },
  {
    // The metrics chart's hover tooltip. Nothing here had ever hovered a chart point, so the
    // tooltip DOM did not exist during a run and these two selectors were unmeasurable: the
    // value rendered at 1.28:1 and the timestamp at 2.51:1 on the default dark theme, worse
    // than every failure this file's header records. The box is echarts' OWN container and
    // echarts inline-styles it, so a `.chart-tip` wrapper rule (which is what used to sit in
    // styles.css) can never reach it — its colours come from the chart option.
    //
    // What this scene CANNOT see is the chart itself: the axis labels and grid lines are
    // painted into a `<canvas>`, so there is no node to sample and no amount of scene-walking
    // will produce one. That half is guarded in the gate instead, by
    // `src/metric-chart-option.test.ts`, which fails if a `var(--…)` ever reaches the option.
    //
    // Needs a Prometheus / VictoriaMetrics backend. Without one MetricChart renders its
    // "Graphs need a …" placeholder, there is no canvas to hover, and these rows are counted
    // as unmeasured rather than passed.
    name: 'metrics chart tooltip',
    // Leads with `full` because it returns to the page the diagnosis scenes stubbed, and this
    // scene is about what the chart paints, not about a diagnosis anyone arranged.
    prepare: 'close;full;leaf:Cluster/Overview;wait:2000;?hover:.metric-echart;wait:800',
    selectors: ['.chart-tip-t', '.chart-tip-v'],
  },
  {
    // A control greyed out because the CLUSTER refused it (#354), and the reason printed
    // beside it.
    //
    // This is the one surface in the app that no instance on this box can produce on its own:
    // the verdict comes from a `SelfSubjectAccessReview`, and every cluster reachable from
    // here answers "yes" to an admin kubeconfig, while the simulator's API server answers
    // nothing at all — which the UI reads as `unknown` and renders as ENABLED, correctly. So
    // there is no live scene in which `.menu-denied-why` exists, and adding the selectors to a
    // watchlist would have bought a permanent `not present`: the "green line with half the run
    // unmeasured" this file's summary warns about.
    //
    // `deny` therefore stubs the ONE response the surface reads — `GET …/access` — with the
    // refusal the server sends when a cluster says no, byte for byte the shape
    // `AccessEndpointsTest` pins. Everything downstream is the real thing: the real components,
    // the real `permissions.ts`, the real stylesheet. What is faked is the cluster's answer,
    // which is exactly the part this box cannot supply.
    name: 'refused row actions (stubbed verdict)',
    prepare:
      'close;deny;leaf:Pods;wait:900;' +
      '?click:.n-data-table-tbody tr:first-child .n-checkbox;wait:300;' +
      '?click:.n-data-table-tbody tr:first-child td:last-child button;wait:500',
    selectors: ['.bulk-denied', '.menu-denied-label', '.menu-denied-why'],
  },
  {
    // The same refusal on the YAML editor's Apply. Behind the admin login, like the review
    // scene above and for the same reason: the editor's footer is `v-if="!readonly"`, so a
    // signed-out run does not fail to measure the hint, it never renders it.
    //
    // No `deny` of its own — the route installed by the scene above is still in place, and the
    // reload-free `signin` leaves it there. `allow` on the first scene is what takes it down
    // again for the next theme.
    name: 'refused apply (stubbed verdict)',
    prepare:
      'close;signin:admin;leaf:Pods;wait:900;' +
      'click:.n-data-table-tbody tr td:nth-child(2);wait:1200;' +
      '?click:.n-tabs-tab:has-text("YAML");wait:1200;?click:button:has-text("Edit");wait:1800',
    selectors: ['.dialog-denied-hint'],
  },
  {
    // `ActionNotice` — the failed-ACTION notice added for roadmap R3's error half. It is a
    // third rendering beside `ErrorNotice` and `EmptyState`, with its own three text classes,
    // and until this scene existed nothing in the run could reach any of them: every other way
    // of producing one needs a write to fail, which means either a broken cluster or actually
    // performing the write. A refused sign-in is the one that costs nothing and reproduces on
    // any instance.
    //
    // `signin:` cannot be reused — that verb exists to get IN, and what needs measuring here
    // is exactly the failure it was built to avoid. It leads with `signout` because PREPARE
    // runs once per theme and an earlier scene has signed in by the second pass; a wrong
    // password cannot be refused while that session is still open. Since GH#320 the app's own
    // Sign out ends it, and this scene is now also the browser-side check that it still does.
    //
    // LAST on purpose. `signout` reloads the page, and a reload mid-walk cost the scene that
    // used to follow this one its whole surface — it timed out clicking through a shell that
    // had just been rebuilt underneath it, and reported four unmeasured selectors rather than
    // an error. Nothing runs after this, so the reload has nothing left to disturb.
    name: 'rejected sign-in',
    prepare:
      'close;signout;' +
      '?click:.bar-btn:has-text("Sign in");wait:500;' +
      '?fill:.n-modal input >> nth=1=not-the-admin-password;' +
      '?click:.n-modal button:has-text("Sign in");wait:1500',
    selectors: ['.action-notice-title', '.action-notice-message', '.action-notice-consequence'],
  },
];

/**
 * Why a selector may legitimately have nothing to sample HERE, keyed by selector.
 *
 * "Not present" is this file's own word for a failed measurement, and it is the right word —
 * but it is the same word for two very different situations, and only one of them is a defect
 * in the run. `.btn` is absent because the pod file browser is off in this instance; the row
 * status pill can be absent because every visible pod is healthy. Neither is "we failed to
 * look", and printing them identically to a scene that walked to the wrong page is how two
 * watchlist entries measured nothing for months without anyone being able to tell (GH#389).
 *
 * So a note is printed beside the empty ratio. It does NOT make the sample count as measured —
 * the unmeasured total is unchanged and still says these are not passes. It only says which
 * kind of nothing this is, so a reader can act on the difference: turn a flag on, or fix a
 * scene. A selector with no entry here is one nobody has explained, which is itself the
 * signal.
 */
const WHY_ABSENT = {
  '.ov-card.danger':
    'needs a cluster with Warning events — StatCard only applies `danger` when there are some',
  '.btn': 'needs the pod file browser: scripts/dev-run.sh --files',
  '.diag-caps li.off .diag-name': 'needs a capability this instance reports as OFF',
  '.chart-tip-t': 'needs a Prometheus/VictoriaMetrics backend — without one there is no chart to hover',
  '.chart-tip-v': 'needs a Prometheus/VictoriaMetrics backend — without one there is no chart to hover',
  '.n-drawer .kv .n-tag': 'needs a Helm-installed ConfigMap in this cluster (label app.kubernetes.io/managed-by=Helm)',
  // A chip exists only for a state some row carries, so an absent one is a claim about the
  // cluster. `ok` is the one that is always there in practice (every list has healthy rows);
  // `idle` needs a finished or scaled-to-zero object.
  '.status-chip.tone-ok': 'needs a row in a healthy state — a chip is drawn only for a state the rows carry',
  '.status-chip.tone-warn': 'needs a row in a warn state — a chip is drawn only for a state the rows carry',
  '.status-chip.tone-err': 'needs a row in a failing state — a chip is drawn only for a state the rows carry',
  '.status-chip.tone-idle':
    'needs a finished or scaled-to-zero row — `idle` is neither healthy nor broken and is drawn muted',
  // No mention of chips in these two: whether one is on the page is what REQUIRED_WHEN below
  // decides, and a reason that guessed at it would contradict the verdict printed beside it.
  '[data-col-key=status] .status-badge.tone-warn': 'needs a pod in a warn state — a tinted pill is drawn only for one',
  '[data-col-key=status] .status-badge.tone-err': 'needs a pod in a failing state — a tinted pill is drawn only for one',
};

/**
 * Selectors that MUST produce a ratio when their oracle is on the page, keyed by selector.
 *
 * `WHY_ABSENT` above separates "not applicable in this instance" from "we failed to look", and
 * it does that in PROSE — a reader decides. This decides, for the cases where the page itself
 * already answers the question. The row status pill is one: the list header draws a status chip
 * for each state its rows carry, so a `warn` chip on screen is the app saying "some row is in a
 * warn state". If the pill of that tone then cannot be measured, the tone was not covered, and
 * the run must say so in the one way that cannot be read as a pass — a FAILURE and exit 1.
 *
 * Without this, per-tone selectors would still have been an improvement on `.n-tag` and would
 * still have let a tone go quietly unmeasured: a scene that stops reaching the warn pill prints
 * `not present`, which this file counts but does not fail on, deliberately (a healthy cluster
 * genuinely has no failing pod, and a checker that cries wolf gets switched off). The oracle is
 * what makes the difference decidable instead of a judgement call — nothing is required on a
 * cluster whose rows do not carry the state.
 */
const REQUIRED_WHEN = {
  '[data-col-key=status] .status-badge.tone-warn': {
    when: '.status-chip.tone-warn',
    why: 'the list offers a warn state chip, so a row in this list carries that state',
  },
  '[data-col-key=status] .status-badge.tone-err': {
    when: '.status-chip.tone-err',
    why: 'the list offers a failing state chip, so a row in this list carries that state',
  },
};

/**
 * Floors above AA, for surfaces where AA alone is not evidence of anything, keyed by selector.
 *
 * The warn status pill measured 4.51:1 in the light theme against the 4.5 floor (GH#393). It
 * passed, and a pass by one hundredth is not a property anything is holding: the tint is
 * translucent, so the ratio moves with whatever panel the row is on, and every previous edit to
 * this palette's tints and panels has moved a ratio by a few tenths. One full point of headroom
 * is what the danger tone already had on the same surface and what the derived `--*-on-tint`
 * foreground now gives all of them, so it is a margin the design meets rather than a target
 * invented for the check. Asserted rather than stated, because a stated margin is a comment.
 */
const FLOOR_OVERRIDE = {
  '.status-chip.tone-ok': 5.5,
  '.status-chip.tone-warn': 5.5,
  '.status-chip.tone-err': 5.5,
  '[data-col-key=status] .status-badge.tone-warn': 5.5,
  '[data-col-key=status] .status-badge.tone-err': 5.5,
};

const args = process.argv.slice(2);
const SELF_TEST = args.includes('--self-test');
const explicit = args.filter((a) => !a.startsWith('--'));
const selectors = explicit.length ? explicit : DEFAULT_SELECTORS;
// Named selectors, or an explicit PREPARE, mean "measure exactly this" — the scene walk would
// then navigate away from whatever the caller set up.
const scenes = explicit.length || process.env.PREPARE ? [] : SCENES;

const channel = (v) => {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
};
const luminance = ([r, g, b]) => 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
const ratio = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};
// Colour channels as 0-255, whatever notation the browser returned.
//
// `getComputedStyle` does NOT always answer in `rgb()`. Naive UI's controls resolve to
// `color(srgb 0.890196 0.909804 0.92549 / 0.75)`, whose channels are 0-1 floats. Read as
// 0-255 those are near-black, so this reported a near-white label on a dark panel as a
// 1.42:1 FAIL (#245) — a false failure in a gating tool, which is worse than no tool,
// because it teaches people to ignore the real ones.
const parse = (s) => {
  const nums = (s || '').match(/[\d.]+/g)?.map(Number) ?? null;
  if (!nums || !/^\s*color\(/.test(s)) return nums;
  if (!/^\s*color\(\s*srgb\b/.test(s)) {
    // display-p3 and the rest need a real gamut conversion, not a scale factor. Refuse
    // rather than guess: a wrong number here is indistinguishable from a right one.
    throw new Error(`unsupported colour space, cannot measure: ${s}`);
  }
  // r g b are 0-1; a trailing alpha after `/` is already 0-1 and must not be scaled.
  return nums.map((n, i) => (i < 3 ? Math.round(n * 255) : n));
};
const rgbText = (c) => `rgb(${c[0]},${c[1]},${c[2]})`;
const agree = (a, b) => a && b && a.every((v, i) => Math.abs(v - b[i]) <= AGREE_TOLERANCE);

/** Sample one selector: its first match's colours, rects and DOM-walk backdrops. */
async function sampleSelector(page, sel) {
  return page
    .$$eval(sel, (els) => {
      // Same 0-1 vs 0-255 trap as the parser outside the page (#245).
      const nums = (c) => {
        const n = (c || '').match(/[\d.]+/g)?.map(Number) ?? null;
        if (!n || !/^\s*color\(/.test(c)) return n;
        if (!/^\s*color\(\s*srgb\b/.test(c)) return null; // unknown space: skip this layer
        return n.map((v, i) => (i < 3 ? Math.round(v * 255) : v));
      };
      // The DOM's opinion of the colour painted at this element's own box: its background
      // composited over every translucent ancestor down to the first opaque one.
      //
      // Kept only as a cross-check now (see the header): it is blind to anything a sibling
      // paints, which is how #250 happened. When it disagrees with the decoded pixel the
      // pixel wins, and the disagreement is printed.
      const painted = (el) => {
        const layers = [];
        let node = el;
        let base = [255, 255, 255];
        while (node) {
          const p = nums(getComputedStyle(node).backgroundColor);
          if (p && p.length >= 3) {
            const a = (p.length > 3) ? p[3] : 1;
            if (a >= 0.999) {
              base = p.slice(0, 3);
              break;
            }
            if (a > 0) layers.push([p.slice(0, 3), a]);
          }
          node = node.parentElement;
        }
        // Nearest ancestor is painted last, so apply the collected layers in reverse.
        let out = base;
        for (let i = layers.length - 1; i >= 0; i -= 1) {
          const [c, a] = layers[i];
          out = out.map((v, j) => Math.round(c[j] * a + v * (1 - a)));
        }
        return out;
      };
      // Where this element's own glyphs actually land, so the pixel sample is taken over the
      // run itself rather than over padding that a neighbouring layer happens to cover.
      // Falls back to the box inside the border edge when the element has no direct text of
      // its own — an <input>, whose value lives in a shadow tree, takes that path.
      const textRect = (el) => {
        const r = document.createRange();
        let box = null;
        for (const n of el.childNodes) {
          if (n.nodeType !== 3 || !n.textContent.trim()) continue;
          r.selectNodeContents(n);
          for (const cr of r.getClientRects()) {
            if (cr.width < 1 || cr.height < 1) continue;
            box = box
              ? {
                  x: Math.min(box.x, cr.x),
                  y: Math.min(box.y, cr.y),
                  r: Math.max(box.r, cr.right),
                  b: Math.max(box.b, cr.bottom),
                }
              : { x: cr.x, y: cr.y, r: cr.right, b: cr.bottom };
          }
        }
        if (box) return { x: box.x, y: box.y, w: box.r - box.x, h: box.b - box.y };
        const cs = getComputedStyle(el);
        const bb = el.getBoundingClientRect();
        const w = (v) => parseFloat(v) || 0;
        return {
          x: bb.x + w(cs.borderLeftWidth) + 1,
          y: bb.y + w(cs.borderTopWidth) + 1,
          w: bb.width - w(cs.borderLeftWidth) - w(cs.borderRightWidth) - 2,
          h: bb.height - w(cs.borderTopWidth) - w(cs.borderBottomWidth) - 2,
        };
      };
      // Only elements that paint glyphs THEMSELVES are measurable.
      //
      // `textContent` is recursive, so every wrapper on the way down used to be measured as
      // if it held the text its descendants hold. That is harmless while the wrapper and its
      // child share a backdrop, and actively wrong once they do not: with the backdrop read
      // off the screen, `.bar-filter`'s three wrappers sampled the white select box that is
      // painted over most of their box while carrying the top bar's inherited light colour,
      // and reported 1.17:1 for text that does not exist. (Same shape as `ui-measure`
      // calling a layout container a 229-chars-per-line defect.) A leaf with real text is
      // still always measured, so nothing is lost: colour reaches the glyphs through it.
      const hasOwnText = (el) =>
        Array.prototype.some.call(el.childNodes, (n) => n.nodeType === 3 && n.textContent.trim());
      // Reading the backdrop off the screen buys ground truth and costs a precondition the
      // DOM walk never had: the text has to BE on the screen. Both ways of failing that
      // turned up the first time this ran against an open drawer —
      //
      //   * off-viewport: a row's `⋮` buttons sat at x=1594 in a 1400px window, so the
      //     screenshot holds no pixels for them at all;
      //   * covered: the `+ Create` button was underneath the drawer panel, so the decoded
      //     pixel was the drawer's white and the button read 1.00:1.
      //
      // Neither is a contrast defect — nobody can see either element — and reporting them as
      // FAIL is the same false-alarm failure mode as #250. So an unmeasurable sample says so,
      // and is NOT quietly back-filled from the DOM walk, which would reintroduce exactly the
      // blindness this change removes.
      const unmeasurable = (el, r) => {
        if (!r || r.w < 1 || r.h < 1) return 'zero-sized text run';
        if (r.x < 0 || r.y < 0 || r.x + r.w > window.innerWidth || r.y + r.h > window.innerHeight) {
          return 'outside the viewport — scroll or widen it';
        }
        // Hit-test several points, not one: a single probe can land in the gap between two
        // glyphs of an inline child. "Related" means the element itself, an ancestor (what a
        // `pointer-events: none` badge resolves to) or a descendant.
        //
        // `pointer-events: none` first, though: such an element is PAINTED but can never be
        // returned by elementFromPoint, so the hit test answers a question it was not asked
        // and every one of them reads as "covered by another layer". That is how the metrics
        // chart's hover tooltip — echarts sets `pointer-events: none` on its container —
        // reported unmeasurable while sitting in plain sight at 1.28:1. Force the property
        // back on for the duration of the probe, so the answer is about occlusion again.
        const forced = [];
        for (let n = el; n && n !== document.documentElement; n = n.parentElement) {
          if (getComputedStyle(n).pointerEvents === 'none') {
            forced.push([n, n.style.pointerEvents]);
            n.style.pointerEvents = 'auto';
          }
        }
        const pts = [0.5, 0.15, 0.85].map((f) => [r.x + r.w * f, r.y + r.h / 2]);
        const seen = pts.some(([x, y]) => {
          const hit = document.elementFromPoint(x, y);
          return hit && (hit === el || el.contains(hit) || hit.contains(el));
        });
        forced.forEach(([n, prev]) => (n.style.pointerEvents = prev));
        return seen ? null : 'covered by another layer';
      };
      const of = (el, what) => {
        const rect = textRect(el);
        const skip = unmeasurable(el, rect);
        return { what, color: getComputedStyle(el).color, domBg: painted(el), rect: skip ? null : rect, skip };
      };
      const sample = (el) => {
        const cs = getComputedStyle(el);
        // The element's own text, plus any text-bearing descendant that sets its own
        // colour — a row can pass on its label and fail on its hint.
        const parts = hasOwnText(el) ? [of(el, '(self)')] : [];
        for (const kid of el.querySelectorAll('*')) {
          if (hasOwnText(kid)) parts.push(of(kid, kid.className || kid.tagName.toLowerCase()));
        }
        return { parts, font: cs.fontSize, weight: cs.fontWeight };
      };
      // First match that actually carries text, not simply the first match. `.n-data-table-th`
      // matches the selection-checkbox column first, which has no text at all, so a strict
      // "first match" reports the whole selector as unmeasurable while every visible header
      // beside it goes unchecked.
      const all = els.map(sample);
      return [all.find((s) => s.parts.length) ?? all[0]].filter(Boolean);
    })
    .catch(() => []);
}

/**
 * Ground truth: the modal colour actually painted under each rect, glyphs removed.
 *
 * One screenshot serves every rect in a pass, so this costs one capture per theme rather
 * than one per selector. Decoding happens in the page (canvas `getImageData`) so the script
 * needs no PNG library — and a data: URL does not taint the canvas.
 */
async function pixelBackdrops(page, rects) {
  const style = await page.addStyleTag({
    content: `*, *::before, *::after { color: transparent !important;
        -webkit-text-fill-color: transparent !important; text-shadow: none !important;
        caret-color: transparent !important; }
      input::placeholder, textarea::placeholder { color: transparent !important;
        -webkit-text-fill-color: transparent !important; }`,
  });
  let shot;
  try {
    shot = await page.screenshot();
  } finally {
    await style.evaluate((n) => n.remove()).catch(() => {});
  }
  const view = page.viewportSize();
  return page.evaluate(
    async ({ dataUrl, rects: rs, width }) => {
      const img = new Image();
      img.src = dataUrl;
      await img.decode();
      const cv = document.createElement('canvas');
      cv.width = img.width;
      cv.height = img.height;
      const ctx = cv.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(img, 0, 0);
      const scale = img.width / width;
      return rs.map((r) => {
        if (!r) return null;
        const x0 = Math.max(0, Math.round(r.x * scale));
        const y0 = Math.max(0, Math.round(r.y * scale));
        const x1 = Math.min(img.width, Math.round((r.x + r.w) * scale));
        const y1 = Math.min(img.height, Math.round((r.y + r.h) * scale));
        if (x1 - x0 < 1 || y1 - y0 < 1) return null;
        const d = ctx.getImageData(x0, y0, x1 - x0, y1 - y0).data;
        // Mode, not mean and not one sample: a mean of a two-tone region is a colour that is
        // not on screen anywhere, and one sample is what a focus ring or an icon derails.
        const counts = new Map();
        let best = 0;
        let key = 0;
        for (let i = 0; i < d.length; i += 4) {
          const k = (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
          const n = (counts.get(k) || 0) + 1;
          counts.set(k, n);
          if (n > best) {
            best = n;
            key = k;
          }
        }
        const total = d.length / 4;
        return { rgb: [(key >> 16) & 255, (key >> 8) & 255, key & 255], share: best / total };
      });
    },
    { dataUrl: `data:image/png;base64,${shot.toString('base64')}`, rects, width: view.width },
  );
}

/** What a step waits for, so an optional one can be tested before it is run. */
const selectorOf = (verb, arg) => ((verb === 'fill' || verb === 'upload') ? splitFill(arg).selector : arg);

/**
 * Make the nav clickable before PREPARE walks it.
 *
 * The rail collapses (#237) and each category is a `<details>` whose open state is remembered
 * in prefs, so a `click:.leaf-label…` step can resolve its element and then spend the whole
 * timeout on "element is not visible". That reads as a renamed leaf and sends you to the nav
 * registry; the only problem is a shut parent. `openLeaf` in lib/kw-playwright.mjs carries the
 * same fix — this file keeps its own copy on purpose (it is the instrument that has caught the
 * real colour defects, and sharing it with a helper that changes is how it stops being one).
 */
async function openNav(page) {
  const tile = await page.$('.tile-nav'); // rendered only while the nav is collapsed
  if (tile) {
    await tile.click().catch(() => {});
    await page.waitForTimeout(300);
  }
  await page.evaluate(() => {
    for (const d of document.querySelectorAll('details.group')) {
      d.open = true;
    }
  });
}

/**
 * Open a nav leaf by label, expanding the rail and every category first.
 *
 * Until this existed, NOTHING behind the nav could have its colour measured here: the app has
 * no deep links (every route is in-page state), `ui-shot`/`ui-measure` reach a list with
 * `--leaf`, and this tool had only PREPARE — whose `click:` cannot see a leaf inside a
 * collapsed category (#237) and dies after a 30s "element is not visible" retry loop. So every
 * list, drawer and detail surface in the app was un-measurable for contrast, which is exactly
 * the set of screens colour bugs have shipped in.
 */
async function openLeafHere(page, label) {
  const rail = page.locator('.tile-nav');
  if (await rail.isVisible().catch(() => false)) {
    await rail.click().catch(() => {});
    await page.waitForTimeout(300);
  }
  // Only the CLOSED categories: clicking every summary TOGGLES, so on the normal case (all
  // open) it shuts them and the leaf stops being clickable.
  const closed = page.locator('.group:not([open]) > summary');
  for (let i = 0, n = await closed.count(); i < n; i += 1) {
    await closed
      .nth(i)
      .click()
      .catch(() => {});
    await page.waitForTimeout(60);
  }
  // Refuse to guess between same-named leaves. SIX categories have an `Overview`, and this
  // took `.first()`: `leaf:Overview`, meant to reach the Network overview, opened the CLUSTER
  // one instead. Both render `.ov-scope-note` and `.ov-truncated`, so the run returned real
  // numbers for the wrong page — the failure mode that looks exactly like a clean pass. The
  // resolver lives in lib/kw-playwright.mjs so the two walkers cannot disagree about it.
  const leaf = await resolveLeaf(page, label);
  await leaf.click({ timeout: 5000 });
  await page.waitForSelector('.n-data-table-tbody tr, .empty, .ov-scope-note', { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(500);
}

/** The one request the refusal affordance reads (#354). */
const ACCESS_URL = '**/api/v1/clusters/*/access*';

/**
 * A refusal in the shape the server actually sends — `KindAccess`, three verbs, each a
 * tri-state verdict with the cluster's own reason. Copied from what `AccessEndpointsTest`
 * asserts on rather than invented, so a change to the wire shape breaks this scene too
 * instead of leaving it quietly measuring a payload the app no longer receives.
 */
const REFUSED_ACCESS = {
  kind: 'Pod',
  namespace: 'default',
  verbs: {
    create: { verdict: 'denied', reason: 'RBAC: no rules authorize this' },
    patch: { verdict: 'denied', reason: 'RBAC: no rules authorize this' },
    delete: { verdict: 'denied', reason: 'RBAC: no rules authorize this' },
  },
};

/** The one request the diagnosis panel reads. `*` does not cross `/`, so the POST that runs an
 *  analysis (`…/diagnose/summary`) is deliberately not matched. */
const DIAGNOSE_URL = '**/api/v1/clusters/*/diagnose*';

/**
 * A diagnosis carrying every severity, including the two findings that say the audit itself
 * did not see everything.
 *
 * Titles, details and suggested fixes are copied from the emitters — `PodDiagnosis`,
 * `PodSecurity` and `SecurityAuditService`, whose strings `SecurityAuditServiceTest` and
 * `DiagnoseSecurityFindingsTest` pin — rather than invented, so what is on screen is what an
 * operator would actually read. The WIRE SHAPE is the part this scene depends on: rename a
 * field on `DiagnoseResult` or `Finding` and the panel renders nothing here, which fails the
 * run. A reworded title would not, and should not — this measures colour, not text. The
 * objects are invented, and named the way the simulator names things: this file is committed,
 * and a real cluster's namespaces and pod names are not.
 */
const PARTIAL_DIAGNOSIS = {
  findings: [
    {
      severity: 'critical',
      title: 'ImagePullBackOff',
      object: 'Pod/sim-ns-2/sim-pod-11',
      detail: 'Back-off pulling image "registry.example.test/sim/api:1.4.2"',
      suggestedFix: 'Check the image name and tag, and whether this namespace has a pull secret for that registry.',
      source: 'validator',
    },
    {
      severity: 'warning',
      title: 'Container runs privileged',
      object: 'Pod/sim-ns-1/sim-pod-4 (agent)',
      detail:
        "securityContext.privileged=true on container 'agent'" +
        ' — it holds every capability the kernel has, and can reconfigure the node',
      suggestedFix:
        'Drop privileged and add back only the capabilities the process needs,' +
        ' or confirm this container is meant to manage the node.',
      source: 'validator',
    },
    {
      severity: 'info',
      title: 'Further container privileges not listed',
      object: 'Pods/7',
      detail: '7 more container privilege findings in this scope are not listed, so the most severe stay readable',
      suggestedFix: 'Narrow the diagnosis to one namespace to see the rest.',
      source: 'validator',
    },
    {
      severity: 'info',
      title: 'RBAC grants could not be read',
      object: 'ClusterRoleBindings',
      detail: 'Failure executing: GET at: /apis/rbac.authorization.k8s.io/v1/clusterrolebindings. Message: Forbidden',
      suggestedFix:
        'The identity kweblens uses may not be permitted to list RoleBindings and ClusterRoleBindings.' +
        ' Nothing about cluster-admin grants was checked.',
      source: 'validator',
    },
  ],
  summary: null,
  aiEnriched: false,
  analysedAt: null,
  summaryOutdated: false,
  aiAvailable: false,
  // What the SERVER says about the list, which is a different claim from any of the findings
  // above and is why `.dx-coverage` exists (#388). Reasons copied from `SecurityAuditService`.
  // The panel builds its notice from this field ALONE — delete these two entries and the strip
  // goes away no matter what the findings are called, which is the property the vitest pins.
  incomplete: [
    {
      dimension: 'container privileges',
      reason: '7 further container privilege findings are not listed, so the most severe stay readable.',
    },
    {
      dimension: 'RBAC grants',
      reason: 'RoleBindings and ClusterRoleBindings could not be listed, so no cluster-admin grant was checked.',
    },
  ],
};

async function stubPartialDiagnosis(page) {
  await page.unroute(DIAGNOSE_URL).catch(() => {});
  await page.route(DIAGNOSE_URL, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PARTIAL_DIAGNOSIS) }),
  );
}

async function stubRefusedAccess(page) {
  await page.unroute(ACCESS_URL).catch(() => {});
  await page.route(ACCESS_URL, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(REFUSED_ACCESS) }),
  );
}

async function runPrepare(page, spec) {
  for (const raw of (spec || '').split(';').map((s) => s.trim()).filter(Boolean)) {
    const optional = raw.startsWith('?');
    const step = optional ? raw.slice(1) : raw;
    const [verb, ...rest] = step.split(':');
    const arg = rest.join(':');
    if (optional && verb === 'press') throw new Error('? needs a selector, so it cannot mark a press step');
    // `?step` is skipped when its target is not on screen. PREPARE runs once per theme, and
    // a step like signing in only applies the first time — without this the second pass sits
    // waiting for a modal that is already dealt with, and the run dies on a timeout.
    if (optional && !(await page.$(selectorOf(verb, arg)))) continue;
    if (verb === 'press') await page.keyboard.press(arg);
    else if (verb === 'click') await page.click(arg);
    else if (verb === 'wait') await page.waitForTimeout(Number(arg));
    // Below the fold is not the same as absent. This tool refuses to sample an element that
    // is off screen — rightly, since the backdrop comes from a rendered pixel — so a surface
    // like the overview's Warnings table (y≈1220 in a 900px viewport) reported `outside the
    // viewport` for every selector, which is a failed run wearing a caveat (#257).
    else if (verb === 'scroll') await page.locator(arg).first().scrollIntoViewIfNeeded();
    // A `:hover` rule was unreachable here, and hover backgrounds are exactly where a
    // one-theme literal survives: `.btn:hover` hard-coded `#f0f4f7` with no dark override, so
    // in dark mode a button's `var(--text)` label sat on a near-white pad at 1.13:1 — on every
    // button in the app. The pointer stays parked, so the state holds through the backdrop
    // screenshot as well as the computed-style read.
    else if (verb === 'hover') await page.locator(arg).first().hover();
    // Signing in, as ONE verb rather than a four-step optional incantation.
    //
    // Until this existed, no surface behind the admin login could have its colour measured
    // here at all — most visibly the YAML editor's own dialog, whose Form / Warnings /
    // Review tabs are `v-if="!readonly"` and therefore simply absent to a signed-out run.
    // Open-mode makes that easy to miss: the page renders, the drawer opens, and only the
    // editing surfaces are quietly gone, so a run looks complete while never having seen
    // them. Idempotent by construction — if the Sign in link is not there we are already in.
    else if (verb === 'signin') {
      const link = page.locator('.bar-btn:has-text("Sign in")').first();
      if (await link.isVisible().catch(() => false)) {
        await link.click();
        await page.waitForTimeout(400);
        const inputs = page.locator('.n-modal input');
        await inputs.nth(0).fill(arg || 'admin');
        await inputs.nth(1).fill(arg || 'admin');
        await page.getByRole('button', { name: /^sign/i }).last().click();
        await page.waitForTimeout(1800);
      }
    }
    // `signout` — the real inverse of `signin`, and it has to end the SESSION, not just the
    // UI state. It clicks the app's own Sign out and nothing else, deliberately: that button
    // now awaits `DELETE /api/v1/auth/session`, so after it the cookie is dead.
    //
    // It used to call `page.context().clearCookies()` as well, because sign-out was a UI
    // state change only (GH#320) — the `JSESSIONID` survived, `loginSubmit` decided success
    // from `verifySession()`, and that request rode the surviving cookie. The rejected
    // sign-in scene therefore passed in the first theme (which runs signed out) and reported
    // `not present` in the second (which runs after an earlier scene signed in), because the
    // deliberately-wrong password quietly SUCCEEDED. That is how the bug was found.
    //
    // Clearing the cookie here would now hide a regression of that fix behind a green run, so
    // it is gone: if sign-out stops signing out, this scene goes back to reporting three
    // absent selectors — which this project already reads as a failed measurement, not a pass.
    else if (verb === 'signout') {
      const out = page.locator('.bar-btn:has-text("Sign out")').first();
      if (await out.isVisible().catch(() => false)) {
        await out.click();
        await page.waitForTimeout(500);
      }
      await page.reload({ waitUntil: 'networkidle' });
      await page.waitForTimeout(500);
    }
    // `close` (no argument) shuts an open drawer or modal if there is one. `?press:Escape`
    // cannot express this — `?` needs a selector to test — and an unconditional Escape is not
    // safe either, since the app also uses it to leave surfaces the caller may have opened on
    // purpose. Scenes need it because each one starts from wherever the previous one stopped,
    // and a Naive modal mask silently intercepts the next click.
    else if (verb === 'close') {
      const open = await page
        .locator('.n-modal-mask, .n-drawer')
        .first()
        .isVisible()
        .catch(() => false);
      if (open) {
        await page.keyboard.press('Escape');
        await page.waitForTimeout(300);
      }
    }
    // `deny` / `allow` — make the app believe the cluster has REFUSED this kind's writes,
    // and take that back.
    //
    // The only faked thing is the cluster's verdict, and it has to be faked because it cannot
    // be produced here: an admin kubeconfig is allowed everything and the simulator answers no
    // review at all. Everything the run then measures — the disabled menu entry, the sentence
    // under it, the hint beside a dead Apply — is the real component rendering the real
    // server's wire shape, which is why the stub is a copy of what `AccessEndpointsTest` pins
    // rather than a convenient invention.
    //
    // `deny` unroutes first so it is idempotent; `allow` is a no-op when nothing is installed.
    else if (verb === 'deny') await stubRefusedAccess(page);
    else if (verb === 'allow') await page.unroute(ACCESS_URL).catch(() => {});
    // `partial` / `full` — the same trick as `deny`, for the same reason, on the diagnosis:
    // make the panel receive a scope where the audit could not see everything, and take that
    // back. The `info` severity has four emitters and two of them are exactly that admission
    // ("Further container privileges not listed", "RBAC grants could not be read"); neither
    // can be produced from this box, so without the stub `.dx-sev-info` is `not present`
    // forever — which reads as a pass and is how it shipped with no rule at all (#381).
    else if (verb === 'partial') await stubPartialDiagnosis(page);
    else if (verb === 'full') await page.unroute(DIAGNOSE_URL).catch(() => {});
    else if (verb === 'leaf') await openLeafHere(page, arg);
    // `drawer:<px>` drags the open drawer to a width inside its own 360..1400 resize range.
    // Shared with the other runner rather than copied — the rule this file's history keeps
    // teaching is that two copies of a walker drift and then disagree silently (#278).
    else if (verb === 'drawer') await resizeDrawer(page, Number(arg));
    else if (verb === 'fill') {
      // splitFill, not lastIndexOf('='): the list header's filter box takes `app=web`, and
      // the old split moved half the VALUE into the selector (see lib/kw-playwright.mjs).
      const { selector, value } = splitFill(arg);
      await page.fill(selector, value);
    } else if (verb === 'upload') {
      // A file input, for UI that only appears once something has been picked — the pod
      // file browser's upload confirmation, for one, which is otherwise unreachable here.
      const { selector, value } = splitFill(arg);
      await page.setInputFiles(selector, value);
    } else throw new Error(`unknown PREPARE verb: ${verb}`);
    await page.waitForTimeout(250);
  }
}

/**
 * Measure every selector once, on whatever the page currently shows.
 *
 * Returns `{ rows, disagreements }`. A row's `bg` is the decoded pixel wherever one could be
 * taken; `domBg` is the walk's answer, kept so the two can be compared.
 */
async function measure(page, theme, sels) {
  const rows = [];
  const disagreements = [];
  const found = [];
  // A known reason for this selector to have nothing here, appended so "not applicable in this
  // instance" and "the scene never reached the surface" stop reading identically (see
  // WHY_ABSENT). Only ever decorates a row that already carries no ratio.
  //
  // ABSENCE ONLY. `outside the viewport` and `covered by another layer` mean the element IS
  // there and the scene failed to put it where it could be read — a scene defect, which is the
  // whole subject of GH#389. The first version of this appended the reason to those too, and
  // the control run written to prove the scroll step load-bearing came back reading
  // `outside the viewport … needs a pod that is not healthy`: a broken scene wearing a
  // configuration excuse, i.e. the exact confusion this map exists to remove, added by the
  // thing meant to remove it.
  const why = (sel, note) => (WHY_ABSENT[sel] && note.startsWith('not present') ? `${note} — ${WHY_ABSENT[sel]}` : note);
  for (const sel of sels) {
    const samples = await sampleSelector(page, sel);
    if (!samples.length) {
      rows.push({ theme, sel, what: '—', r: null, note: why(sel, 'not present') });
      continue;
    }
    // Present but painting no glyphs is still nothing measured, and must say so rather than
    // pass silently — the same rule as `not present`.
    if (!samples[0].parts.length) {
      rows.push({ theme, sel, what: '—', r: null, note: why(sel, 'present, but no text of its own') });
      continue;
    }
    found.push({ sel, s: samples[0] });
  }
  const flat = found.flatMap((f) => f.s.parts);
  const pixels = flat.length ? await pixelBackdrops(page, flat.map((p) => p.rect)) : [];

  let i = 0;
  for (const { sel, s } of found) {
    // WCAG's large-text allowance: >=18.66px bold, or >=24px. A selector with a floor of its
    // own overrides both: AA is the legal minimum, not a design margin (see FLOOR_OVERRIDE).
    const px = parseFloat(s.font);
    const bold = Number(s.weight) >= 700;
    const floor = FLOOR_OVERRIDE[sel] ?? (px >= 24 || (bold && px >= 18.66) ? AA_LARGE : AA_NORMAL);
    for (const p of s.parts) {
      const pixel = pixels[i];
      i += 1;
      const fg = parse(p.color);
      if (!fg) continue;
      // No pixel means nothing was measured. Say so; never fall back to the DOM walk.
      if (!pixel) {
        rows.push({ theme, sel, what: p.what, r: null, note: why(sel, p.skip || 'no pixel could be sampled') });
        continue;
      }
      const bg = pixel.rgb;
      const row = {
        theme,
        sel,
        what: p.what,
        r: ratio(fg, bg),
        floor,
        bg: rgbText(bg),
        bgRgb: bg,
        src: 'pixel',
      };
      rows.push(row);
      if (!agree(pixel.rgb, p.domBg)) {
        disagreements.push({
          theme,
          sel,
          what: p.what,
          pixel: rgbText(pixel.rgb),
          dom: rgbText(p.domBg),
          domRatio: ratio(fg, p.domBg),
          r: row.r,
          share: pixel.share,
        });
      }
    }
  }
  return { rows, disagreements };
}

/**
 * Mark the rows that produced no ratio where the page itself says one was owed.
 *
 * Runs against the page the rows were just measured on, so the oracle is read in the same state
 * the sample failed in — asking afterwards, from a page a later scene has navigated, would be
 * asking a different question. Only ever touches a row that already carries no ratio: this
 * cannot turn a measured colour into a failure, only an unmeasured one into an admitted one.
 */
async function flagRequired(page, rows) {
  for (const r of rows) {
    if (r.r !== null) continue;
    const req = REQUIRED_WHEN[r.sel];
    if (!req) continue;
    if (!(await page.$(req.when))) continue;
    r.required = true;
    r.note = `${r.note} — REQUIRED here: ${req.why} (${req.when} is on the page)`;
  }
}

/** Measure, then let the page say which of the empty rows were owed a ratio. */
async function measureHere(page, theme, sels) {
  const out = await measure(page, theme, sels);
  await flagRequired(page, out.rows);
  return out;
}

/**
 * Positive controls — cases whose answers are known before the tool is run.
 *
 * "Beware the oracle": every wrong conclusion in this project came from a broken instrument,
 * not broken maths, and this instrument has now been wrong three times (#245, #250, and the
 * 8.12:1-reported-as-1.78:1 stack before them). So the fix ships with a fixture whose right
 * answers are arithmetic, including the exact sibling-paint shape that #250 was about and
 * the glyph trap that a naive point sample falls into.
 */
const FIXTURE = `
  <style>
    body { margin: 0; font: 14px/1.4 sans-serif; }
    .bar { background: rgb(27,42,51); padding: 12px; }
    #opaque { background: rgb(27,42,51); color: rgb(232,238,242); padding: 8px; }
    .panel { background: rgb(255,255,255); padding: 8px; }
    #tint { background: rgba(10,122,194,0.14); color: rgb(51,54,57); padding: 8px; }
    #l1 { background: rgba(10,122,194,0.14); padding: 8px; }
    #l2 { background: rgba(10,122,194,0.14); padding: 8px; }
    #stack { color: rgb(232,238,242); }
    /* The #250 shape: the white box is a SIBLING of the text, painted underneath it. */
    .sel { position: relative; width: 220px; height: 30px; }
    .sel .label { position: absolute; inset: 0; background: rgb(255,255,255); }
    .sel .input { position: absolute; inset: 0; color: rgb(51,54,57); padding: 6px; }
    /* The glyph trap: dense black text on white, where a centre-point sample lands on ink. */
    #glyphs { background: rgb(255,255,255); color: rgb(0,0,0); font: 900 22px/1 sans-serif;
              width: 200px; }
    /* Nothing to measure: covered by an opaque layer, and parked off the viewport. */
    .cover { position: relative; width: 200px; height: 24px; }
    .cover .under { position: absolute; inset: 0; background: rgb(255,255,255); color: rgb(0,0,0); }
    .cover .over { position: absolute; inset: 0; background: rgb(27,42,51); }
    #offscreen { position: absolute; left: 3000px; top: 0; color: rgb(0,0,0); }
    /* Painted, in plain sight, but invisible to elementFromPoint — echarts' tooltip. */
    .ghost { position: relative; width: 200px; height: 24px; background: rgb(27,42,51); }
    .ghost .layer { position: absolute; inset: 0; background: rgb(255,255,255); color: rgb(0,0,0);
                    pointer-events: none; }
    /* Per-tone addressing, and the tone that is NOT here (GH#393). Both pills would be one
       .n-tag match, which is how one tone's ratio got reported under another's name. */
    .status-chip { background: rgb(255,255,255); color: rgb(51,54,57); padding: 2px 8px; }
    .status-badge { background: rgb(255,255,255); color: rgb(51,54,57); padding: 2px 8px; }
  </style>
  <div class="bar">
    <div id="opaque">Opaque</div>
    <div class="panel"><div id="tint">Tint over panel</div></div>
    <div id="l1"><div id="l2"><div id="stack">Three layers</div></div></div>
    <div class="sel"><div class="label"></div><div class="input">sibling-painted</div></div>
    <div id="glyphs">MMMMMMMM</div>
    <div class="cover"><div class="under">hidden under</div><div class="over"></div></div>
    <div id="offscreen">parked off-screen</div>
    <div class="ghost"><div class="layer">pointer-events none</div></div>
    <!-- The list header says both tones are in these rows... -->
    <div class="status-rail">
      <span class="status-chip tone-warn">Pending</span>
      <span class="status-chip tone-err">CrashLoopBackOff</span>
    </div>
    <!-- ...and only one of them has a pill. The missing one is the control that must FIRE:
         before GH#393 an .n-tag selector would have measured the warn pill, reported it as
         "the status pill", and passed the run with the danger tone never sampled. -->
    <div class="n-data-table-td" data-col-key="status"><span class="status-badge tone-warn">Pending</span></div>
    <div class="n-data-table-td" data-col-key="status"></div>
    <!-- The Ready column renders the same component, and an unscoped selector reaches it: this
         one is a tone-err pill on the page that the tone-err STATUS control must NOT find. -->
    <div class="n-data-table-td" data-col-key="ready"><span class="status-badge tone-err">0/1</span></div>
  </div>
`;

// Expected backdrops, by arithmetic. `over` composites src at alpha a onto dst.
const over = (src, a, dst) => src.map((c, i) => Math.round(c * a + dst[i] * (1 - a)));
const TINT = [10, 122, 194];
const WHITE = [255, 255, 255];
const BAR = [27, 42, 51];
const CONTROLS = [
  { sel: '#opaque', want: BAR, why: 'plain opaque element' },
  { sel: '#tint', want: over(TINT, 0.14, WHITE), why: 'translucent tint over an opaque panel' },
  { sel: '#stack', want: over(TINT, 0.14, over(TINT, 0.14, BAR)), why: 'three-layer stack' },
  { sel: '.sel .input', want: WHITE, why: 'sibling-painted backdrop (#250)', domMustDiffer: true },
  { sel: '#glyphs', want: WHITE, why: 'dense glyphs — sample must not read the ink' },
  // The two ways the pixel path can have nothing to read. Both must decline to answer rather
  // than report the colour they happen to find there.
  { sel: '.cover .under', wantNote: 'covered by another layer', why: 'covered — must not measure the layer on top' },
  { sel: '#offscreen', wantNote: 'outside the viewport', why: 'off-viewport — must not measure' },
  // ...and the control that must NOT fire: an element that is painted on top but has
  // `pointer-events: none`, so elementFromPoint can never return it. Every metrics-chart
  // tooltip sample reported "covered by another layer" until the hit test forced the property
  // back on — a whole surface reading as unmeasurable while visibly failing at 1.28:1.
  { sel: '.ghost .layer', want: WHITE, why: 'pointer-events:none but painted — must be measured' },
  // Per-tone addressing (GH#393), both halves. The tone that is on the page must be measured
  // UNDER ITS OWN NAME; the tone that is not must fail, because the page's own status chip says
  // a row carries that state. These use the real scene selectors and the real REQUIRED_WHEN
  // entries, so a change to either is a change to this control.
  {
    sel: '[data-col-key=status] .status-badge.tone-warn',
    want: WHITE,
    why: 'the tone that IS in the scene, measured under its own name',
  },
  {
    sel: '[data-col-key=status] .status-badge.tone-err',
    wantNote: 'not present',
    wantRequired: true,
    why: 'tone missing while its chip says a row has that state — must FAIL, not pass',
  },
];

async function selfTest(page) {
  await page.setContent(FIXTURE);
  await page.waitForTimeout(200);
  // measureHere, not measure: the required-when flagging is part of what the controls check.
  const { rows, disagreements } = await measureHere(page, 'control', CONTROLS.map((c) => c.sel));
  let bad = 0;
  // Expected values are computed by compositing each layer and rounding, which is not
  // bit-identical to what the compositor does — the verified three-layer case in this
  // project's history computed rgb(41,63,80) where the browser painted rgb(40,63,79). So the
  // controls assert agreement to within AGREE_TOLERANCE, and print the delta. A tolerance
  // wide enough to hide a real defect would be several channels, not two.
  console.log('positive controls (expected values are arithmetic, not observations)\n');
  for (const c of CONTROLS) {
    // `'—'` as well as `(self)`: a selector that matched NOTHING has no part to name, and the
    // control that must fire is exactly that one — looking only for `(self)` reported it as
    // "got nothing", which is the control failing for the wrong reason.
    const row = rows.find((r) => r.sel === c.sel && (r.what === '(self)' || r.what === '—'));
    const dis = disagreements.find((d) => d.sel === c.sel && d.what === '(self)');
    if (c.wantNote) {
      // `wantRequired` is the difference between a note and a verdict: an absent selector the
      // page said was owed must come back flagged, because that flag is what makes it exit 1.
      const ok =
        row?.r === null &&
        String(row?.note || '').startsWith(c.wantNote) &&
        Boolean(row?.required) === Boolean(c.wantRequired);
      if (!ok) bad += 1;
      console.log(
        `${ok ? 'ok  ' : 'FAIL'}  ${c.sel.padEnd(46)} want ${c.wantRequired ? 'REQUIRED ' : ''}note "${c.wantNote}"` +
          ` got ${row ? (row.r === null ? `"${row.note}"` : `${row.r.toFixed(2)}:1`) : 'nothing'}  ${c.why}`,
      );
      continue;
    }
    const got = row?.bgRgb;
    const ok = agree(got, c.want) && row?.src === 'pixel' && (!c.domMustDiffer || Boolean(dis));
    if (!ok) bad += 1;
    const delta = got ? Math.max(...got.map((v, i) => Math.abs(v - c.want[i]))) : '—';
    console.log(
      `${ok ? 'ok  ' : 'FAIL'}  ${c.sel.padEnd(46)} want ${rgbText(c.want).padEnd(18)}` +
        ` got ${String(got ? rgbText(got) : 'none').padEnd(18)} d=${delta}  ${c.why}` +
        (dis ? `   [dom walk said ${dis.dom}]` : ''),
    );
  }
  // The three-layer stack is the case the previous fix was verified against: the DOM walk
  // and the browser agreed to within a rounding step. If that stops holding, the walk broke.
  const stack = disagreements.find((d) => d.sel === '#stack');
  if (stack) {
    bad += 1;
    console.log(`FAIL  #stack: dom walk ${stack.dom} vs pixel ${stack.pixel} — the ancestor walk regressed`);
  } else {
    console.log(`ok    #stack   dom walk and decoded pixel agree within ${AGREE_TOLERANCE} (the verified case)`);
  }
  console.log(bad ? `\n${bad} control(s) failed.` : '\nAll positive controls hold.');
  return bad;
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });

if (SELF_TEST) {
  const bad = await selfTest(page);
  await browser.close();
  process.exit(bad ? 1 : 0);
}

await page.goto(URL, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);

const rows = [];
const disagreements = [];
let worst = { r: Infinity };

for (let pass = 0; pass < 2; pass++) {
  if (pass === 1) {
    // Dismiss whatever pass 0's PREPARE opened before reaching for the toggle. PREPARE runs
    // INSIDE this loop, so a spec like `press:Control+k` leaves a modal mask over the whole
    // shell — and Naive's mask intercepts the click on `.theme-toggle`. An earlier version
    // did not do this, and checking the command palette's own colours (the surface that has
    // been got wrong twice, #200) died on the dark pass with a 30-second timeout whose
    // message named the toggle rather than the modal actually in the way. Escape is what the
    // app binds to close the palette and the drawer, so this is the reader's own exit.
    if (
      await page
        .locator('.n-modal-mask, .n-drawer-mask')
        .first()
        .isVisible()
        .catch(() => false)
    ) {
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
    }
    await page.click('.theme-toggle');
    await page.waitForTimeout(700);
  }
  // Read the theme from the DOM rather than assuming the toggle order — the app
  // remembers the last theme, so pass 0 is not reliably "light".
  const theme = (await page.evaluate(() => document.documentElement.classList.contains('kw-dark')))
    ? 'dark'
    : 'light';

  await openNav(page);
  await runPrepare(page, process.env.PREPARE);

  // The shell watchlist is measured on the cluster overview. Pass 1 inherits whatever pass 0's
  // last scene left on screen, so without this the dark pass would sample the base selectors
  // on a resource list and report `not present` for half of them — the two themes measuring
  // different pages, which is worse than measuring neither.
  if (scenes.length) await runPrepare(page, 'close;leaf:Cluster/Overview;wait:800');

  const out = await measureHere(page, theme, selectors);
  rows.push(...out.rows);
  disagreements.push(...out.disagreements);
  for (const r of out.rows) if (r.r !== null && r.r < worst.r) worst = r;

  // Then walk the scenes, in this same theme. A scene that cannot be reached (a leaf this
  // cluster does not have, a modal that failed to open) must not take the run down with it —
  // its selectors then report `not present` and are counted as unmeasured, which is the
  // truthful outcome and is visible in the summary.
  for (const scene of scenes) {
    try {
      await runPrepare(page, scene.prepare);
    } catch (e) {
      console.error(`scene "${scene.name}" could not be reached: ${e.message.split('\n')[0]}`);
    }
    const s = await measureHere(page, theme, scene.selectors);
    rows.push(...s.rows);
    disagreements.push(...s.disagreements);
    for (const r of s.rows) if (r.r !== null && r.r < worst.r) worst = r;
  }
}

const pad = (s, n) => String(s).padEnd(n);
console.log(pad('theme', 6) + pad('selector', 46) + pad('part', 18) + pad('ratio', 9) + 'verdict');
let failures = 0;
let unmeasured = 0;
let missing = 0;
for (const r of rows) {
  if (r.r === null) {
    unmeasured += 1;
    // An absent selector the page said was owed is a failure, not a note: see REQUIRED_WHEN.
    if (r.required) {
      failures += 1;
      missing += 1;
    }
    console.log(
      pad(r.theme, 6) + pad(r.sel, 46) + pad(r.what, 18) + pad('—', 9) + (r.required ? `MISSING  ${r.note}` : r.note),
    );
    continue;
  }
  const ok = r.r >= r.floor;
  if (!ok) failures += 1;
  console.log(
    pad(r.theme, 6) +
      pad(r.sel, 46) +
      pad(String(r.what).slice(0, 17), 18) +
      pad(r.r.toFixed(2) + ':1', 9) +
      (ok ? `pass (>=${r.floor})` : `FAIL (<${r.floor})  on ${r.bg}`),
  );
}

// Not a failure, but always worth printing: where the two methods disagree is where this
// instrument has historically been wrong, and it is how #250 was found in the first place.
if (disagreements.length) {
  console.log(`\n${disagreements.length} backdrop disagreement(s) — decoded pixel wins, DOM walk shown for comparison:`);
  for (const d of disagreements) {
    console.log(
      `  ${pad(d.theme, 6)}${pad(d.sel, 26)}${pad(String(d.what).slice(0, 17), 18)}` +
        `pixel ${pad(d.pixel, 17)}${d.r.toFixed(2)}:1   dom ${pad(d.dom, 17)}${d.domRatio.toFixed(2)}:1` +
        (d.share < 0.5 ? `   (mixed backdrop, mode covers ${(d.share * 100).toFixed(0)}%)` : ''),
    );
  }
}

// "All measured text" is doing a lot of work in that sentence, so say how much was NOT
// measured. Rows that report a note carry no ratio and therefore cannot fail, which means a
// run that silently stopped measuring things exits 0 and looks identical to a clean one —
// exactly the trap the `not present` rule warns about, and now countable rather than
// something you have to notice by scrolling. Deliberately not an automatic exit 1: a
// healthy cluster genuinely never renders `.ov-card.danger`, and a checker that cries wolf
// on that gets switched off.
const belowFloor = failures - missing;
if (belowFloor) {
  console.log(`\n${belowFloor} below the floor. Worst: ${worst.sel} ${worst.what} at ${worst.r.toFixed(2)}:1 (${worst.theme}).`);
} else if (!failures) {
  console.log(
    `\nAll measured text clears its floor in both themes (${rows.length - unmeasured} of ${rows.length} samples measured).`,
  );
}
if (missing) {
  console.log(
    `\n${missing} sample(s) MISSING: the page's own oracle said the surface was there and nothing was measured. ` +
      'That is a failed measurement counted as a failure, not a caveat — fix the scene, or the tone is uncovered.',
  );
}
if (unmeasured) {
  console.log(
    `${unmeasured} sample(s) produced no ratio — NOT passes. If that is most of the run, ` +
      'the run failed: bring the surfaces on screen with PREPARE and measure again.',
  );
}

await browser.close();
process.exit(failures ? 1 : 0);
