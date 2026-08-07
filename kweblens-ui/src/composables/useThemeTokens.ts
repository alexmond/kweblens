import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue';

/**
 * Resolve design tokens (CSS custom properties) to real colour strings, and re-resolve them
 * when the theme flips.
 *
 * A `<canvas>` cannot resolve `var(--x)`. Assigning `ctx.fillStyle = 'var(--muted)'` is rejected
 * outright and the previous value stands, so an echarts CanvasRenderer handed a token string
 * silently falls back to its own default — measured as pure black axis labels and grid lines on
 * the dark theme's `--panel` (#1f242a), a contrast ratio of 1.34:1. CSS cannot rescue it: text
 * painted into a canvas has no DOM node to style, which is also why `contrast-check.mjs` reports
 * nothing at all for it. Any colour destined for a canvas has to be resolved here first.
 *
 * The `<html>` `kw-dark` class is the only signal that the theme changed (App.vue toggles it),
 * so this watches for it with the same MutationObserver the YAML editor uses.
 */
export function useThemeTokens(names: readonly string[]): Ref<Record<string, string>> {
  const resolve = (): Record<string, string> => {
    const style = getComputedStyle(document.documentElement);
    return Object.fromEntries(names.map((n) => [n, style.getPropertyValue(n).trim()]));
  };
  const tokens = ref<Record<string, string>>(resolve());
  let observer: MutationObserver | null = null;
  onMounted(() => {
    // Re-read on mount as well as at setup: the stylesheet may not have applied yet when the
    // component was created, and an empty token is the same silent black as an unresolved one.
    tokens.value = resolve();
    observer = new MutationObserver(() => (tokens.value = resolve()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  });
  onBeforeUnmount(() => {
    observer?.disconnect();
    observer = null;
  });
  return tokens;
}
