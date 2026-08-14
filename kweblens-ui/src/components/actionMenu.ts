import type { DropdownOption } from 'naive-ui';
import { h } from 'vue';

import type { RowActionOption } from '../rowActions';

/**
 * The action registry's plain options, as naive-ui dropdown options.
 *
 * <p>Two surfaces render the same menu — the table's row kebab and the detail drawer's (#233)
 * — so the projection lives here rather than twice. `rowActions.ts` stays framework-agnostic
 * (it describes the shape and never imports naive's types), and this is the one place that
 * crosses over.
 *
 * <p>The only thing it adds is how a REFUSED action looks: the label becomes two lines, the
 * action's name and the sentence saying the deployment's service account cannot do it here.
 * A `title` attribute would have been less code and no use — nobody hovers a greyed-out menu
 * item to find out why it is grey, and "disabled with no explanation" is the defect #354 set
 * out to remove rather than a milder form of it.
 *
 * <p>The DECISION is not made here. `permissions.ts` decides, `rowActions.ts` carries the
 * result, and this only draws it — so the fail-open rule has exactly one implementation.
 */
export function naiveActionOptions(options: RowActionOption[]): DropdownOption[] {
  return options.map((o) =>
    o.deniedReason
      ? {
          ...o,
          label: () =>
            h('span', { class: 'menu-denied' }, [
              h('span', { class: 'menu-denied-label' }, o.label),
              h('span', { class: 'menu-denied-why' }, o.deniedReason),
            ]),
        }
      : o,
  ) as unknown as DropdownOption[];
}
