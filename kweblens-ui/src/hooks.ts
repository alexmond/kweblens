import type { RefObject } from 'react';
import { useEffect, useState } from 'react';

/** Call `handler` whenever Escape is pressed while the component is mounted. */
export function useEscapeKey(handler: () => void): void {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        handler();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [handler]);
}

/** Close a portal menu on outside-click / scroll / resize while it is open. */
export function useMenuDismiss(
  open: boolean,
  setOpen: (v: boolean) => void,
  btnRef: RefObject<HTMLButtonElement | null>,
  menuRef: RefObject<HTMLDivElement | null>,
): void {
  useEffect(() => {
    if (!open) {
      return;
    }
    const onDoc = (e: MouseEvent) => {
      const t = e.target as Node;
      if (menuRef.current?.contains(t) || btnRef.current?.contains(t)) {
        return;
      }
      setOpen(false);
    };
    const dismiss = () => setOpen(false);
    document.addEventListener('mousedown', onDoc);
    // The menu is fixed-positioned, so close it if the page scrolls or resizes under it.
    window.addEventListener('scroll', dismiss, true);
    window.addEventListener('resize', dismiss);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      window.removeEventListener('scroll', dismiss, true);
      window.removeEventListener('resize', dismiss);
    };
  }, [open, setOpen, btnRef, menuRef]);
}

export type SortState = { key: string; dir: number };

/** Reusable column-sorting for the simple record tables (mirrors ResourceTable's UX). */
export function useTableSort<T>(rows: T[], initialKey: string, value: (row: T, key: string) => string | number) {
  const [sort, setSort] = useState<SortState>({ key: initialKey, dir: 1 });
  const sorted = [...rows].sort((a, b) => {
    const va = value(a, sort.key);
    const vb = value(b, sort.key);
    if (typeof va === 'number' && typeof vb === 'number') {
      return (va - vb) * sort.dir;
    }
    return String(va).localeCompare(String(vb), undefined, { numeric: true }) * sort.dir;
  });
  const clickHeader = (key: string) =>
    setSort((prev) => (prev.key === key ? { key, dir: -prev.dir } : { key, dir: 1 }));
  return { sorted, sort, clickHeader };
}
