// ---- Fitting a resource table's columns to the width it has (#238) ----
//
// The problem, measured: at a 1024px viewport the Nodes list has 687px of table and wants
// 1514px of columns, so nine of its fourteen columns are reachable only by scrolling
// sideways. Pods wants 1314px in the same space. #122 chose horizontal scrolling over
// squeezing text until it wraps, and that choice stands — but "fits" and "scroll for half
// the columns" were the only two states, with no step in between.
//
// WHY THIS IS NOT A BREAKPOINT. The responsive primitive (`responsive.ts`) names one
// boundary, 900px, and `responsive.test.ts` fails the build if a second literal appears.
// Nothing here adds one, and nothing here should: a breakpoint cannot answer this question.
// The width at which a table stops fitting is a property of its COLUMN SET, not of the
// viewport — Deployments fits in 938px, Pods needs 1314px and Nodes 1514px, so any single
// number would be wrong for two of the three. What is needed is arithmetic: add the columns
// up, and if they do not fit, take the least useful ones off. That is continuous, it is
// exact for every kind including CRDs whose columns nobody here has seen, and it never
// disagrees with the drawer about what "wide" means because it never uses the word.
//
// DROP ORDER — last declared, first dropped. Column sets in `columns.ts` are written
// most-important-first, following `kubectl get`: Pods lead with Ready and Status and end
// with Node and the live CPU/Memory columns; Nodes lead with Status and Roles and end with
// Conditions and usage bars. So the declaration order already IS the editorial ranking, and
// reading it backwards gives the drop order without a new field on every column and without
// a second opinion to keep in sync with `defaultHidden`.
//
// WHAT IT WILL NOT DO. Auto-hiding is only worth anything if it makes the table fit; if it
// cannot — because the user pinned more columns than the space holds, or because the
// framework columns alone overflow — it hides NOTHING and the table scrolls as before.
// Hiding columns AND still scrolling is the worst of both: information gone, and the
// scrollbar that was the reason for taking it still there.

/** A column as far as fitting is concerned: a key and the width the table gives it. */
export interface FitColumn {
  key: string;
  width?: number;
}

/**
 * The pixel widths `ResourceTable` hands NDataTable. Here rather than in the component so
 * the fit can be tested without a DOM — and so there is one place to change them.
 */
export const COL_WIDTH = {
  select: 40,
  name: 260,
  /** Extra width the tree-expand arrow takes in the Name column. */
  expand: 34,
  namespace: 150,
  age: 80,
  menu: 44,
  /** The readable floor for a column that declares no width of its own. */
  data: 110,
} as const;

/** The width of the columns the table always renders itself (select, name, age, kebab…). */
export function chromeWidth(has: { expandable?: boolean; namespace?: boolean; age?: boolean }): number {
  return (
    COL_WIDTH.select +
    COL_WIDTH.name +
    (has.expandable ? COL_WIDTH.expand : 0) +
    (has.namespace ? COL_WIDTH.namespace : 0) +
    (has.age ? COL_WIDTH.age : 0) +
    COL_WIDTH.menu
  );
}

/** What one kind-specific column costs. */
export const columnWidth = (c: FitColumn): number => c.width ?? COL_WIDTH.data;

/** What the whole table wants: the framework columns plus every one of `cols`. */
export const tableWidth = (cols: readonly FitColumn[], chrome: number): number =>
  cols.reduce((sum, c) => sum + columnWidth(c), chrome);

/**
 * The columns to hide so the table fits `available` px.
 *
 * `keep` is the user's override — a column checked in the Columns picker is never taken away
 * again by width, which is what makes the picker still the last word.
 *
 * Returns an empty set (hide nothing) when the table already fits, when the available width
 * is not yet known (0 before first layout — hiding everything for one frame is a flicker
 * nobody asked for), and when hiding everything droppable still would not fit.
 */
export function autoHiddenCols(
  cols: readonly FitColumn[],
  opts: { available: number; chrome: number; keep?: ReadonlySet<string> },
): Set<string> {
  const { available, chrome } = opts;
  const keep = opts.keep ?? new Set<string>();
  const hidden = new Set<string>();
  if (!Number.isFinite(available) || available <= 0) {
    return hidden;
  }
  let total = tableWidth(cols, chrome);
  if (total <= available) {
    return hidden;
  }
  // The floor: what remains when everything droppable is gone. If that still overflows,
  // dropping is pure loss — see "WHAT IT WILL NOT DO" above.
  const floor = tableWidth(
    cols.filter((c) => keep.has(c.key)),
    chrome,
  );
  if (floor > available) {
    return hidden;
  }
  for (let i = cols.length - 1; i >= 0 && total > available; i -= 1) {
    const c = cols[i];
    if (keep.has(c.key)) {
      continue;
    }
    hidden.add(c.key);
    total -= columnWidth(c);
  }
  return hidden;
}

/**
 * The Columns picker's one decision, as data: what checking or unchecking `key` means.
 *
 * Two sets rather than one, because "hidden" now has two causes and they must not be
 * confused. `hidden` is the user saying no; `keep` is the user saying yes to something the
 * width would otherwise have taken. A column in neither set is one the user has never had an
 * opinion about, and is the only kind auto-fit is allowed to decide for.
 */
export function nextColumnChoice(
  key: string,
  state: { hidden: ReadonlySet<string>; keep: ReadonlySet<string>; autoHidden: ReadonlySet<string> },
): { hidden: Set<string>; keep: Set<string> } {
  const hidden = new Set(state.hidden);
  const keep = new Set(state.keep);
  const visible = !state.hidden.has(key) && !state.autoHidden.has(key);
  if (visible) {
    hidden.add(key);
    keep.delete(key);
  } else {
    hidden.delete(key);
    keep.add(key);
  }
  return { hidden, keep };
}
