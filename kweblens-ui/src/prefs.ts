// ---- User preferences (per browser) ----
//
// Surface 1 of the #27 config design: things that belong to the person using kweblens, not
// to the deployment. Deliberately localStorage rather than server-side, because there is no
// per-user identity yet (one shared admin) — a server-side store would key every user's
// preferences to the same account and they would overwrite each other. Revisit if/when #38
// gives real identities.
//
// Everything here is best-effort: a corrupt or unavailable store must degrade to defaults,
// never throw into a render. Private-mode browsers can reject localStorage writes outright.

const PREFIX = 'kw.';

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

function write(key: string, value: unknown): void {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // storage full or blocked (private mode) — preferences are a convenience, not a feature
  }
}

/**
 * Hidden columns, keyed by KIND and shared across clusters.
 *
 * Per-kind rather than per-cluster on purpose: which columns of a Node list are useful is a
 * property of Nodes, not of the cluster you are looking at, so a choice made once should
 * follow you everywhere. (Namespace is the opposite — see below.)
 *
 * This is the fix for the papercut in #27: App.vue used to re-seed hiddenCols from
 * defaultHiddenCols on every [selected, namespace] change, so enabling an opt-in column
 * (Nodes → OS Image) was lost the moment you navigated away.
 */
export function loadHiddenCols(resourceId: string | undefined, fallback: Set<string>): Set<string> {
  if (!resourceId) {
    return fallback;
  }
  const saved = read<string[] | null>(`cols.${resourceId}`, null);
  return saved === null ? fallback : new Set(saved);
}

export function saveHiddenCols(resourceId: string | undefined, hidden: Set<string>): void {
  if (resourceId) {
    write(`cols.${resourceId}`, [...hidden]);
  }
}

/** The cluster to reopen on load. Global: it is the last thing you were working on. */
export const loadCluster = (): string | null => read<string | null>('cluster', null);
export const saveCluster = (id: string | null): void => write('cluster', id);

/**
 * The namespace filter, remembered PER CLUSTER — unlike columns. Namespaces are cluster-local,
 * so carrying "monitoring" from one cluster to another would filter on a namespace that may
 * not exist there and silently show an empty list.
 */
export const loadNamespace = (cluster: string): string | null => read<string | null>(`ns.${cluster}`, null);
export const saveNamespace = (cluster: string, ns: string | null): void => write(`ns.${cluster}`, ns);

/**
 * Theme. Migrates the original ad-hoc `kw-theme` key (a bare 'dark'/'light' string, not JSON)
 * so an existing user's choice survives the move to this namespace.
 */
export function loadDark(): boolean {
  const legacy = ((): string | null => {
    try {
      return localStorage.getItem('kw-theme');
    } catch {
      return null;
    }
  })();
  if (legacy !== null) {
    const dark = legacy !== 'light';
    write('dark', dark);
    try {
      localStorage.removeItem('kw-theme');
    } catch {
      // best effort
    }
    return dark;
  }
  return read<boolean>('dark', true);
}

export const saveDark = (dark: boolean): void => write('dark', dark);

/**
 * Whether the nav tree (the 240px category/kind panel) is collapsed.
 *
 * Global rather than per cluster, like the theme: it is a statement about how much of a
 * narrow screen you are willing to spend on navigation, and that does not change when you
 * switch cluster. Defaults to expanded — the collapse is a deliberate choice the user makes
 * once, never something the app decides for them (see `useNavCollapse`).
 */
export const loadNavCollapsed = (): boolean => read<boolean>('navCollapsed', false);
export const saveNavCollapsed = (collapsed: boolean): void => write('navCollapsed', collapsed);
