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
