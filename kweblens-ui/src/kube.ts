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

/** Two-letter avatar for a cluster/id tile. */
export function initials(id: string): string {
  return (id.length >= 2 ? id.slice(0, 2) : id).toUpperCase();
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

/**
 * Remove the noisy metadata.managedFields block from a manifest (text-level, so it works
 * without a YAML parser). Drops the `managedFields:` key and its whole nested/sequence body.
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
