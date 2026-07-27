import { describe, expect, it } from 'vitest';

import { objSpec, objStatus, toNum } from './kube';

describe('kube accessors', () => {
  it('objSpec returns spec or {}', () => {
    expect(objSpec({ spec: { a: 1 } })).toEqual({ a: 1 });
    expect(objSpec({})).toEqual({});
  });

  it('objStatus returns status or {}', () => {
    expect(objStatus({ status: { phase: 'Running' } })).toEqual({ phase: 'Running' });
    expect(objStatus({})).toEqual({});
  });

  it('toNum coerces non-numbers to 0', () => {
    expect(toNum(5)).toBe(5);
    expect(toNum('x')).toBe(0);
    expect(toNum(undefined)).toBe(0);
    expect(toNum(null)).toBe(0);
  });
});
