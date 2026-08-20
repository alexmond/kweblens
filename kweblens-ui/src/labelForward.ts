/**
 * Forwarding a click from a text label to the control it names (GH#506).
 *
 * `<label>` forwards a click only to a *labelable* element — `input`, `select`, `textarea`,
 * `button`, `meter`, `output`, `progress`. A naive-ui control is none of those: `NSwitch`
 * renders a `<div role="switch">`, so a `<label>` wrapped round one has `label.control === null`
 * and the browser agrees there is nothing to forward to. Measured on a running instance, the
 * words beside the YAML tab's Hide Managed Fields switch produced **0** state changes while
 * `.yaml-toggle` painted `cursor: pointer` over them — an affordance for a click target that
 * was not one.
 *
 * So the forward is explicit. And an explicit forward has exactly one way to go wrong, which is
 * the reason this is a named predicate with its own test rather than three lines inline: a click
 * that lands on the CONTROL bubbles up to the wrapper's handler too, so the control fires once
 * on its own and once again from the wrapper — a double toggle, which on a two-state control
 * looks like a switch that has stopped working rather than one that fired twice.
 *
 * The rule is therefore: forward only a click that did **not** land on something which activates
 * itself. That is read off what the markup DECLARES — a native interactive tag or an ARIA role
 * that carries its own activation — never off a library class name, because a class name is a
 * fact about naive-ui's current release and a role is a fact about the control.
 */

/**
 * Things that handle their own activation, so a click on one has already done the work.
 *
 * Roles as well as tags: `NSwitch`'s root is a `<div role="switch">` with `tabindex="0"`, which
 * is precisely a control that is not labelable and is still activated by the click.
 *
 * The attribute values are UNQUOTED, and that is load-bearing rather than a style choice. Both
 * spellings are valid CSS, but `classesAreEmitted.test.ts` harvests single-token double-quoted
 * literals out of every script because that is what a composed class name looks like — so a
 * double-quoted role name in this list is read as an emission of the class of the same name. The
 * tab role is the one that bites: the sheet still carries a dead rule under that name, exempted
 * as dead, and naming the role in quotes made the exemption look stale. Measured — it failed on
 * exactly that entry, three times: once on the selector, then twice on the COMMENT written to
 * explain it, because a comment naming what it forbids is scanned like any other text and a
 * backtick counts too. So this paragraph spells no role name in any kind of quote.
 */
export const SELF_ACTIVATING =
  'button,a[href],input,select,textarea,summary,[role=switch],[role=checkbox],[role=button],[role=tab],[role=menuitem],[role=combobox],[contenteditable]';

/** The two DOM methods this needs, so the decision is testable without a document. */
interface TargetLike {
  closest(selectors: string): unknown;
}
interface ContainerLike {
  contains(node: unknown): boolean;
}

const isTarget = (v: unknown): v is TargetLike => !!v && typeof (v as TargetLike).closest === 'function';
const isContainer = (v: unknown): v is ContainerLike => !!v && typeof (v as ContainerLike).contains === 'function';

/**
 * Should a click on a label's text be forwarded to the control beside it?
 *
 * @param target    the click's `target` — where the pointer actually landed
 * @param container the element the handler is bound to (`currentTarget`), i.e. the label
 * @param within    selector for things that activate themselves; override only to narrow it
 *
 * `closest` walks all the way to the document root, so a self-activating ancestor ABOVE the
 * label — a toolbar that is itself a button, say — would otherwise suppress every forward. The
 * hit only counts when it is inside the label, which is what `container.contains` decides.
 * A target that is not an element (a text node, `document`) is not forwarded: nothing is known
 * about where it landed, and a spurious toggle is worse than a missed one.
 */
export function forwardsLabelClick(target: unknown, container: unknown, within: string = SELF_ACTIVATING): boolean {
  if (!isTarget(target)) return false;
  const hit = target.closest(within);
  if (hit === null || hit === undefined) return true;
  return isContainer(container) ? !container.contains(hit) : false;
}
