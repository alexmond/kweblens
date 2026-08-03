// ---- The cluster overview's Warnings table: column sizing as data (#257) ----
//
// Kept out of ClusterOverview.vue so the sizing POLICY can be asserted without a DOM, the same
// split as overview.ts / Overview.vue.
//
// What went wrong without it: the four columns declared no width and no ellipsis policy, so
// Naive's `table-layout: auto` sized them from content — and because Naive puts
// `word-break: break-word` on every cell, a column's minimum content width is ONE GLYPH. So the
// single genuinely long value on the page (an event message can be 200 characters) took the
// width, and the three bounded columns were squeezed under their own headers. Measured at
// 1900px, before this file existed:
//
//     Reason 155px   Object 280px   Message 1053px   Age 74px
//
// and at 1400px, Reason 111px / Object 193px / Message 702px / Age 58px — where the `Age`
// header's title box was 23px and rendered "Ag / e", `Reason` values wrapped mid-word, and
// `Pod/kw251-pending-scheduler-a` broke across two lines. It was never a lack of space: the
// space was there and had been handed to the column that did not need it.
//
// The policy, therefore:
//
//   - The three BOUNDED columns (`Reason`, `Object`, `Age`) declare a width. Their content has
//     a known shape — an event reason is a CamelCase token, an object is `Kind/name`, an age is
//     `4m` — so a width can be picked once and be right.
//   - `Message` declares NONE. It is the only genuinely variable column, so under
//     `table-layout: fixed` it takes whatever is left: the slack lands where the variance is.
//   - Below {@link WARN_TABLE_MIN_WIDTH} the table SCROLLS rather than crushing `Message` to a
//     word a line. That floor is the pane boundary from `responsive.ts`, deliberately reused
//     rather than a second number invented here.
import { ageToSeconds } from '../kube';
import { PANE_WIDE_PX } from '../responsive';
import type { EventSummary } from '../types';

/**
 * Width in px of each bounded column, and the reason it is that number. Widths are for 13px
 * text in a `size="small"` Naive table, whose cells add ~12px of padding a side.
 */
export const WARN_COLUMN_WIDTHS = {
  /** ~22 characters: `FailedCreatePodSandBox`, the longest reason in common use. */
  reason: 180,
  /** ~40 characters: `PersistentVolumeClaim/` is 22 of them before the name starts. */
  object: 300,
  /** `Age` plus its sort arrow. Values are two or three characters (`4m`, `13h`). */
  age: 90,
} as const;

/**
 * The width below which the table scrolls sideways instead of shrinking further — the same
 * 900px `narrow`/`wide` pane boundary the rest of the app uses, imported rather than restated.
 * Above it every column has its width and `Message` has the remainder; below it, horizontal
 * scroll is the honest answer, because the alternative is a message column three words wide.
 */
export const WARN_TABLE_MIN_WIDTH = PANE_WIDE_PX;

/** What `Message` is guaranteed at the narrowest the table ever lays out. */
export const warnMessageFloor = (): number =>
  WARN_TABLE_MIN_WIDTH - (WARN_COLUMN_WIDTHS.reason + WARN_COLUMN_WIDTHS.object + WARN_COLUMN_WIDTHS.age);

/** A column definition, in the shape Naive's `DataTableColumns` accepts. */
export interface WarnColumn {
  title: string;
  key: keyof EventSummary | 'age';
  width?: number;
  sorter: 'default' | ((a: EventSummary, b: EventSummary) => number);
  defaultSortOrder?: 'ascend';
}

/**
 * The Warnings table's columns. A function rather than a constant so the sorter closure is not
 * shared across component instances, and so the test gets a fresh object to assert on.
 */
export function warnColumns(): WarnColumn[] {
  return [
    { title: 'Reason', key: 'reason', width: WARN_COLUMN_WIDTHS.reason, sorter: 'default' },
    { title: 'Object', key: 'object', width: WARN_COLUMN_WIDTHS.object, sorter: 'default' },
    // No width: this is the column the slack belongs to.
    { title: 'Message', key: 'message', sorter: 'default' },
    {
      title: 'Age',
      key: 'age',
      width: WARN_COLUMN_WIDTHS.age,
      sorter: (a, b) => ageToSeconds(a.age) - ageToSeconds(b.age),
      defaultSortOrder: 'ascend',
    },
  ];
}
