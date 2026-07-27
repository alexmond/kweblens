import { onBeforeUnmount, onMounted } from 'vue';

/** Call `handler` whenever Escape is pressed while the component is mounted. */
export function useEscapeKey(handler: () => void): void {
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
      handler();
    }
  };
  onMounted(() => window.addEventListener('keydown', onKey));
  onBeforeUnmount(() => window.removeEventListener('keydown', onKey));
}
