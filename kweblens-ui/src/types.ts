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
  // Dashboard hint from the nav catalog: rows can expand to show owned child pods
  // (workload kinds). Absent on synthetic items (Overview/Helm/Port Forwards).
  expandable?: boolean;
}

export interface NavCategory {
  label: string;
  icon: string;
  items: NavItem[];
  // Nested collapsible groups (Custom Resources nests one per CRD API group).
  subgroups?: NavCategory[];
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

// metrics-server usage for a node or pod (cpu in millicores, memory in mebibytes).
export interface UsageSummary {
  name: string;
  namespace: string | null;
  cpu: string;
  memory: string;
}

export interface NodeDiskUsage {
  node: string;
  usedBytes: number;
  totalBytes: number;
}

interface MetricPoint {
  t: number;
  v: number;
}

export interface MetricSeries {
  available: boolean;
  unit: string;
  points: MetricPoint[];
}

export interface PortForward {
  id: string;
  clusterId: string;
  namespace: string;
  kind: string;
  name: string;
  remotePort: number;
  localPort: number;
  address: string;
  protocol: string;
  status: string;
}

export interface HelmRelease {
  name: string;
  namespace: string;
  revision: number;
  status: string;
  chart: string;
  chartVersion: string;
  appVersion: string;
  updated: string | null;
  /** Installed/upgraded through kweblens (stamped release label) vs created externally. */
  managedByKweblens: boolean;
  /** Newest chart version found across the configured repos, or null. */
  latestVersion: string | null;
  latestRepository: string | null;
  /** latestVersion is newer than the installed chartVersion. */
  updateAvailable: boolean;
}

export interface HelmChart {
  name: string;
  version: string;
  appVersion: string | null;
  description: string | null;
  repository: string;
}

export interface HelmResourceRef {
  apiVersion: string;
  kind: string;
  namespace: string;
  name: string;
}

export interface HelmMutationResult {
  dryRun: boolean;
  name: string;
  namespace: string;
  revision: number;
  status: string | null;
  manifest: string | null;
}
