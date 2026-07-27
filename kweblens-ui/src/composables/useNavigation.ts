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
  },
) {
  const { setSelected, setDetail, setNamespace, setHelmTarget } = actions;
  const kindNav = computed(() => {
    const map = new Map<string, NavItem>();
    allNavItems(nav.value).forEach((i) => i.kind && map.set(i.kind, i));
    return map;
  });
  const navigateToKind = (kind: string, ns?: string) => {
    const item = kindNav.value.get(kind);
    if (item) {
      setDetail(null);
      setSelected(item);
      setNamespace(item.namespaced && ns ? ns : null);
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
  return { navigateToKind, navigateToPortForwards, navigateToHelmRelease };
}
