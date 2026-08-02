import { computed, ref } from 'vue';

import { loadNavCollapsed, saveNavCollapsed } from '../prefs';

/**
 * The nav tree's collapsed/expanded state (#237).
 *
 * <p>The rail (55px) plus the nav tree (240px) spend 295px on navigation whatever the
 * viewport is — 29% of a 1024px laptop, before the resource table gets a pixel. Collapsing
 * the tree hands all 240px of that back to the content column; the cluster rail stays,
 * because it is only 55px and it is the one way to switch cluster.
 *
 * <p><b>Hidden, not shrunk to icons.</b> Nothing in the nav has an icon: leaves render a
 * label and a count, and the Custom Resources section is generated from whatever CRDs the
 * cluster happens to have, so a discovered kind could never be given one. An icon rail would
 * therefore be two-letter abbreviations — exactly the failure the cluster-selection review
 * already recorded for `initials()`, where four different clusters all read `KI`. Hiding the
 * panel is also worth 240px rather than the ~60px an icon strip would return.
 *
 * <p><b>Manual only — never automatic.</b> This deliberately does not watch the viewport.
 * A layout that rearranges itself while someone drags a window is a surprise, and honouring
 * a saved choice *and* a width rule needs a three-valued state (auto / forced-open /
 * forced-closed) whose edge cases are how "my sidebar keeps coming back" bugs happen. One
 * boolean, set by the person, remembered forever.
 *
 * <p>Collapsing is not a dead end: Ctrl/Cmd-K reaches any kind by name, which is why the
 * control says so.
 */
export function useNavCollapse() {
  const collapsed = ref(loadNavCollapsed());

  const toggle = (): void => {
    collapsed.value = !collapsed.value;
    saveNavCollapsed(collapsed.value);
  };

  /** Accessible name for the toggle — it says what the click will do, not what the state is. */
  const toggleLabel = computed(() => (collapsed.value ? 'Show navigation' : 'Collapse navigation'));

  /**
   * Tooltip. Mentions the command palette on the collapsing action, where the reassurance is
   * needed; on the way back it would only be noise.
   */
  const toggleTitle = computed(() =>
    collapsed.value ? 'Show navigation' : 'Collapse navigation — Ctrl/Cmd-K still finds any resource kind by name',
  );

  return { collapsed, toggle, toggleLabel, toggleTitle };
}
