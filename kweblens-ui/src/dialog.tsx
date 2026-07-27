import type { ReactNode } from 'react';
import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { useEscapeKey } from './hooks';

// A promise-based confirm/prompt dialog, provided once at the app root so any component
// can `await useDialog().confirm(...)` instead of the native window.confirm/prompt.

type ConfirmOpts = { title?: string; message: string; confirmLabel?: string; danger?: boolean };
type PromptOpts = {
  title?: string;
  message: string;
  label?: string;
  initial?: string;
  placeholder?: string;
  type?: 'text' | 'number';
  confirmLabel?: string;
};

export type DialogApi = {
  confirm: (opts: ConfirmOpts) => Promise<boolean>;
  prompt: (opts: PromptOpts) => Promise<string | null>;
};

const DialogContext = createContext<DialogApi | null>(null);

export function useDialog(): DialogApi {
  const api = useContext(DialogContext);
  if (!api) {
    throw new Error('useDialog outside DialogProvider');
  }
  return api;
}

type DialogState =
  | { kind: 'confirm'; opts: ConfirmOpts; resolve: (v: boolean) => void }
  | { kind: 'prompt'; opts: PromptOpts; resolve: (v: string | null) => void };

export function DialogProvider(props: { children: ReactNode }) {
  const [state, setState] = useState<DialogState | null>(null);
  const api = useMemo<DialogApi>(
    () => ({
      confirm: (opts) => new Promise<boolean>((resolve) => setState({ kind: 'confirm', opts, resolve })),
      prompt: (opts) => new Promise<string | null>((resolve) => setState({ kind: 'prompt', opts, resolve })),
    }),
    [],
  );
  return (
    <DialogContext.Provider value={api}>
      {props.children}
      {state && <DialogHost state={state} onClose={() => setState(null)} />}
    </DialogContext.Provider>
  );
}

function DialogHost(props: { state: DialogState; onClose: () => void }) {
  const { state, onClose } = props;
  const isPrompt = state.kind === 'prompt';
  const [value, setValue] = useState(isPrompt ? ((state.opts as PromptOpts).initial ?? '') : '');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isPrompt) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  }, [isPrompt]);

  const cancel = () => {
    if (state.kind === 'prompt') {
      state.resolve(null);
    } else {
      state.resolve(false);
    }
    onClose();
  };
  const accept = () => {
    if (state.kind === 'prompt') {
      state.resolve(value);
    } else {
      state.resolve(true);
    }
    onClose();
  };

  useEscapeKey(cancel);

  const danger = !isPrompt && (state.opts as ConfirmOpts).danger;
  const confirmLabel = state.opts.confirmLabel ?? (isPrompt ? 'OK' : 'Confirm');

  return createPortal(
    <div className="modal-backdrop" onClick={cancel}>
      <form
        className="modal dialog"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault();
          accept();
        }}
      >
        {state.opts.title && <h2>{state.opts.title}</h2>}
        <p className="dialog-message">{state.opts.message}</p>
        {state.kind === 'prompt' && (
          <label className="dialog-field">
            {state.opts.label && <span>{state.opts.label}</span>}
            <input
              ref={inputRef}
              type={state.opts.type ?? 'text'}
              value={value}
              placeholder={state.opts.placeholder}
              onChange={(e) => setValue(e.target.value)}
            />
          </label>
        )}
        <div className="modal-actions">
          <button type="button" className="btn" onClick={cancel}>
            Cancel
          </button>
          <button type="submit" className={'btn ' + (danger ? 'danger' : 'primary')}>
            {confirmLabel}
          </button>
        </div>
      </form>
    </div>,
    document.body,
  );
}
