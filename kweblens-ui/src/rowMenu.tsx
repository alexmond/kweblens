import { useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { api } from './api';
import type { DialogApi } from './dialog';
import { useMenuDismiss } from './hooks';
import { objectPorts } from './kube';
import type { DockKind, KubeObject } from './types';

const SCALABLE = ['Deployment', 'StatefulSet', 'ReplicaSet'];
const RESTARTABLE = ['Deployment', 'StatefulSet', 'DaemonSet'];
const ROLLBACKABLE = ['Deployment', 'StatefulSet'];

export type RowAction =
  | 'logs'
  | 'terminal'
  | 'attach'
  | 'forward'
  | 'scale'
  | 'restart'
  | 'rollback'
  | 'suspend'
  | 'resume'
  | 'trigger'
  | 'cordon'
  | 'uncordon'
  | 'drain'
  | 'edit'
  | 'delete'
  | 'forceDelete';

// Capabilities a row action's handler may use; the table builds one per fired action.
type RowActionCtx = {
  cluster: string;
  resourceId: string;
  obj: KubeObject;
  ns: string;
  name: string;
  kind: string;
  containers: string[];
  container?: string;
  dialog: DialogApi;
  openDock: (kind: DockKind, ns: string, pod: string, containers: string[], attach?: boolean) => void;
  setForward: (f: { kind: string; namespace: string; name: string; ports: number[] }) => void;
  setDetail: (d: { resourceId: string; obj: KubeObject; edit?: boolean }) => void;
  setError: (msg: string) => void;
  removeObject: (obj: KubeObject) => void;
  // Optional-confirm wrapper: run fn and surface errors; confirm first when confirmMsg is set.
  confirmRun: (fn: () => Promise<unknown>, confirmMsg?: string) => void;
};

// One declarative row action. Adding a menu item = adding one entry to ROW_ACTIONS below;
// nothing else changes. `applies` decides which kinds show it; `run` does the work.
type RowActionDef = {
  id: RowAction;
  label: string;
  danger?: boolean;
  // Renders as a per-container submenu on multi-container pods (Attach/Shell/Logs).
  containerScoped?: boolean;
  // Only Logs is readable without signing in; everything else prompts for auth first.
  requiresAuth?: boolean;
  // 'main' = kind-specific actions; 'lifecycle' = Edit/Delete (separated by a divider).
  section: 'main' | 'lifecycle';
  applies: (ctx: { kind: string; suspended: boolean }) => boolean;
  run: (ctx: RowActionCtx) => void;
};

// Containers to target: the one chosen from a submenu, else all of the pod's containers.
function scopedContainers(c: RowActionCtx): string[] {
  return c.container ? [c.container] : c.containers;
}

function confirmDelete(c: RowActionCtx, force: boolean) {
  c.dialog
    .confirm({
      title: force ? 'Force delete' : 'Delete',
      message: `${force ? 'Force delete' : 'Delete'} ${c.kind} ${c.name}? This cannot be undone.`,
      confirmLabel: force ? 'Force delete' : 'Delete',
      danger: true,
    })
    .then((ok) => {
      if (!ok) {
        return;
      }
      api.del(c.cluster, c.resourceId, c.ns, c.name, force).then(
        () => c.removeObject(c.obj),
        (e) => c.setError(String(e)),
      );
    });
}

function scaleAction(c: RowActionCtx) {
  const current = Number((c.obj.spec as Record<string, unknown>)?.replicas ?? 1);
  c.dialog
    .prompt({
      title: 'Scale',
      message: `Scale ${c.kind} ${c.name} to how many replicas?`,
      label: 'Replicas',
      initial: String(current),
      type: 'number',
      confirmLabel: 'Scale',
    })
    .then((input) => {
      if (input === null) {
        return;
      }
      const replicas = Math.max(0, Number.parseInt(input, 10));
      if (Number.isNaN(replicas)) {
        return;
      }
      c.confirmRun(() => api.scale(c.cluster, c.resourceId, c.ns, c.name, replicas));
    });
}

// The single source of truth for per-row actions — the kebab menu renders it and the
// dashboard dispatches it. Both derive entirely from this list.
export const ROW_ACTIONS: RowActionDef[] = [
  {
    id: 'attach',
    label: 'Attach to Pod',
    containerScoped: true,
    section: 'main',
    applies: (c) => c.kind === 'Pod',
    run: (c) => c.openDock('terminal', c.ns, c.name, scopedContainers(c), true),
  },
  {
    id: 'terminal',
    label: 'Shell',
    containerScoped: true,
    section: 'main',
    applies: (c) => c.kind === 'Pod',
    run: (c) => c.openDock('terminal', c.ns, c.name, scopedContainers(c)),
  },
  {
    id: 'logs',
    label: 'Logs',
    containerScoped: true,
    requiresAuth: false,
    section: 'main',
    applies: (c) => c.kind === 'Pod',
    run: (c) => c.openDock('logs', c.ns, c.name, scopedContainers(c)),
  },
  {
    id: 'forward',
    label: 'Port Forward',
    section: 'main',
    applies: (c) => c.kind === 'Service',
    run: (c) => c.setForward({ kind: c.kind, namespace: c.ns, name: c.name, ports: objectPorts(c.kind, c.obj) }),
  },
  { id: 'scale', label: 'Scale…', section: 'main', applies: (c) => SCALABLE.includes(c.kind), run: scaleAction },
  {
    id: 'restart',
    label: 'Restart',
    section: 'main',
    applies: (c) => RESTARTABLE.includes(c.kind),
    run: (c) => c.confirmRun(() => api.restart(c.cluster, c.resourceId, c.ns, c.name), `Rolling-restart ${c.name}?`),
  },
  {
    id: 'rollback',
    label: 'Rollback',
    section: 'main',
    applies: (c) => ROLLBACKABLE.includes(c.kind),
    run: (c) =>
      c.confirmRun(
        () => api.rollback(c.cluster, c.resourceId, c.ns, c.name),
        `Roll ${c.kind} ${c.name} back to its previous revision?`,
      ),
  },
  {
    id: 'trigger',
    label: 'Trigger',
    section: 'main',
    applies: (c) => c.kind === 'CronJob',
    run: (c) =>
      c.confirmRun(() => api.trigger(c.cluster, c.resourceId, c.ns, c.name), `Trigger a manual run of ${c.name}?`),
  },
  {
    id: 'suspend',
    label: 'Suspend',
    section: 'main',
    applies: (c) => (c.kind === 'CronJob' || c.kind === 'Job') && !c.suspended,
    run: (c) =>
      c.confirmRun(() => api.suspend(c.cluster, c.resourceId, c.ns, c.name, true), `Suspend ${c.kind} ${c.name}?`),
  },
  {
    id: 'resume',
    label: 'Resume',
    section: 'main',
    applies: (c) => (c.kind === 'CronJob' || c.kind === 'Job') && c.suspended,
    run: (c) =>
      c.confirmRun(() => api.suspend(c.cluster, c.resourceId, c.ns, c.name, false), `Resume ${c.kind} ${c.name}?`),
  },
  {
    id: 'cordon',
    label: 'Cordon',
    section: 'main',
    applies: (c) => c.kind === 'Node',
    run: (c) => c.confirmRun(() => api.cordon(c.cluster, c.name), `Cordon ${c.name}?`),
  },
  {
    id: 'uncordon',
    label: 'Uncordon',
    section: 'main',
    applies: (c) => c.kind === 'Node',
    run: (c) => c.confirmRun(() => api.uncordon(c.cluster, c.name)),
  },
  {
    id: 'drain',
    label: 'Drain',
    danger: true,
    section: 'main',
    applies: (c) => c.kind === 'Node',
    run: (c) =>
      c.confirmRun(() => api.drain(c.cluster, c.name), `Drain ${c.name}? This cordons it and evicts its pods.`),
  },
  {
    id: 'edit',
    label: 'Edit',
    section: 'lifecycle',
    applies: () => true,
    run: (c) => c.setDetail({ resourceId: c.resourceId, obj: c.obj, edit: true }),
  },
  {
    id: 'delete',
    label: 'Delete',
    danger: true,
    section: 'lifecycle',
    applies: (c) => c.kind !== 'Node',
    run: (c) => confirmDelete(c, false),
  },
  {
    id: 'forceDelete',
    label: 'Force Delete',
    danger: true,
    section: 'lifecycle',
    applies: (c) => c.kind !== 'Node',
    run: (c) => confirmDelete(c, true),
  },
];

// Per-row kebab (⋮) actions menu (Freelens-style), kind-aware: it renders whichever
// entries of ROW_ACTIONS apply to this kind — no per-kind markup lives here.
export function RowMenu(props: {
  kind: string;
  suspended: boolean;
  containers: string[];
  onAction: (a: RowAction, container?: string) => void;
}) {
  const { kind, suspended, containers, onAction } = props;
  const [open, setOpen] = useState(false);
  // Fixed-position anchor so the menu is rendered in a portal on document.body and never
  // clipped by the table's overflow container.
  const [anchor, setAnchor] = useState<{ left: number; top: number; up: boolean } | null>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  useMenuDismiss(open, setOpen, btnRef, menuRef);

  const toggle = () => {
    if (open) {
      setOpen(false);
      return;
    }
    const r = btnRef.current?.getBoundingClientRect();
    if (r) {
      const estHeight = 280;
      const up = r.bottom + estHeight > window.innerHeight;
      setAnchor({ left: r.right, top: up ? r.top : r.bottom, up });
    }
    setOpen(true);
  };
  const run = (action: RowAction, container?: string) => {
    setOpen(false);
    onAction(action, container);
  };
  const applicable = ROW_ACTIONS.filter((a) => a.applies({ kind, suspended }));
  const main = applicable.filter((a) => a.section === 'main');
  const lifecycle = applicable.filter((a) => a.section === 'lifecycle');
  const renderItem = (a: RowActionDef) => {
    // Multi-container pod: a hover submenu of containers; otherwise a plain item.
    if (a.containerScoped && containers.length > 1) {
      return (
        <div key={a.id} className="menu-item has-sub">
          <span>{a.label}</span>
          <span className="sub-arrow">›</span>
          <div className="submenu">
            {containers.map((c) => (
              <button
                key={c}
                className="menu-item"
                onClick={(e) => {
                  e.stopPropagation();
                  run(a.id, c);
                }}
              >
                {c}
              </button>
            ))}
          </div>
        </div>
      );
    }
    return (
      <button
        key={a.id}
        className={'menu-item' + (a.danger ? ' danger' : '')}
        onClick={(e) => {
          e.stopPropagation();
          run(a.id);
        }}
      >
        {a.label}
      </button>
    );
  };
  return (
    <div className="rowmenu" onClick={(e) => e.stopPropagation()}>
      <button
        ref={btnRef}
        className="kebab"
        title="Actions"
        onClick={(e) => {
          e.stopPropagation();
          toggle();
        }}
      >
        ⋮
      </button>
      {open &&
        anchor &&
        createPortal(
          <div
            ref={menuRef}
            className="menu menu-portal"
            style={{
              position: 'fixed',
              left: anchor.left,
              top: anchor.top,
              transform: anchor.up ? 'translate(-100%, -100%)' : 'translate(-100%, 0)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {main.map(renderItem)}
            {main.length > 0 && lifecycle.length > 0 && <div className="menu-sep" />}
            {lifecycle.map(renderItem)}
          </div>,
          document.body,
        )}
    </div>
  );
}

export type KebabItem = { label: string; onClick: () => void; danger?: boolean; disabled?: boolean };

// A generic kebab (⋮) actions menu with the same portal/anchor behaviour as RowMenu, for
// non-resource tables (Helm charts/releases/repos) so their row actions match the rest.
export function KebabMenu(props: { items: KebabItem[] }) {
  const { items } = props;
  const [open, setOpen] = useState(false);
  const [anchor, setAnchor] = useState<{ left: number; top: number; up: boolean } | null>(null);
  const btnRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  useMenuDismiss(open, setOpen, btnRef, menuRef);

  const toggle = () => {
    if (open) {
      setOpen(false);
      return;
    }
    const r = btnRef.current?.getBoundingClientRect();
    if (r) {
      const estHeight = 20 + items.length * 30;
      const up = r.bottom + estHeight > window.innerHeight;
      setAnchor({ left: r.right, top: up ? r.top : r.bottom, up });
    }
    setOpen(true);
  };

  return (
    <div className="rowmenu" onClick={(e) => e.stopPropagation()}>
      <button
        ref={btnRef}
        className="kebab"
        title="Actions"
        onClick={(e) => {
          e.stopPropagation();
          toggle();
        }}
      >
        ⋮
      </button>
      {open &&
        anchor &&
        createPortal(
          <div
            ref={menuRef}
            className="menu menu-portal"
            style={{
              position: 'fixed',
              left: anchor.left,
              top: anchor.top,
              transform: anchor.up ? 'translate(-100%, -100%)' : 'translate(-100%, 0)',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {items.map((it) => (
              <button
                key={it.label}
                className={'menu-item' + (it.danger ? ' danger' : '')}
                disabled={it.disabled}
                onClick={(e) => {
                  e.stopPropagation();
                  setOpen(false);
                  it.onClick();
                }}
              >
                {it.label}
              </button>
            ))}
          </div>,
          document.body,
        )}
    </div>
  );
}
