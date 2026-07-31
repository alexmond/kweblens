import { describe, expect, it } from 'vitest';

import { OVERVIEW_CATEGORIES, overviewCategoryOf } from './overviewCategories';

describe('overviewCategoryOf', () => {
  it('resolves a category overview id', () => {
    expect(overviewCategoryOf('overview:network')).toBe('network');
    expect(overviewCategoryOf('overview:config')).toBe('config');
  });

  it('does not claim the cluster overview, which is a different component', () => {
    // The cluster dashboard has its own nodes/metrics/warnings layout; routing it here would
    // render it as a bare category page.
    expect(overviewCategoryOf('overview:cluster')).toBeNull();
  });

  it('returns null for anything else', () => {
    expect(overviewCategoryOf('pods')).toBeNull();
    expect(overviewCategoryOf(undefined)).toBeNull();
    expect(overviewCategoryOf('overview:invented')).toBeNull();
  });
});

describe('category copy', () => {
  it('states what every check does not cover, where its result is read', () => {
    // A claim about the cluster without its bounds is the failure this copy exists to prevent —
    // "not referenced" must not be read as "safe to delete".
    expect(OVERVIEW_CATEGORIES.config.notes?.join(' ')).toContain('Not evidence that an object is unused');
    // Capacity IS checked now, but only where the reading is about the claim — the bound
    // still has to be stated, or a reader assumes every volume was measured.
    expect(OVERVIEW_CATEGORIES.storage.notes?.join(' ')).toContain('only where the provisioner reports per-volume');
    expect(OVERVIEW_CATEGORIES.network.notes?.join(' ')).toContain('ExternalName');
  });

  it('marks only Config advisory, because unreferenced is not the same as broken', () => {
    // Over half the ConfigMaps on a real cluster are unreferenced by this scan and entirely in
    // use — control-plane config, secrets named by custom resources. Colouring those as faults
    // is the false alarm that gets the whole screen ignored.
    expect(OVERVIEW_CATEGORIES.config.advisory).toBe(true);
    expect(OVERVIEW_CATEGORIES.workloads.advisory).toBeUndefined();
    expect(OVERVIEW_CATEGORIES.network.advisory).toBeUndefined();
    expect(OVERVIEW_CATEGORIES.storage.advisory).toBeUndefined();
  });

  it('gives every category a heading and a clean-result line', () => {
    for (const [name, copy] of Object.entries(OVERVIEW_CATEGORIES)) {
      expect(copy.title, name).toBeTruthy();
      expect(copy.attention, name).toBeTruthy();
      expect(copy.clean, name).toBeTruthy();
    }
  });
});
