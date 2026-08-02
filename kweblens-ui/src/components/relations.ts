// Projects the detail endpoint's relations (GH#136) into renderable sections.
//
// Kept out of the .vue for the same reason as overview.ts: the interesting part is the
// projection, and it should be testable without a DOM.
import { objName, objNs, objStatus } from '../kube';
import type { KubeObject, Relation } from '../types';

interface RelationCell {
  text: string;
  mono?: boolean;
}

export interface RelationSection {
  /**
   * The #231 rank, and it is not a field: a relation table (which pods back this Service,
   * what mounts this Secret) is the object's own substance, so every relation section is
   * PRIMARY. Carrying no `rank` is how it says so — `rankOf` in `overview.ts` reads an
   * unmarked entry as primary, which lets a consumer rank both kinds of section through the
   * same call instead of special-casing this one.
   */
  rank?: never;
  /** Section title shown in the accordion. */
  title: string;
  /** Count badge — the number of related objects, or undefined when unresolved. */
  count?: number;
  headers: string[];
  rows: RelationCell[][];
  /**
   * Set when the relation could not be resolved. Rendered INSTEAD of rows, never alongside an
   * empty table, so a failure can't be mistaken for "there are none".
   */
  message?: string;
  /** Distinguishes an expected permissions refusal from a malfunction, for styling. */
  notPermitted?: boolean;
  /** The server capped the list; say so rather than implying it is complete. */
  truncated?: boolean;
}

const plain = (text: string): RelationCell => ({ text });
const mono = (text: string): RelationCell => ({ text, mono: true });
const str = (v: unknown): string => (v === undefined || v === null ? '' : String(v));

/**
 * Titles for the relations we know about. A key with no entry is humanised rather than shown
 * raw, so a new server-side relation reads as "Owned By" instead of "ownedBy" on day one.
 */
const TITLES: Record<string, string> = {
  endpoints: 'Endpoints',
  selectedPods: 'Selected Pods',
  mountedBy: 'Mounted By',
};

/** `ownedBy` -> `Owned By`. Only used for keys TITLES does not cover. */
function humanise(key: string): string {
  const spaced = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2');
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/**
 * Endpoints, split into ready and not-ready addresses.
 *
 * This split is the whole diagnostic value: "no pods match my selector" and "pods match but
 * none are ready" look identical from the Service object, and they have completely different
 * fixes (a label typo vs a failing readiness probe).
 */
function endpointRows(items: KubeObject[]): { headers: string[]; rows: RelationCell[][] } {
  const rows: RelationCell[][] = [];
  for (const ep of items) {
    const subsets = (ep as unknown as Record<string, unknown>).subsets;
    for (const subset of Array.isArray(subsets) ? (subsets as Record<string, unknown>[]) : []) {
      const ports = (Array.isArray(subset.ports) ? (subset.ports as Record<string, unknown>[]) : [])
        .map((p) => `${str(p.port)}${p.protocol && p.protocol !== 'TCP' ? '/' + str(p.protocol) : ''}`)
        .join(', ');
      const add = (addresses: unknown, ready: boolean) => {
        for (const a of Array.isArray(addresses) ? (addresses as Record<string, unknown>[]) : []) {
          const target = a.targetRef as Record<string, unknown> | undefined;
          rows.push([
            mono(str(a.ip)),
            plain(ready ? 'Ready' : 'Not ready'),
            mono(ports || '—'),
            plain(target ? str(target.name) : '—'),
          ]);
        }
      };
      add(subset.addresses, true);
      add(subset.notReadyAddresses, false);
    }
  }
  return { headers: ['Address', 'State', 'Ports', 'Target'], rows };
}

function podRows(items: KubeObject[]): { headers: string[]; rows: RelationCell[][] } {
  const rows = items.map((pod) => [
    plain(objName(pod)),
    plain(str(objStatus(pod).phase) || '—'),
    plain(str((pod.spec as Record<string, unknown> | undefined)?.nodeName) || '—'),
    plain(objNs(pod) ?? '—'),
  ]);
  return { headers: ['Pod', 'Status', 'Node', 'Namespace'], rows };
}

/**
 * Any list of Kubernetes objects, projected on the three fields every object has.
 *
 * <p>This is the fallback for a relation key the UI does not recognise, and it is the reason
 * a new server-side relation cannot render wrong. Previously the choice was
 * `key === 'endpoints' ? endpointRows : podRows`, so ANY new relation was rendered as pods —
 * a ConfigMap list would have appeared under Pod/Status/Node/Namespace headers with three
 * empty columns, silently (#203). Bland and correct beats rich and wrong; a relation earns a
 * richer projection by being added to PROJECTIONS.
 */
function genericRows(items: KubeObject[]): { headers: string[]; rows: RelationCell[][] } {
  const rows = items.map((o) => [plain(objName(o)), plain(str(o.kind) || '—'), plain(objNs(o) ?? '—')]);
  return { headers: ['Name', 'Kind', 'Namespace'], rows };
}

/**
 * How each known relation is projected. Registry rather than a conditional so adding one is a
 * line here, and so an unknown key falls through to {@link genericRows} instead of borrowing
 * whichever projection the condition happened to land on.
 */
const PROJECTIONS: Record<string, (items: KubeObject[]) => { headers: string[]; rows: RelationCell[][] }> = {
  endpoints: endpointRows,
  selectedPods: podRows,
  mountedBy: podRows,
};

/**
 * Turn the endpoint's relations map into sections, in a stable order.
 *
 * A relation carrying an error or `notPermitted` becomes a section with a MESSAGE and no
 * table. That is deliberate: rendering an empty table would assert "there are none", which is
 * a claim about the cluster the UI is in no position to make when the fetch failed.
 */
export function relationSections(relations: Record<string, Relation> | undefined): RelationSection[] {
  if (!relations) {
    return [];
  }
  const order = ['endpoints', 'selectedPods', 'mountedBy'];
  const keys = Object.keys(relations).sort((a, b) => {
    const ia = order.indexOf(a);
    const ib = order.indexOf(b);
    return (ia < 0 ? order.length : ia) - (ib < 0 ? order.length : ib);
  });
  return keys.map((key) => {
    const relation = relations[key];
    const title = TITLES[key] ?? humanise(key);
    if (relation.notPermitted) {
      return {
        title,
        headers: [],
        rows: [],
        notPermitted: true,
        // Says who was refused, so it reads as a deployment permission issue rather than
        // the viewer's own access being wrong.
        message: relation.error ?? 'kweblens is not permitted to read this related kind.',
      };
    }
    if (relation.error) {
      return { title, headers: [], rows: [], message: `Could not load: ${relation.error}` };
    }
    const projected = (PROJECTIONS[key] ?? genericRows)(relation.items);
    return {
      title,
      count: projected.rows.length,
      headers: projected.headers,
      rows: projected.rows,
      truncated: relation.truncated,
    };
  });
}
