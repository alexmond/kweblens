import { describe, expect, it } from 'vitest';

import { selectCluster } from './useClusters';

describe('selectCluster', () => {
  it('stays on the cluster you are viewing', () => {
    expect(selectCluster('b', 'a', ['a', 'b', 'c'])).toBe('b');
  });

  it('reopens the remembered cluster on a first load', () => {
    expect(selectCluster(null, 'c', ['a', 'b', 'c'])).toBe('c');
  });

  it('falls back to the first when the remembered one is gone', () => {
    expect(selectCluster(null, 'gone', ['a', 'b'])).toBe('a');
  });

  it('drops a selection whose cluster no longer exists', () => {
    // Removing the cluster you are looking at is a shipped action on the Clusters page.
    // Keeping the dead id left the shell rendering that cluster's name, nav tree and counts
    // over a registry that no longer had it (#298).
    expect(selectCluster('gone', null, ['a'])).toBe('a');
  });

  it('selects nothing when there is nothing — including after the last cluster is removed', () => {
    expect(selectCluster('gone', 'also-gone', [])).toBeNull();
    expect(selectCluster(null, null, [])).toBeNull();
  });
});
