import type { Ref } from 'vue';
import { watch } from 'vue';

/** Close a fixed-positioned menu on outside-click / scroll / resize while it is open.
 *  `open` is a ref; `close` clears it. `btn`/`menu` are element refs. */
export function useMenuDismiss(
  open: Ref<boolean>,
  close: () => void,
  btn: Ref<HTMLElement | null>,
  menu: Ref<HTMLElement | null>,
): void {
  const onDoc = (e: MouseEvent) => {
    const t = e.target as Node;
    if (menu.value?.contains(t) || btn.value?.contains(t)) {
      return;
    }
    close();
  };
  const dismiss = () => close();
  watch(open, (isOpen) => {
    if (isOpen) {
      document.addEventListener('mousedown', onDoc);
      window.addEventListener('scroll', dismiss, true);
      window.addEventListener('resize', dismiss);
    } else {
      document.removeEventListener('mousedown', onDoc);
      window.removeEventListener('scroll', dismiss, true);
      window.removeEventListener('resize', dismiss);
    }
  });
}
