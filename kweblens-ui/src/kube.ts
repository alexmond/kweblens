import type { KubeObject } from './types';

// Small defensive accessors for the loosely-typed cluster objects the API returns.
// Shared by the table columns (columns.ts) and the dashboard (App.tsx) so the same
// `?? {}` / numeric-coalesce logic isn't re-defined per file.

/** {@code o.spec} as a record, never null. */
export const objSpec = (o: KubeObject): Record<string, unknown> => (o.spec as Record<string, unknown>) ?? {};

/** {@code o.status} as a record, never null. */
export const objStatus = (o: KubeObject): Record<string, unknown> => (o.status as Record<string, unknown>) ?? {};

/** A number, or 0 when the value isn't numeric. */
export const toNum = (v: unknown): number => (typeof v === 'number' ? v : 0);
