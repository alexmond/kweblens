import { ref } from 'vue';
import { describe, expect, it } from 'vitest';

import type { NavCategory } from '../types';
import { useNavigation } from './useNavigation';

/**
 * What the namespace filter does across a jump.
 *
 * The overviews count objects within the current filter, so a card that jumps to a kind must land
 * on the SAME slice those numbers came from — clearing the filter would show a list that
 * contradicts the card the user just clicked.
 */
describe('useNavigation', () => {
  const nav: NavCategory[] = [
    {
      label: 'Workloads',
      icon: 'workloads',
      items: [
        { id: 'pods', label: 'Pods', kind: 'Pod', namespaced: true },
        { id: 'nodes', label: 'Nodes', kind: 'Node', namespaced: false },
      ],
    },
  ];

  const setup = (current: string | null) => {
    const state = { selected: '', namespace: current };
    const navi = useNavigation(ref(nav), {
      setSelected: (i) => (state.selected = i.id),
      setDetail: () => {},
      setNamespace: (ns) => (state.namespace = ns),
      setHelmTarget: () => {},
      currentNamespace: () => state.namespace,
    });
    return { state, ...navi };
  };

  it('keeps the filter in force when no namespace is given', () => {
    const { state, navigateToKind } = setup('kwfx-workloads');
    navigateToKind('Pod');
    expect(state.selected).toBe('pods');
    expect(state.namespace).toBe('kwfx-workloads');
  });

  it('moves the filter to the namespace of a named object', () => {
    const { state, navigateToKind } = setup('kwfx-workloads');
    navigateToKind('Pod', 'kube-system');
    expect(state.namespace).toBe('kube-system');
  });

  it('clears the filter for a cluster-scoped kind, which cannot honour it', () => {
    const { state, navigateToKind } = setup('kwfx-workloads');
    navigateToKind('Node');
    expect(state.selected).toBe('nodes');
    expect(state.namespace).toBeNull();
  });

  it('does nothing for a kind the nav does not model, and says so in advance', () => {
    // A control that silently does nothing is worse than one that is plainly inert, so callers
    // can ask first.
    const { state, navigateToKind, knowsKind } = setup(null);
    expect(knowsKind('Pod')).toBe(true);
    expect(knowsKind('Widget')).toBe(false);
    navigateToKind('Widget');
    expect(state.selected).toBe('');
  });
});
