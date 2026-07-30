import { describe, expect, it } from 'vitest';

import type { KubeObject, Relation } from '../types';
import { relationSections } from './relations';

// The contract these pin: a relation that FAILED must never render as an empty table.
// "There are none" is a factual claim about the cluster, and making it wrongly sends the
// reader after the wrong problem — a broken selector instead of a permissions gap.

const ok = (items: KubeObject[], truncated = false): Relation => ({
  items,
  truncated,
  error: null,
  notPermitted: false,
});

const pod = (name: string, phase: string, node?: string): KubeObject => ({
  kind: 'Pod',
  metadata: { name, namespace: 'app' },
  spec: node ? { nodeName: node } : {},
  status: { phase },
});

describe('failure states', () => {
  it('renders a permissions refusal as a message, not an empty table', () => {
    const [section] = relationSections({
      endpoints: { items: [], truncated: false, error: 'kweblens is not permitted', notPermitted: true },
    });
    expect(section.notPermitted).toBe(true);
    expect(section.message).toContain('not permitted');
    expect(section.rows).toHaveLength(0);
    // No count badge — a count would imply the fetch succeeded and found zero.
    expect(section.count).toBeUndefined();
  });

  it('renders a generic error as a message, distinct from a refusal', () => {
    const [section] = relationSections({
      selectedPods: { items: [], truncated: false, error: 'connection reset', notPermitted: false },
    });
    expect(section.notPermitted).toBeFalsy();
    expect(section.message).toContain('connection reset');
    expect(section.count).toBeUndefined();
  });

  it('distinguishes a genuine empty result from a failure', () => {
    // A Service with nothing backing it is a REAL answer — the most common Service
    // misconfiguration — so it gets a count of 0 and no message.
    const [section] = relationSections({ endpoints: ok([]) });
    expect(section.message).toBeUndefined();
    expect(section.count).toBe(0);
  });

  it('reports truncation instead of implying completeness', () => {
    const [section] = relationSections({ selectedPods: ok([pod('a', 'Running')], true) });
    expect(section.truncated).toBe(true);
  });
});

describe('endpoints projection', () => {
  it('splits ready from not-ready addresses — the whole diagnostic value', () => {
    // "No pods match my selector" and "pods match but none are ready" look identical from the
    // Service object, and have completely different fixes.
    const endpoints: KubeObject = {
      kind: 'Endpoints',
      metadata: { name: 'web', namespace: 'app' },
      subsets: [
        {
          ports: [{ port: 8080 }],
          addresses: [{ ip: '192.0.2.11', targetRef: { name: 'web-a' } }],
          notReadyAddresses: [{ ip: '192.0.2.12', targetRef: { name: 'web-b' } }],
        },
      ],
    } as unknown as KubeObject;

    const [section] = relationSections({ endpoints: ok([endpoints]) });

    expect(section.headers).toEqual(['Address', 'State', 'Ports', 'Target']);
    expect(section.rows.map((r) => r.map((c) => c.text))).toEqual([
      ['192.0.2.11', 'Ready', '8080', 'web-a'],
      ['192.0.2.12', 'Not ready', '8080', 'web-b'],
    ]);
  });

  it('annotates a non-TCP protocol and copes with missing ports', () => {
    const endpoints: KubeObject = {
      kind: 'Endpoints',
      metadata: { name: 'dns', namespace: 'app' },
      subsets: [{ ports: [{ port: 53, protocol: 'UDP' }], addresses: [{ ip: '192.0.2.53' }] }],
    } as unknown as KubeObject;

    const [section] = relationSections({ endpoints: ok([endpoints]) });

    expect(section.rows[0].map((c) => c.text)).toEqual(['192.0.2.53', 'Ready', '53/UDP', '—']);
  });
});

describe('pod projections', () => {
  it('shows status and node for selected pods', () => {
    const [section] = relationSections({ selectedPods: ok([pod('web-a', 'Running', 'node-1')]) });
    expect(section.title).toBe('Selected Pods');
    expect(section.rows[0].map((c) => c.text)).toEqual(['web-a', 'Running', 'node-1', 'app']);
  });

  it('titles mountedBy readably and counts the referencing pods', () => {
    const [section] = relationSections({ mountedBy: ok([pod('a', 'Running'), pod('b', 'Running')]) });
    expect(section.title).toBe('Mounted By');
    expect(section.count).toBe(2);
  });
});

describe('ordering and unknown relations', () => {
  it('orders known relations consistently regardless of key order', () => {
    const sections = relationSections({ mountedBy: ok([]), endpoints: ok([]), selectedPods: ok([]) });
    expect(sections.map((s) => s.title)).toEqual(['Endpoints', 'Selected Pods', 'Mounted By']);
  });

  it('renders a relation the UI does not know about rather than dropping it', () => {
    // The server can add relations without a UI release; an unknown key falls back to its own
    // name and the pod projection rather than disappearing.
    const sections = relationSections({ somethingNew: ok([pod('x', 'Running')]) });
    expect(sections.map((s) => s.title)).toEqual(['somethingNew']);
    expect(sections[0].rows).toHaveLength(1);
  });

  it('returns nothing when the detail has not loaded yet', () => {
    expect(relationSections(undefined)).toEqual([]);
  });
});
