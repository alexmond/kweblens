# Per-kind catalog in core — status after the detail endpoint (#148)

Re-audit of GH#148 after GH#136 landed (PRs #150, #152). #148 exists "to record the
analysis so it is not redone"; this is the second instalment, measured 2026-07-31.

**Verdict: #136 did not absorb #148. A real remainder exists — and it is exactly the
remainder #148 scoped. The trigger #148 named for starting it has not fired, so it stays
parked.** Nothing here revises the original recommendation; it records what changed, and
three things the first pass did not know.

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
| 2026-07-31 | HEAD | 84 |

28 kinds, plus 6 more columns injected in `table.ts` (node usage bars, pod container
squares, pod CPU/memory). #148's headline "71 render functions" was measured just before
#122 landed; the catalog grew ~17% in the two days around the measurement. This is the
cost side of the ledger, and it is not flat.

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

This is #148's thesis reproduced on the newest surface, and unlike columns this metadata
genuinely *is* data (titles, order, headers — no closures), so it serialises to Java
cleanly. It is the strongest candidate for the first real slice. It was not built here
because its only consumer lives in `kweblens-ui`, and shipping an unconsumed field on a
live API is the speculative move #148 exists to prevent.

## Why no code landed for this

#148's sequencing gate: "when the TUI or the agent tool surface actually starts, extend
that endpoint to serve columns." Neither has started — GH#143 (TUI) and GH#142 (agent tool
surface) are both open, and there is no `kweblens-tui` module in the reactor. The MCP
surface that *is* shipped (`ClusterTools`) has no per-kind branching at all; it returns
`ResourceSummary` and would need #142's work before a catalog would change anything it
emits.

So every available slice today is either the standalone SPA refactor #148 forbids, or a
server field with no reader. Recording the analysis is the work that was available.

## Start conditions, and what to do first

Start when GH#143 or GH#142 begins. Then, in order:

1. **Relations metadata** (section 3) — smallest, genuinely data, fixes a live drift
   hazard, and exercises the "core declares, clients render" contract on three keys
   instead of eighty-four.
2. **Columns for pods / deployments / nodes** — #148's named starter set, covering simple
   reads, ratios and metric-backed columns. Parity tests against today's TypeScript output.
3. **The rest, kind by kind.** Rich cells stay client-rendered keyed by column key.

## Loose end worth closing independently

The detail endpoint has **no server-side test**. `RelationService` is well covered (8 tests
in `RelationServiceTest`), but the HTTP contract — the `{object, relations}` envelope,
`Relation`'s null-omission that `types.ts` depends on, the 400 on a missing object, the
unknown-`resourceId` path — is unverified. That is #136 hygiene rather than #148, but it is
the thing a catalog would be built on top of.
