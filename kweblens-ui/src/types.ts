// Mirrors the kweblens JSON API records (kweblens-core / web).

export interface ClusterInfo {
  id: string;
  name: string;
  masterUrl: string;
}

export interface NavItem {
  id: string;
  label: string;
  kind: string;
  namespaced: boolean;
}

export interface NavCategory {
  label: string;
  icon: string;
  items: NavItem[];
}

export interface ResourceRow {
  kind: string;
  namespace: string | null;
  name: string;
  status: string | null;
  age: string;
}

// A raw Kubernetes object (as returned by the cluster), used to render kind-specific columns.
export interface KubeObject {
  apiVersion?: string;
  kind?: string;
  metadata?: {
    name?: string;
    namespace?: string;
    creationTimestamp?: string;
    labels?: Record<string, string>;
    annotations?: Record<string, string>;
    ownerReferences?: { kind: string; name: string }[];
  };
  spec?: Record<string, unknown>;
  status?: Record<string, unknown>;
  [k: string]: unknown;
}

export interface EventSummary {
  type: string;
  reason: string;
  object: string;
  namespace: string | null;
  message: string;
  age: string;
}

// A CRD-declared additional printer column.
export interface PrinterColumn {
  name: string;
  jsonPath: string;
  type: string;
}

export interface HelmRelease {
  name: string;
  namespace: string;
  revision: number;
  status: string;
  chart: string;
  appVersion: string;
  updated: string | null;
}
