import { api } from './api';
import type { DialogApi } from './dialog';
import { objectPorts } from './kube';
import type { DockKind, KubeObject } from './types';

// The declarative per-row action registry — shared by the RowMenu kebab (renders it) and the
// shell (dispatches it). Adding a menu item = adding one entry here; nothing else changes.
// Framework-agnostic: the `run` handlers take a RowActionCtx of capabilities the shell wires.

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

// Capabilities a row action's handler may use; the shell builds one per fired action.
interface RowActionCtx {
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
}

// One declarative row action. `applies` decides which kinds show it; `run` does the work.
export interface RowActionDef {
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
}

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
