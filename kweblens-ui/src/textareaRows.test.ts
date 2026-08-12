import { describe, expect, it } from 'vitest';

import { initialRows } from './textareaRows';

/** A PEM certificate — the case the whole change exists for, and it arrives as real lines. */
const PEM = [
  '-----BEGIN CERTIFICATE-----',
  'MIIDdzCCAl+gAwIBAgIEAgAAuTAN',
  'BgkqhkiG9w0BAQUFADBaMQswCQYD',
  '-----END CERTIFICATE-----',
].join('\n');

describe('initialRows', () => {
  it('gives an empty value the minimum, not zero rows', () => {
    expect(initialRows('', 1, 12)).toBe(1);
    expect(initialRows(null, 1, 12)).toBe(1);
    expect(initialRows(undefined, 1, 12)).toBe(1);
  });

  it('gives a one-line value the minimum, so a short field still looks like a field', () => {
    expect(initialRows('production', 1, 12)).toBe(1);
  });

  it('opens a PEM certificate at its own line count', () => {
    expect(initialRows(PEM, 1, 12)).toBe(4);
  });

  it('does not count the trailing newline that ends a text file as a row', () => {
    expect(initialRows('a\nb\n', 1, 12)).toBe(2);
    expect(initialRows('a\nb', 1, 12)).toBe(2);
  });

  it('counts a genuinely blank interior line', () => {
    expect(initialRows('a\n\nb', 1, 12)).toBe(3);
  });

  it('never returns fewer than min, however short the content', () => {
    expect(initialRows('one line', 12, 24)).toBe(12);
  });

  it('clamps at max so a huge value cannot open a field taller than its dialog', () => {
    const huge = Array.from({ length: 4000 }, (_, i) => `line ${i}`).join('\n');
    expect(initialRows(huge, 12, 24)).toBe(24);
  });

  it('does not guess at wrapped rows — a long single line is still one row', () => {
    // Deliberate: visual rows depend on the rendered width and font, which this module cannot
    // see. Guessing produced a 20%-out estimate the last time this project tried it.
    expect(initialRows('x'.repeat(5000), 1, 12)).toBe(1);
  });

  it('refuses a max below min rather than silently returning min', () => {
    expect(() => initialRows('a', 12, 4)).toThrow(/below min/);
  });
});
