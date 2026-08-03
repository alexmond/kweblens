import type { KubeObject } from './types';

// Small defensive accessors + pure helpers for the loosely-typed cluster objects the API
// returns. Shared across the SPA (table columns, dashboard, feature views) so the same
// coalescing / parsing logic isn't re-defined per file. No JSX / React here.

/** {@code o.spec} as a record, never null. */
export const objSpec = (o: KubeObject): Record<string, unknown> => (o.spec as Record<string, unknown>) ?? {};

/** {@code o.status} as a record, never null. */
export const objStatus = (o: KubeObject): Record<string, unknown> => (o.status as Record<string, unknown>) ?? {};

/** A number, or 0 when the value isn't numeric. */
export const toNum = (v: unknown): number => (typeof v === 'number' ? v : 0);

export const objName = (o: KubeObject): string => o.metadata?.name ?? '';
export const objNs = (o: KubeObject): string | undefined => o.metadata?.namespace;
export const objKey = (o: KubeObject): string => (objNs(o) ?? '') + '/' + objName(o);

/**
 * Two-letter avatar for a single id, split on word boundaries.
 *
 * <p>`prod-eu` is `PE` and `prod-us` is `PU`, where the old `id.slice(0, 2)` made both `PR`.
 * Word-splitting alone fixes the common case — a naming convention with a shared prefix —
 * but it cannot guarantee uniqueness, because it only ever sees one id. Use
 * {@link tileLabels} anywhere several ids are shown together.
 */
export function initials(id: string): string {
  const words = id.split(/[-_.\s/]+/).filter(Boolean);
  if (words.length === 0) {
    return id.slice(0, 2).toUpperCase();
  }
  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase();
  }
  return (words[0][0] + words[1][0]).toUpperCase();
}

/**
 * Distinct two-letter labels for a set of ids — no two ever the same (#252).
 *
 * <p>The rail is the cluster switcher, and every write in this product is scoped by what it
 * selects. A label that looks precise and is not invites acting on the wrong cluster and
 * gives no signal that you have: on a real setup five of seven tiles rendered the same two
 * letters. {@link initials} alone cannot fix that, because uniqueness is a property of the
 * SET, not of any one id.
 *
 * <p>Collisions are resolved by keeping the first letter and taking the second from the
 * first character of the id that has not already been used in that position. Order matters
 * and that is deliberate: ids are processed as given and only a *later* colliding id is
 * changed, so appending a cluster never relabels the ones already on screen — a rail whose
 * letters shuffle when you add a cluster would be worse than one with duplicates.
 */
export function tileLabels(ids: string[]): Map<string, string> {
  const out = new Map<string, string>();
  const taken = new Set<string>();
  for (const id of ids) {
    let label = initials(id);
    if (taken.has(label)) {
      label = disambiguate(id, taken);
    }
    taken.add(label);
    out.set(id, label);
  }
  return out;
}

const ALPHANUM = /[a-z0-9]/i;

/** A free label for `id` that keeps its first letter where it can. */
function disambiguate(id: string, taken: Set<string>): string {
  const head = (initials(id)[0] ?? '?').toUpperCase();
  // Prefer a second letter drawn from the id itself, so the label still says something
  // about which cluster it is rather than becoming an opaque counter.
  for (const ch of id.slice(1)) {
    if (!ALPHANUM.test(ch)) {
      continue;
    }
    const candidate = head + ch.toUpperCase();
    if (!taken.has(candidate)) {
      return candidate;
    }
  }
  // Ids that differ only outside [a-z0-9] (or are equal) fall back to a digit. Uniqueness
  // is the property we refuse to give up; legibility is what we spend to keep it.
  for (let n = 2; n <= 9; n += 1) {
    const candidate = head + String(n);
    if (!taken.has(candidate)) {
      return candidate;
    }
  }
  return head + String(taken.size % 10);
}

/** Container names on a pod/workload spec (empty names filtered out). */
export function containerNames(o: KubeObject): string[] {
  return ((objSpec(o).containers as { name?: string }[]) ?? []).map((c) => c.name ?? '').filter(Boolean);
}

const isNum = (v: unknown): v is number => typeof v === 'number';

function servicePortNumbers(spec: Record<string, unknown>): number[] {
  return ((spec.ports as { port?: number }[]) ?? []).map((p) => p.port).filter(isNum);
}

function containerPortNumbers(spec: Record<string, unknown>): number[] {
  return ((spec.containers as { ports?: { containerPort?: number }[] }[]) ?? [])
    .flatMap((c) => c.ports ?? [])
    .map((p) => p.containerPort)
    .filter(isNum);
}

/** Ports worth suggesting when starting a forward: a Service's ports, else containerPorts. */
export function objectPorts(kind: string, o: KubeObject): number[] {
  const spec = objSpec(o);
  const ports = kind === 'Service' ? servicePortNumbers(spec) : containerPortNumbers(spec);
  return [...new Set(ports)];
}

/** Parse a Kubernetes CPU quantity (e.g. "245m", "2", "500n") to cores. */
export function parseCpuCores(q: string | undefined): number {
  if (!q) {
    return 0;
  }
  const n = Number.parseFloat(q);
  if (Number.isNaN(n)) {
    return 0;
  }
  if (q.endsWith('m')) {
    return n / 1000;
  }
  if (q.endsWith('u')) {
    return n / 1e6;
  }
  if (q.endsWith('n')) {
    return n / 1e9;
  }
  return n;
}

const MEM_UNITS: Record<string, number> = {
  '': 1,
  Ki: 1024,
  Mi: 1024 ** 2,
  Gi: 1024 ** 3,
  Ti: 1024 ** 4,
  Pi: 1024 ** 5,
  k: 1e3,
  K: 1e3,
  M: 1e6,
  G: 1e9,
  T: 1e12,
};

/** Parse a Kubernetes memory quantity (e.g. "4637Mi", "4046264Ki", "4Gi") to bytes. */
export function parseMemBytes(q: string | undefined): number {
  if (!q) {
    return 0;
  }
  const m = /^([0-9.]+)\s*([A-Za-z]*)$/.exec(q.trim());
  if (!m) {
    return 0;
  }
  return Number.parseFloat(m[1]) * (MEM_UNITS[m[2]] ?? 1);
}

export const gib = (bytes: number): string => (bytes / 1024 ** 3).toFixed(1) + 'Gi';

/** Parse a compact age string (e.g. "45s", "5m", "2h", "18d") to seconds, for sorting. */
export function ageToSeconds(age: string): number {
  const m = /^(\d+)([smhd])$/.exec(age.trim());
  if (!m) {
    return 0;
  }
  const mult: Record<string, number> = { s: 1, m: 60, h: 3600, d: 86400 };
  return Number(m[1]) * (mult[m[2]] ?? 1);
}

/** The top-level maps a list payload ships keys-only (see ListProjection.java). */
const PROJECTED_FIELDS = ['data', 'stringData', 'binaryData'] as const;

/**
 * Whether this row came out of a LIST payload with its values stripped, so the drawer has to
 * fetch the whole object before it can render them (GH#276).
 *
 * <p>Deliberately derived from the row itself rather than from a list of kinds. The server
 * marks a stripped value by setting it to `null` — which a real value never is, since these
 * maps are `map[string]string` — so this predicate stays true to whatever the server actually
 * decided to strip. A hard-coded `kind === 'Secret' || kind === 'ConfigMap'` here would be a
 * second copy of that rule, and the copy that goes stale silently: the drawer would show an
 * empty Data section for the next kind added server-side and nothing would say why.
 *
 * <p>An object with no such map at all — every Pod, Deployment, Service — answers false, which
 * is the point: the common case must not pay for a re-fetch it has no use for.
 */
export function needsFullObject(o: KubeObject): boolean {
  return PROJECTED_FIELDS.some((f) => {
    const v = (o as Record<string, unknown>)[f];
    return !!v && typeof v === 'object' && Object.values(v as Record<string, unknown>).some((x) => x === null);
  });
}

/**
 * Remove the noisy metadata.managedFields block from a manifest (text-level, so it works
 * without a YAML parser). Drops the `managedFields:` key and its whole nested/sequence body.
 *
 * <p>Still needed after GH#276 stripped `managedFields` from list payloads: this operates on
 * the YAML TEXT that the YAML tab and the editor's Review Changes diff fetch from their own
 * endpoints (`/yaml`, and the server's dry-run answer), neither of which is a list payload.
 */
export function stripManagedFields(yaml: string): string {
  const lines = yaml.split('\n');
  const out: string[] = [];
  let blockIndent = -1;
  for (const line of lines) {
    if (blockIndent >= 0) {
      const indent = line.search(/\S/);
      const isSeqItem = indent === blockIndent && line[indent] === '-';
      if (indent === -1 || indent > blockIndent || isSeqItem) {
        continue;
      }
      blockIndent = -1;
    }
    const m = /^(\s*)managedFields:\s*$/.exec(line);
    if (m) {
      blockIndent = m[1].length;
      continue;
    }
    out.push(line);
  }
  return out.join('\n');
}

/**
 * The kind out of an event's `involvedObject` string, which the server formats as `Kind/name`.
 *
 * Returns null when there is no usable kind rather than guessing: an event about an object the
 * nav does not model (or a malformed value) should leave the row inert, not navigate somewhere
 * arbitrary. A name may itself contain slashes, so only the FIRST segment is the kind.
 */
export function eventObjectKind(object: string | null | undefined): string | null {
  const kind = (object ?? '').split('/')[0].trim();
  return kind.length > 0 ? kind : null;
}
