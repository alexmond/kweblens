import type { Ref } from 'vue';
import { computed } from 'vue';

import { NAV, allNavItems } from '../shell';
import type { NavCategory, NavItem } from '../types';

/** Cross-link navigation: jump to a kind (owner refs), Port Forwards, or a Helm release. */
export function useNavigation(
  nav: Ref<NavCategory[]>,
  actions: {
    setSelected: (i: NavItem) => void;
    setDetail: (d: null) => void;
    setNamespace: (ns: string | null) => void;
    setHelmTarget: (t: { namespace: string; name: string }) => void;
    /** The namespace filter currently in force, so a jump can preserve it. */
    currentNamespace?: () => string | null;
  },
) {
  const { setSelected, setDetail, setNamespace, setHelmTarget, currentNamespace } = actions;
  const kindNav = computed(() => {
    const map = new Map<string, NavItem>();
    allNavItems(nav.value).forEach((i) => i.kind && map.set(i.kind, i));
    return map;
  });
  /**
   * Jump to a kind's list.
   *
   * With an explicit namespace (following a named object) the filter moves to that object's
   * namespace. Without one, the filter already in force is KEPT rather than cleared: a jump made
   * from a namespace-filtered overview should land on the same slice of the cluster the numbers
   * on that overview were counted from.
   */
  const navigateToKind = (kind: string, ns?: string) => {
    const item = kindNav.value.get(kind);
    if (item) {
      setDetail(null);
      setSelected(item);
      setNamespace(item.namespaced ? (ns ?? currentNamespace?.() ?? null) : null);
    }
  };
  const navigateToPortForwards = () => {
    setDetail(null);
    setSelected({ id: NAV.portForwards, label: 'Port Forwards', kind: '', namespaced: false });
  };
  const navigateToHelmRelease = (namespace: string, name: string) => {
    setDetail(null);
    setSelected({ id: NAV.helmReleases, label: 'Releases', kind: '', namespaced: false });
    setHelmTarget({ namespace, name });
  };
  /**
   * Whether {@link navigateToKind} would actually go somewhere for this kind.
   *
   * Exposed so callers can avoid presenting a control that silently does nothing — an event may
   * name a kind the nav does not model, and a dead click is worse than an inert row.
   */
  const knowsKind = (kind: string) => kindNav.value.has(kind);

  /**
   * The resource id that addresses a kind in the API, or null when the nav does not model
   * it.
   *
   * Needed because a row is not always the kind of the list it appears in: an expanded
   * workload carries its child pods as rows, and using the list's id for one of those asks
   * the server for a Deployment by a pod's name.
   */
  const resourceIdForKind = (kind: string) => kindNav.value.get(kind)?.id ?? null;
  return { navigateToKind, navigateToPortForwards, navigateToHelmRelease, knowsKind, resourceIdForKind };
}
