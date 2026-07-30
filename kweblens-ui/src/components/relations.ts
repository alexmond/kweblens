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

/** Titles are per relation key, so the server can add relations without the UI guessing names. */
const TITLES: Record<string, string> = {
  endpoints: 'Endpoints',
  selectedPods: 'Selected Pods',
  mountedBy: 'Mounted By',
};

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
    const title = TITLES[key] ?? key;
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
    const projected = key === 'endpoints' ? endpointRows(relation.items) : podRows(relation.items);
    return {
      title,
      count: projected.rows.length,
      headers: projected.headers,
      rows: projected.rows,
      truncated: relation.truncated,
    };
  });
}
