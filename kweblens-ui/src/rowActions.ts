import { api } from './api';
import type { DialogApi } from './dialog';
import type { LogScope } from './dock';
import { containerNames, objectPorts } from './kube';
import type { AccessVerb } from './permissions';
import { controlAccess } from './permissions';
import type { DockKind, KindAccess, KubeObject } from './types';

// The declarative per-row action registry — shared by the RowMenu kebab (renders it) and the
// shell (dispatches it). Adding a menu item = adding one entry here; nothing else changes.
// Framework-agnostic: the `run` handlers take a RowActionCtx of capabilities the shell wires.

const SCALABLE = ['Deployment', 'StatefulSet', 'ReplicaSet'];
const RESTARTABLE = ['Deployment', 'StatefulSet', 'DaemonSet'];
const ROLLBACKABLE = ['Deployment', 'StatefulSet'];
// Kinds whose pods can be resolved from a spec.selector, so "Logs" can mean every replica.
const POD_OWNERS = ['Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet', 'Job'];

/**
 * The sentinel the container submenu uses for "every container", vs. one container's name.
 *
 * Module-private since the menu is projected here too (`rowActionOptions`): the surfaces that
 * render the menu pass the key back through `parseRowActionKey` and never spell it themselves.
 */
const ALL_CONTAINERS = '*';

export type RowAction =
  | 'logs'
  | 'logsAll'
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
  openLogs: (
    ns: string,
    pod: string,
    containers: string[],
    scope: LogScope,
    workload?: { resourceId: string; name: string },
  ) => void;
  setForward: (f: { kind: string; namespace: string; name: string; ports: number[] }) => void;
  setDetail: (d: { resourceId: string; obj: KubeObject; edit?: boolean }) => void;
  /**
   * Report that this action did not complete.
   *
   * <p>Takes the THROWN VALUE, not a rendered string: every row action is a write, and what a
   * failed write leaves behind depends on whether the cluster gave a verdict or never answered
   * at all (`paneFailure.actionConsequence`). Rendering it here would throw that away, and the
   * shell would be left offering a Retry it cannot reason about.
   */
  reportFailure: (e: unknown) => void;
  removeObject: (obj: KubeObject) => void;
  // Optional-confirm wrapper: run fn and surface errors; confirm first when confirmMsg is set.
  confirmRun: (fn: () => Promise<unknown>, confirmMsg?: string) => void;
}

// One declarative row action. `applies` decides which kinds show it; `run` does the work.
export interface RowActionDef {
  id: RowAction;
  label: string;
  danger?: boolean;
  /**
   * The verb on THIS kind that the deployment's service account needs for the action to work,
   * or absent when nothing the access report covers can answer it.
   *
   * Absent is the honest answer for more actions than it might look. `drain` deletes Pods and
   * `trigger` creates a Job — a different resource each time, so a verdict about this kind
   * says nothing about them. `edit` only opens the editor; the write is the Apply inside it,
   * and that is where the verb is checked. Reads and exec (logs, shell, attach, forward) are
   * either open or gated on a subresource that is not reviewed here.
   *
   * An absent verb resolves to `unknown`, which renders as ENABLED — the same fail-open rule
   * that governs a review nobody could answer. Guessing a verb here would be worse than
   * leaving it out: a wrong guess disables a control that works.
   */
  verb?: AccessVerb;
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
        (e) => c.reportFailure(e),
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

/**
 * Pod "Logs". Picking a specific container from the submenu follows just that one; choosing
 * "All containers" (or a single-container pod, where there is no submenu) follows every
 * container interleaved.
 */
function podLogsAction(c: RowActionCtx) {
  if (c.container && c.container !== ALL_CONTAINERS) {
    c.openLogs(c.ns, c.name, [c.container], 'container');
    return;
  }
  c.openLogs(c.ns, c.name, c.containers, c.containers.length > 1 ? 'pod' : 'container');
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
    run: podLogsAction,
  },
  {
    // A distinct id, not a second 'logs' entry: the shell dispatches with
    // ROW_ACTIONS.find(a => a.id === action), so a duplicate id would always resolve to the
    // pod action and the workload one would be unreachable.
    id: 'logsAll',
    label: 'Logs (all pods)',
    requiresAuth: false,
    section: 'main',
    applies: (c) => POD_OWNERS.includes(c.kind),
    // The whole point of the ticket: one view over every replica's output, so a rollout or
    // an intermittent failure can be read across pods instead of one pod at a time.
    run: (c) => c.openLogs(c.ns, c.name, [], 'workload', { resourceId: c.resourceId, name: c.name }),
  },
  {
    id: 'forward',
    label: 'Port Forward',
    section: 'main',
    applies: (c) => c.kind === 'Service',
    run: (c) => c.setForward({ kind: c.kind, namespace: c.ns, name: c.name, ports: objectPorts(c.kind, c.obj) }),
  },
  {
    id: 'scale',
    label: 'Scale…',
    verb: 'patch',
    section: 'main',
    applies: (c) => SCALABLE.includes(c.kind),
    run: scaleAction,
  },
  {
    id: 'restart',
    label: 'Restart',
    verb: 'patch',
    section: 'main',
    applies: (c) => RESTARTABLE.includes(c.kind),
    run: (c) => c.confirmRun(() => api.restart(c.cluster, c.resourceId, c.ns, c.name), `Rolling-restart ${c.name}?`),
  },
  {
    id: 'rollback',
    label: 'Rollback',
    verb: 'patch',
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
    verb: 'patch',
    section: 'main',
    applies: (c) => (c.kind === 'CronJob' || c.kind === 'Job') && !c.suspended,
    run: (c) =>
      c.confirmRun(() => api.suspend(c.cluster, c.resourceId, c.ns, c.name, true), `Suspend ${c.kind} ${c.name}?`),
  },
  {
    id: 'resume',
    label: 'Resume',
    verb: 'patch',
    section: 'main',
    applies: (c) => (c.kind === 'CronJob' || c.kind === 'Job') && c.suspended,
    run: (c) =>
      c.confirmRun(() => api.suspend(c.cluster, c.resourceId, c.ns, c.name, false), `Resume ${c.kind} ${c.name}?`),
  },
  {
    id: 'cordon',
    label: 'Cordon',
    verb: 'patch',
    section: 'main',
    applies: (c) => c.kind === 'Node',
    run: (c) => c.confirmRun(() => api.cordon(c.cluster, c.name), `Cordon ${c.name}?`),
  },
  {
    id: 'uncordon',
    label: 'Uncordon',
    verb: 'patch',
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
    verb: 'delete',
    section: 'lifecycle',
    applies: (c) => c.kind !== 'Node',
    run: (c) => confirmDelete(c, false),
  },
  {
    id: 'forceDelete',
    label: 'Force Delete',
    danger: true,
    verb: 'delete',
    section: 'lifecycle',
    applies: (c) => c.kind !== 'Node',
    run: (c) => confirmDelete(c, true),
  },
];

/**
 * One entry in an actions menu — plain data shaped to what Naive's NDropdown consumes, but
 * deliberately not typed as `DropdownOption`: this module stays framework-agnostic so the
 * projection can be tested without a DOM.
 */
export interface RowActionOption {
  label?: string;
  key: string;
  type?: 'divider';
  props?: { class: string };
  children?: { label: string; key: string }[];
  /**
   * Set only when the cluster has said, in so many words, that the deployment's service
   * account cannot do this. Never set by a review that failed — see `permissions.ts`.
   */
  disabled?: boolean;
  /** Why it is disabled, in a sentence naming the service account. Rendered, not hovered. */
  deniedReason?: string;
}

/** Separator between the kind-specific actions and Edit/Delete. */
const DIVIDER: RowActionOption = { type: 'divider', key: 'divider' };

/**
 * Every action that applies to `obj`, as menu options.
 *
 * Lives here rather than in the table because the detail drawer offers the same menu (#233):
 * two copies of "which actions apply, and which of them need a per-container submenu" would
 * be two chances for the drawer and the list to disagree about what an object can do.
 *
 * @param access what the deployment's service account may do with this kind here, when it is
 * known. It is optional and nullable on purpose: not loaded yet, failed, or never asked all
 * mean the same thing — every action stays ENABLED. An action is disabled only where the
 * cluster said no, and it then carries the sentence that says so.
 */
export function rowActionOptions(obj: KubeObject, access?: KindAccess | null): RowActionOption[] {
  const ctx = { kind: obj.kind ?? '', suspended: Boolean((obj.spec as Record<string, unknown>)?.suspend) };
  const containers = containerNames(obj);
  const applicable = ROW_ACTIONS.filter((a) => a.applies(ctx));
  const toOption = (a: RowActionDef): RowActionOption => {
    const gate = controlAccess(access, a.verb);
    if (!a.containerScoped || containers.length <= 1) {
      const option: RowActionOption = { label: a.label, key: a.id, props: { class: a.danger ? 'menu-danger' : '' } };
      if (gate.disabled) {
        option.disabled = true;
        option.deniedReason = gate.reason ?? undefined;
      }
      return option;
    }
    // Logs can span containers, so it leads with "All containers"; a shell or attach can
    // only ever target one, so those stay a plain container list.
    const children = containers.map((c) => ({ label: c, key: `${a.id}::${c}` }));
    if (a.id === 'logs') {
      children.unshift({ label: 'All containers', key: `${a.id}::${ALL_CONTAINERS}` });
    }
    return { label: a.label, key: a.id, children };
  };
  const main = applicable.filter((a) => a.section === 'main').map(toOption);
  const life = applicable.filter((a) => a.section === 'lifecycle').map(toOption);
  return main.length > 0 && life.length > 0 ? [...main, DIVIDER, ...life] : [...main, ...life];
}

/** Read a menu key back as the action it names, plus the container a submenu scoped it to. */
export function parseRowActionKey(key: string): { action: RowAction; container?: string } {
  const [action, container] = key.split('::');
  return { action: action as RowAction, container };
}
