// ---- Naive's NDataTable, painted from the app's table tokens (#478) ----
//
// A category overview renders two tables one above the other and they did not look like the
// same product: the findings table is a hand-written `.mini`, Recent Events is an
// `NDataTable`. The measured difference and the reason a `var()` is safe here are in the
// `--table-*` block at the top of `styles.css`; this file is the half that carries those
// tokens across the component boundary.
//
// WHY THE THEME AND NOT THE COMPONENT. `EventsPane` is shared — the drawer's Events tab uses
// the same instance — so "make it look like the top table" is a change to every place events
// are listed. Restyling it into a `.mini` would have given up `NDataTable`'s sorting, which
// the reported screenshot shows in use (Type, Reason, Message, Age all sort). Pointing naive's
// theme at our tokens keeps the sorting and reaches every other table in the app at the same
// time, which is #184's shape: naive's primary was pointed at the app accent rather than the
// component being replaced.
//
// THE THREE VARIANTS ARE NOT DECORATION. `NDataTable` picks `*Modal` / `*Popover` colours when
// it is rendered inside one, and naive's DRAWER counts as modal — measured, the drawer's
// events table painted `rgb(44, 44, 50)`, naive's dark modal colour, while the `.mini` tables
// on the tab beside it painted the panel tokens. Overriding only the base names would have
// fixed the overview and left the drawer exactly as reported.
//
// `tdColorSorting` is deliberately the plain row colour. Naive tints the sorted COLUMN
// (measured rgb(38, 38, 42) dark / rgb(247, 247, 250) light against the row), which on the
// events table — sorted by Age out of the box — draws a vertical band down the last column
// that reads as a column divider the other table does not have. The sort arrow and the
// header's own `--table-head-active-bg` say which column it is.

/** The colour roles, once. Applied to the base, modal and popover names below. */
const SURFACE = {
  borderColor: 'var(--table-line)',
  thColor: 'var(--table-head-bg)',
  thColorHover: 'var(--table-head-active-bg)',
  thColorSorting: 'var(--table-head-active-bg)',
  tdColor: 'var(--table-row-bg)',
  tdColorHover: 'var(--table-row-hover-bg)',
  tdColorSorting: 'var(--table-row-bg)',
  tdColorStriped: 'var(--table-row-alt-bg)',
} as const;

const suffixed = (suffix: '' | 'Modal' | 'Popover'): Record<string, string> =>
  Object.fromEntries(Object.entries(SURFACE).map(([k, v]) => [`${k}${suffix}`, v]));

/**
 * The `DataTable` entry of the app's Naive theme overrides.
 *
 * Every value is a `var()` and therefore theme-independent: the tokens it names are redefined
 * by `html.kw-dark`, so one object serves both themes. `thFontWeight` is the one non-colour —
 * naive owns the header's weight, and 600 is what `.mini th` uses.
 */
export const dataTableOverrides: Record<string, string> = {
  ...suffixed(''),
  ...suffixed('Modal'),
  ...suffixed('Popover'),
  tdTextColor: 'var(--table-row-fg)',
  thTextColor: 'var(--table-head-fg)',
  thFontWeight: '600',
};
