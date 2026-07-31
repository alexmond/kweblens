# Cluster selection: the design review workstream 1 was waiting on

Issue: GH#141 workstream 1. Date: 2026-07-31.

The ticket says the rail "stops scaling past six clusters" and that this "needs reviewing
design samples from competitors before committing". This is that review, and a
recommendation. **No code has been written** — that is the point of doing this first.

## What is there now

A vertical strip of tiles down the far left (`Sidebar.vue`, `.rail`), copied from Freelens.
The current tile is highlighted, and each carries `title="cluster.name"` so hover reveals
the full name. Both themes are styled (`html.kw-dark .rail` / `.tile`) — the rail is not
one of the hardcoded-light-literal bugs PR #172 swept up.

Two failure modes, checked against the code rather than assumed:

1. **The label cannot distinguish clusters.** `initials()` in `kube.ts` is literally
   `id.slice(0, 2).toUpperCase()` — the first two characters of the **id**, with no
   word-splitting and no collision handling. So `prod-eu` and `prod-us` are both `PR`;
   `staging` and `sandbox` are both `SA`. The hover title resolves it, but only one tile at
   a time and only after you already suspect a problem. A label that looks precise and
   isn't is worse than an obviously-abbreviated one.
2. **No overflow behaviour.** `.rail` is `display: flex; flex-direction: column` with a
   `gap`, and no `overflow-y` and no scroll affordance. Past a viewport's worth of tiles
   the extras are simply not reachable — this is the specific mechanism behind the ticket's
   "stops scaling past six".

Note these are different problems. (2) is the one the ticket names and is a small fix; (1)
bites at *three* clusters and is the reason a bigger change is worth considering at all.

## What the competitors do

From `docs/competitive-review/competitor-analysis.md`, the multi-cluster row:

| Product | Approach | Worth taking? |
|---|---|---|
| **Radar** | **Simultaneous** — `+`-joined URLs, one list view spanning clusters, with a **Cluster column** | The strongest idea in the field. Also: **web users cannot add clusters**, which is the gap #141 W2 closes. |
| **Lens / Freelens** | Icon rail, desktop, plus a "Catalog" landing page listing every cluster with status | The rail is what kweblens copied; the **catalog page** is the half it did not |
| **Rancher / OpenShift / Devtron** | Fleet products: a cluster **list page** is the home screen, agent per cluster | Right instinct for scale, wrong weight — kweblens is "one jar, not a platform" |
| **Headlamp** | Cluster chooser page on load | Simple and honest |
| **Skooner / k8sgpt** | One cluster only | n/a |

The pattern across everything that handles more than a handful: **a page, not a strip**. The
rail survives in the desktop apps because a desktop window has a permanent left gutter and
users have 2–3 clusters. Neither assumption holds for a self-hosted server that a team
points at their whole estate.

## Recommendation

**Both, with a clear division of labour — and the rail stops being the primary mechanism.**

**1. A Clusters page as the landing screen.** Name, context, API server, reachability, and
the health summary the overview already computes. This is the place to add, edit and remove
a cluster once #141 W2 lands, which is exactly why W2 should not wait for this: the page is
a consumer of that API, not a prerequisite for it.

**2. A command-palette-style switcher** (⌘K) as the fast path. Type-to-filter beats any
number of tiles, and the competitive review already flags a **command-palette gap** against
k9s. One control solves two problems.

**3. Keep the rail, but fix what it shows and cap it.** It is genuinely good for 2–3 pinned
clusters. Show only pinned or recent ones, cap at ~6, and end with a "More…" that opens the
page — which also disposes of the overflow problem without needing a scrollbar in a 38px
gutter. Add a colour derived from the cluster id alongside the initialism: the hover title
already carries the full name, so what the tile is missing is an *at-a-glance* distinction
between two tiles that both read `PR`, and colour supplies that without hover.

**Deliberately not recommended: Radar's simultaneous multi-cluster view.** It is the most
interesting idea here, and it is a different product decision rather than a selection
control — every list, watch, and detail route currently addresses exactly one cluster id,
and `ClusterRegistry` hands out one client per id. Making a list span clusters is a change
to the access layer and the watch topology, not to the sidebar. Worth its own ticket if it
is wanted; it should not be smuggled in under "the rail does not scale".

## Sequencing, and why W1 was right to wait

- **W2 (add/edit/remove) first** — the Clusters page is mostly a UI for it, so building the
  page first would mean building it twice.
- **Then the page**, then the palette, then the rail trim.
- The palette is independently useful and could be pulled forward if the command-palette gap
  is judged more pressing than multi-cluster ergonomics.

## What this review did not do

It compared **documented behaviour and screenshots already in this repo**, not live
products. Nobody installed Rancher to see how its cluster list feels at fifty clusters.
That is enough to choose a direction — page-not-strip is unanimous among the products that
handle scale — but not enough to copy any specific layout, and the recommendation above
deliberately stops at structure rather than pixels.
