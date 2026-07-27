import { ref } from 'vue';

// A promise-based confirm/prompt dialog. A single DialogHost is mounted at the app root;
// any component can `await useDialog().confirm(...)` instead of native window.confirm/prompt.
// Implemented as a module-level singleton (one dialog at a time) rather than provide/inject.

export interface ConfirmOpts {
  title?: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
}

export interface PromptOpts {
  title?: string;
  message: string;
  label?: string;
  initial?: string;
  placeholder?: string;
  type?: 'text' | 'number';
  confirmLabel?: string;
}

export type DialogState =
  | { kind: 'confirm'; opts: ConfirmOpts; resolve: (v: boolean) => void }
  | { kind: 'prompt'; opts: PromptOpts; resolve: (v: string | null) => void };

export const dialogState = ref<DialogState | null>(null);

export function closeDialog(): void {
  dialogState.value = null;
}

export interface DialogApi {
  confirm: (opts: ConfirmOpts) => Promise<boolean>;
  prompt: (opts: PromptOpts) => Promise<string | null>;
}

export function useDialog(): DialogApi {
  return {
    confirm: (opts) => new Promise<boolean>((resolve) => (dialogState.value = { kind: 'confirm', opts, resolve })),
    prompt: (opts) => new Promise<string | null>((resolve) => (dialogState.value = { kind: 'prompt', opts, resolve })),
  };
}
