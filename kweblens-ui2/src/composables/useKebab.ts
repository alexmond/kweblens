import { ref } from 'vue';

import { useMenuDismiss } from './useMenuDismiss';

export interface Anchor {
  left: number;
  top: number;
  up: boolean;
}

/** Shared open-state + fixed-position anchoring for the kebab (⋮) menus. The menu renders in a
 *  Teleport to body (never clipped by table overflow); it flips upward near the viewport bottom. */
export function useKebab() {
  const open = ref(false);
  const anchor = ref<Anchor | null>(null);
  const btnRef = ref<HTMLElement | null>(null);
  const menuRef = ref<HTMLElement | null>(null);
  const close = () => (open.value = false);
  useMenuDismiss(open, close, btnRef, menuRef);

  const toggle = (estHeight: number) => {
    if (open.value) {
      open.value = false;
      return;
    }
    const r = btnRef.value?.getBoundingClientRect();
    if (r) {
      const up = r.bottom + estHeight > window.innerHeight;
      anchor.value = { left: r.right, top: up ? r.top : r.bottom, up };
    }
    open.value = true;
  };

  const menuStyle = (a: Anchor) => ({
    position: 'fixed' as const,
    left: a.left + 'px',
    top: a.top + 'px',
    transform: a.up ? 'translate(-100%, -100%)' : 'translate(-100%, 0)',
  });

  return { open, anchor, btnRef, menuRef, toggle, close, menuStyle };
}
