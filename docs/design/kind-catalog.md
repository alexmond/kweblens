# Per-kind catalog in core — status after the detail endpoint (#148)

Re-audit of GH#148 after GH#136 landed (PRs #150, #152). #148 exists "to record the
analysis so it is not redone"; this is the second instalment, measured 2026-07-31 and
**re-checked against the code at `fb4e4fd` on 2026-08-13**. Where the two readings differ
the later one is marked inline rather than overwriting the earlier — the point of this
document is that the analysis is not redone, which requires knowing what moved.

**Verdict: #136 did not absorb #148. A real remainder exists — and it is exactly the
remainder #148 scoped. It stays parked.** Nothing here revises the original
recommendation; it records what changed, and three things the first pass did not know.

**Changed since the first instalment, and stated up front because one of them was wrong
in this document's own words: the sequencing gate has half fired.** GH#142 (agent tool
surface) **closed 2026-08-03** and the 15-tool MCP surface shipped, so the sentence below
that said "Neither has started" was false from that date. What it bought #148 is narrower
than the gate's wording suggests — see [Why no code landed](#why-no-code-landed-for-this),
rewritten below. GH#143 (TUI) has not started and there is still no `kweblens-tui` module
in the reactor.

## What #136 actually delivered

`GET /api/v1/clusters/{id}/detail/{resourceId}/{ns}/{name}` →
`{ object, relations: { <key>: Relation } }`, assembled in
[`DetailApiController`](../../kweblens-web/src/main/java/org/alexmond/kweblens/web/api/DetailApiController.java)
over [`RelationService`](../../kweblens-core/src/main/java/org/alexmond/kweblens/resource/RelationService.java).
That is the *relations* half. The *columns* half was explicitly deferred, in writing, in
both places that could have closed it:

- #136's closing note: "Not done here, tracked separately: folding column values into this
  endpoint (#148), which is the remaining half of the original catalog idea."
- PR #150's body: "Still to do on GH#136 … fold in column values per GH#148."

And the freshly-landed [plugin-framework research](../research/plugin-framework.md) treats
#148 as a live prerequisite, not a resolved one.

Server-side, nothing serves built-in kind columns.
[`NavCatalog`](../../kweblens-web/src/main/java/org/alexmond/kweblens/web/nav/NavCatalog.java)
carries routing and labelling only — no headers, widths, ordering, default-hidden sets or
tone hints. The only column data core produces is
[`PrinterColumn`](../../kweblens-core/src/main/java/org/alexmond/kweblens/resource/PrinterColumn.java),
and only for CRDs.

## The drift is real and still accruing

`kweblens-ui/src/columns.ts`, counting `render:` functions:

| Date | Commit | `render:` fns |
|---|---|---|
| 2026-07-27 | 6e74264 (#98) | 72 |
| 2026-07-29 | 374aa93 (#122) | 84 |
| 2026-07-31 | HEAD at the time | 84 |
| 2026-08-13 | fb4e4fd | **83** |

28 kinds, plus 6 more columns injected in `table.ts` (node usage bars, pod container
squares, pod CPU/memory). #148's headline "71 render functions" was measured just before
#122 landed; the catalog grew ~17% in the two days around the measurement.

**The 2026-08-13 row is the first time this number has gone down, and it went down for a
reason that matters here.** GH#341/#350 deleted seven hand-rolled per-kind status
renderers and replaced them with **one** shared `serverState` column reused seven times,
reading a verdict the server now computes. That is #148's own remedy, applied to one
column by a different epic — which is the strongest evidence so far that the remedy
works, and also why "the drift is monotonic" is no longer a safe argument to lean on. The
count is still 83 against a target of ~0, and the other 27 kinds are untouched, so the
cost side of the ledger is real; it is simply not automatic.

The simple-vs-computed split holds up, though the exact ratio depends on how you count:
by a crude classifier, roughly 45–55 of the 84 are plain dotted-path reads and roughly
30–38 involve ratios, filters, counts, joins, conditionals or formatting. Two independent
passes disagreed inside that band. The band is what matters, and it says the same thing
#148 concluded: **JSONPath alone cannot carry the catalog**, so option (c) stays a
convenience for the simple reads alongside server-computed values, never a replacement.

## Three things the first pass did not know

### 1. The client seam is cleaner than #148 assumed

`toneFor(key, text)` in `table.ts` dispatches status pills off the **stable column key,
not the display header** — `status`, `ready`, `type`. The genuinely rich cells (usage
bars, container squares) are `CellSpec` columns injected in `table.ts`, entirely outside
the `COLUMNS` map.

So a server-supplied column keyed `status` or `ready` would inherit pill rendering with no
client change, and the rich cells never need to move at all. #148 said the seam "already
exists"; it is better than that — it is keyed on exactly the identifier a server catalog
would supply.

### 2. Option (c) is already proven end-to-end, and its ceiling is known

`CrdService.printerColumns` → `GET …/resources/{resourceId}/columns` → `printerColumnDefs`
is a working server-declares/client-evaluates path today. Its evaluator (`resolvePath`)
supports dotted paths plus exactly one filter form, `[?(@.field=="value")]`. That is the
measured ceiling of the JSONPath approach in this codebase — useful for the simple reads,
and demonstrably unable to express the computed ones.

Note it is CRD-only: `useResourceData` gates the fetch on a dotted (group-qualified)
resource id, so built-in kinds never call it. Serving built-in columns there would
therefore be inert until the client is changed — which is why it is not a free first step.

### 3. #136 introduced a *second* client-side per-kind catalog

`kweblens-ui/src/components/relations.ts` hardcodes, for the relation keys the server
emits:

- key → section title (`TITLES`, falling back to the raw key)
- render order (a literal `['endpoints', 'selectedPods', 'mountedBy']`)
- per-relation table headers, via a binary `key === 'endpoints' ? endpointRows : podRows`

The server emits bare keys with no display metadata, so this is a manual mirror of
`RelationService.relationsFor`. The failure mode is specific: **a new server relation that
is neither endpoints-shaped nor pod-shaped renders through `podRows` and produces wrong
columns under an untitled heading** — silently, with no type error.

**Re-checked 2026-08-13: that failure mode is fixed; the mirror is not.** GH#203 replaced
the binary `key === 'endpoints' ? endpointRows : podRows` with a `PROJECTIONS` lookup
falling through to a loud `genericRows`, and `TITLES[key] ?? humanise(key)`, so an unknown
key now renders generically under a humanised heading instead of borrowing pod columns.
What remains is exactly what this section is about: `PROJECTIONS` still carries 3 of the
server's **12** relation keys and the render order is still the literal
`['endpoints', 'selectedPods', 'mountedBy']`, so the mirror is intact and the drift is now
*visible* rather than silent. Mitigated, not closed — which is why this stays the strongest
candidate for the first real slice.

This is #148's thesis reproduced on the newest surface, and unlike columns this metadata
genuinely *is* data (titles, order, headers — no closures), so it serialises to Java
cleanly. It is the strongest candidate for the first real slice. It was not built here
because its only consumer lives in `kweblens-ui`, and shipping an unconsumed field on a
live API is the speculative move #148 exists to prevent.

## Why no code landed for this

#148's sequencing gate: "when the TUI or the agent tool surface actually starts, extend
that endpoint to serve columns."

**As measured 2026-07-31**, neither had started, and the shipped MCP surface
(`ClusterTools`) had no per-kind branching at all — it returned `ResourceSummary` and
would have needed #142's work before a catalog changed anything it emitted.

**As re-checked 2026-08-13, the agent half has fired and the TUI half has not.** GH#142
closed 2026-08-03; the surface is now 15 `@Tool` methods across `ClusterTools` (4),
`DiagnosticTools` (4) and `HealthTools` (7). GH#143 has still produced no module —
`kweblens-tui` appears in no `pom.xml` in the reactor.

**But what the agent surface consumes is not the catalog this document is about, and that
distinction is the whole reason the verdict does not move.** The tools carry no kind-name
branching of their own — no `switch` and no `case "…"` anywhere in `web/mcp/*.java`, and
the package's only per-kind branch is `ToolRedaction`'s `if ("Secret".equals(...))`. They
do not need any, because a **server-side per-kind registry now exists** and they consume
it: `HealthTools` filters with `.filter((d) -> WorkloadHealth.supports(d.kind()))`, and
that `supports()` is one of five such predicates in `kweblens-core/health`
(`WorkloadHealth`, `ClusterObjectHealth`, `NetworkHealthService`, `StorageHealthService`,
`ConfigUsageService`) that the status vocabulary shipped in GH#336 dispatches over.

That registry answers **"what state is this object in"** for 13 kinds. It does not carry
headers, widths, ordering, default-hidden sets or tone hints, and it does not carry
relation display metadata. So it is evidence *for* #148's thesis — core can hold per-kind
knowledge and more than one client will read it — while leaving both of #148's own
catalogs with no server-side consumer. The gate's wording ("the agent tool surface starts")
has been met on its face; its intent ("something other than the SPA needs per-kind
**columns or relations**") has not.

So every available slice today is still either the standalone SPA refactor #148 forbids,
or a server field with no reader. Recording the analysis remains the work that is
available.

## Start conditions, and what to do first

The remaining trigger is a consumer for **columns or relations** specifically — GH#143
beginning is the clear one; a tool or a second client that needs a rendered column set
would also do it. GH#142 closing is not that trigger, for the reason above. Then, in
order:

1. **Relations metadata** (section 3) — smallest, genuinely data, closes a drift #203 only
   made visible, and exercises the "core declares, clients render" contract on twelve keys
   instead of eighty-three.
2. **Columns for pods / deployments / nodes** — #148's named starter set, covering simple
   reads, ratios and metric-backed columns. Parity tests against today's TypeScript output.
3. **The rest, kind by kind.** Rich cells stay client-rendered keyed by column key.

## Loose end worth closing independently — **CLOSED** (#312)

As measured 2026-07-31 the detail endpoint had **no server-side test**: `RelationService`
was well covered (8 tests in `RelationServiceTest`), but the HTTP contract — the
`{object, relations}` envelope, `Relation`'s null-omission that `types.ts` depends on, the
400 on a missing object, the unknown-`resourceId` path — was unverified. That was #136
hygiene rather than #148, but it was the thing a catalog would be built on top of.

`DetailEndpointsTest` (#312) now pins all of it, including all **12** relation keys
asserted **as a set** so a thirteenth without a test fails the build, each seeded with a
decoy so an assertion cannot pass on "non-empty". It found a live bug on the way in
(GH#313, fixed in #319). The foundation this document said a catalog would need is
therefore in place — which removes a reason not to start, without supplying the consumer
that is still the actual gate.
