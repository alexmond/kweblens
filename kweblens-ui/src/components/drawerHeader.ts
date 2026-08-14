// ---- What the detail drawer's header shows when it has the room (#233) ----
//
// The drawer is 520px or expanded to fill the content column, and until now the header
// stacked the same way at both: `KIND name` on one line, the tab row below it, and nothing
// at all in the band to the right of the tabs. This module decides the two things that
// change with width, in TypeScript so both are testable without a DOM:
//
//   drawerBadges  — the identity chips (kind, namespace, Helm release) that sit UNDER the
//                   title at wide instead of wrapping around it.
//   drawerActions — which of the object's actions are worth a button next to the tab row,
//                   and the full menu that stays available at every width.
//
// The width itself is not decided here. Nothing measures: the template hands the two
// alternatives to the `kw-when-wide` / `kw-when-narrow` helpers and the pane's container
// query picks (see `responsive.ts`).
import { containerNames, objNs } from '../kube';
import type { RowAction, RowActionDef, RowActionOption } from '../rowActions';
import { ROW_ACTIONS, rowActionOptions } from '../rowActions';
import type { KindAccess, KubeObject } from '../types';

/** One identity chip under the drawer title. `tone` only distinguishes it visually. */
export interface DrawerBadge {
  key: string;
  text: string;
  tone: 'kind' | 'plain' | 'helm';
  title?: string;
}

/**
 * The object's identity, as chips.
 *
 * Kind is always first and always present — it is the same word the narrow header shows
 * inline, rendered again here so the two layouts are alternatives rather than one being a
 * strict subset that loses information. Namespace and the owning Helm release only appear
 * when the object has them, so a cluster-scoped object gets one chip rather than a row of
 * em-dashes.
 */
export function drawerBadges(obj: KubeObject): DrawerBadge[] {
  const badges: DrawerBadge[] = [{ key: 'kind', text: obj.kind ?? '—', tone: 'kind' }];
  const ns = objNs(obj);
  if (ns) {
    badges.push({ key: 'ns', text: ns, tone: 'plain', title: `Namespace ${ns}` });
  }
  const release = (obj.metadata?.annotations ?? {})['meta.helm.sh/release-name'];
  if (release) {
    badges.push({ key: 'helm', text: `Helm: ${release}`, tone: 'helm', title: `Helm release ${release}` });
  }
  return badges;
}

/**
 * Actions promoted to a button, in the order they are offered.
 *
 * An explicit list rather than "the first N that apply": the registry's own order is the
 * order the kebab reads well in, which puts `Attach to Pod` before `Logs`, and a toolbar
 * has room for about three things so the three it shows had better be the three anyone
 * reaches for. Nothing destructive is on it — Drain, Delete and Force Delete are one
 * mis-aimed click each, and they stay behind the menu at every width.
 */
const PROMOTED: RowAction[] = ['logs', 'logsAll', 'terminal', 'forward', 'restart', 'scale', 'trigger'];

/** How many buttons the tab row shows before the rest stay in the menu. */
export const DRAWER_BUTTON_LIMIT = 3;

/**
 * The drawer's actions: buttons for the wide layout, and the menu that backs both.
 *
 * `menu` is EVERY applicable action, not the leftovers. A button that is also in the menu
 * is mild redundancy; a menu whose contents changed with the drawer's width would mean an
 * action reachable at one width and not the other, which is the failure #232 is explicitly
 * written to avoid. It also means the promotion decision needs no width — only the CSS
 * hides the buttons when there is no room for them.
 */
export function drawerActions(
  obj: KubeObject,
  access?: KindAccess | null,
): { buttons: RowActionDef[]; menu: RowActionOption[] } {
  const ctx = { kind: obj.kind ?? '', suspended: Boolean((obj.spec as Record<string, unknown>)?.suspend) };
  const applies = new Map(ROW_ACTIONS.filter((a) => a.applies(ctx)).map((a) => [a.id, a]));
  // A container-scoped action on a multi-container pod needs a submenu to say WHICH
  // container, and a submenu is not a button — those stay in the menu only.
  const multiContainer = containerNames(obj).length > 1;
  const buttons = PROMOTED.map((id) => applies.get(id)).filter(
    (a): a is RowActionDef => !!a && !a.danger && !(a.containerScoped && multiContainer),
  );
  return { buttons: buttons.slice(0, DRAWER_BUTTON_LIMIT), menu: rowActionOptions(obj, access) };
}
