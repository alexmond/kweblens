// UI-only types shared across the Helm SFCs (not part of the JSON API DTOs in ../types).

/** A pending Helm mutation the action modal renders (install / upgrade / rollback). */
export type HelmAction =
  | { mode: 'install'; repository: string; chart: string; version: string }
  | {
      mode: 'upgrade';
      namespace: string;
      name: string;
      chart: string;
      chartVersion: string;
      repository?: string;
      version?: string;
    }
  | { mode: 'rollback'; namespace: string; name: string; revision: number };

/** One entry in the generic kebab (⋮) actions menu. */
export interface KebabItem {
  label: string;
  onClick: () => void;
  danger?: boolean;
  disabled?: boolean;
}
