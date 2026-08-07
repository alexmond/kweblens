import { describe, expect, it } from 'vitest';

import type { CheckState } from './checkState';
import { checkedData, checkedDanger, checkedValue, uncheckedNote } from './checkState';

const checking: CheckState<number[]> = { status: 'checking' };
const empty: CheckState<number[]> = { status: 'checked', data: [] };
const some: CheckState<number[]> = { status: 'checked', data: [1, 2, 3] };
const failed: CheckState<number[]> = { status: 'unchecked', message: '403 Forbidden' };

describe('checkedValue', () => {
  it('never reports a failed check as zero — that is the bug this file exists for', () => {
    expect(checkedValue(failed)).toBe('—');
    expect(checkedValue(failed)).not.toBe(0);
  });

  it('reports a real zero as zero', () => {
    expect(checkedValue(empty)).toBe(0);
  });

  it('reports the count once the check has answered', () => {
    expect(checkedValue(some)).toBe(3);
  });

  it('says nothing yet while the check is in flight', () => {
    expect(checkedValue(checking)).toBe('…');
  });
});

describe('checkedDanger', () => {
  it('is true only for a check that answered with something', () => {
    expect(checkedDanger(some)).toBe(true);
    expect(checkedDanger(empty)).toBe(false);
    expect(checkedDanger(checking)).toBe(false);
  });

  it('is false for a failed check — an unknown is not an alarm either', () => {
    expect(checkedDanger(failed)).toBe(false);
  });
});

describe('checkedData', () => {
  it('hands back the data only when there is data, never a fabricated empty', () => {
    expect(checkedData(some)).toEqual([1, 2, 3]);
    expect(checkedData(empty)).toEqual([]);
    expect(checkedData(checking)).toBeNull();
    expect(checkedData(failed)).toBeNull();
  });
});

describe('uncheckedNote', () => {
  it('says the state is unknown rather than clear, and names the cause', () => {
    const note = uncheckedNote(failed, 'warnings');
    expect(note).toContain('Could not check warnings');
    expect(note).toContain('unknown, not clear');
    expect(note).toContain('403 Forbidden');
  });

  it('is null when there is nothing to explain', () => {
    expect(uncheckedNote(checking, 'warnings')).toBeNull();
    expect(uncheckedNote(empty, 'warnings')).toBeNull();
  });
});
