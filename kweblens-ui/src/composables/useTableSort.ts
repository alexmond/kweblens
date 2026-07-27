import { computed, ref } from 'vue';

export interface SortState {
  key: string;
  dir: number;
}

/** Reusable column-sorting for the simple record tables (mirrors ResourceTable's UX).
 *  `rows` is a getter so the sorted output stays reactive to its source. */
export function useTableSort<T>(rows: () => T[], initialKey: string, value: (row: T, key: string) => string | number) {
  const sort = ref<SortState>({ key: initialKey, dir: 1 });
  const sorted = computed(() =>
    [...rows()].sort((a, b) => {
      const va = value(a, sort.value.key);
      const vb = value(b, sort.value.key);
      if (typeof va === 'number' && typeof vb === 'number') {
        return (va - vb) * sort.value.dir;
      }
      return String(va).localeCompare(String(vb), undefined, { numeric: true }) * sort.value.dir;
    }),
  );
  const clickHeader = (key: string) => {
    sort.value = sort.value.key === key ? { key, dir: -sort.value.dir } : { key, dir: 1 };
  };
  return { sorted, sort, clickHeader };
}
