import { effectScope, ref, watch } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { NavItem } from '../types';
import { useResourceData } from './useResourceData';

// Regression guard for the "large-list watch flood": the object watch replays the whole
// list on connect (e.g. 157 ReplicaSets as an ADDED burst). If the SSE handler reassigns
// `objects` per event, the table re-renders N times and the tab freezes before first paint.
// The invariant here: a burst of N events coalesces into AT MOST ONE `objects` update per
// animation frame. If this test fails, per-event re-rendering has been reintroduced.

vi.mock('../api', () => ({
  api: {
    objects: vi.fn(() => Promise.resolve([])),
    printerColumns: vi.fn(() => Promise.resolve([])),
    podMetrics: vi.fn(() => Promise.resolve([])),
    nodeMetrics: vi.fn(() => Promise.resolve([])),
    nodeDisk: vi.fn(() => Promise.resolve([])),
  },
  clusterBase: (c: string) => `/api/v1/clusters/${c}`,
}));

// A fake EventSource we can drive, and a manual requestAnimationFrame pump.
const createdEventSources: FakeEventSource[] = [];
class FakeEventSource {
  listeners: Record<string, (e: { data: string }) => void> = {};
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public url: string) {
    createdEventSources.push(this);
  }
  addEventListener(type: string, cb: (e: { data: string }) => void) {
    this.listeners[type] = cb;
  }
  close() {}
  emit(type: string, data: unknown) {
    this.listeners[type]?.({ data: JSON.stringify(data) });
  }
}

let rafQueue: FrameRequestCallback[] = [];
const flushFrame = () => {
  const q = rafQueue;
  rafQueue = [];
  q.forEach((cb) => cb(0));
};
const flushPromises = () => new Promise((r) => setTimeout(r, 0));

beforeEach(() => {
  createdEventSources.length = 0;
  rafQueue = [];
  vi.stubGlobal('EventSource', FakeEventSource);
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => rafQueue.push(cb));
  vi.stubGlobal('cancelAnimationFrame', () => {});
});

const rs = (i: number) => ({ metadata: { name: `rs-${i}`, namespace: 'ns' } });

describe('useResourceData watch batching', () => {
  it('coalesces a burst of watch events into one objects update per frame', async () => {
    const cluster = ref<string | null>('c1');
    const selected = ref<NavItem | null>({
      id: 'replicasets',
      label: 'Replica Sets',
      kind: 'ReplicaSet',
      namespaced: true,
      expandable: true,
    });
    const namespace = ref<string | null>(null);

    const scope = effectScope();
    let data!: ReturnType<typeof useResourceData>;
    scope.run(() => {
      data = useResourceData(cluster, selected, namespace, () => undefined);
    });
    await flushPromises(); // resolve the initial (mocked, empty) objects fetch

    // Count objects reassignments from here on (excludes setup).
    let updates = 0;
    scope.run(() => watch(data.objects, () => (updates += 1), { flush: 'sync' }));

    const es = createdEventSources[createdEventSources.length - 1];
    expect(es).toBeTruthy();

    // Fire a 157-event connect burst (what froze the tab).
    for (let i = 0; i < 157; i += 1) {
      es.emit('ADDED', rs(i));
    }
    // Buffered — nothing applied until the frame flushes.
    expect(data.objects.value).toHaveLength(0);
    expect(updates).toBe(0);

    // One frame applies the whole burst in a SINGLE update.
    flushFrame();
    expect(data.objects.value).toHaveLength(157);
    expect(updates).toBe(1);

    // A later event on a new frame is its own single update (still flushing, not stuck).
    es.emit('ADDED', rs(999));
    flushFrame();
    expect(data.objects.value).toHaveLength(158);
    expect(updates).toBe(2);

    scope.stop();
  });

  it('applies add / modify / delete correctly within a batch', async () => {
    const cluster = ref<string | null>('c1');
    const selected = ref<NavItem | null>({
      id: 'replicasets',
      label: 'Replica Sets',
      kind: 'ReplicaSet',
      namespaced: true,
    });
    const namespace = ref<string | null>(null);
    const scope = effectScope();
    let data!: ReturnType<typeof useResourceData>;
    scope.run(() => {
      data = useResourceData(cluster, selected, namespace, () => undefined);
    });
    await flushPromises();

    const es = createdEventSources[createdEventSources.length - 1];
    es.emit('ADDED', rs(1));
    es.emit('ADDED', rs(2));
    es.emit('MODIFIED', { metadata: { name: 'rs-1', namespace: 'ns' }, spec: { replicas: 3 } });
    es.emit('DELETED', rs(2));
    flushFrame();

    const names = data.objects.value.map((o) => o.metadata?.name);
    expect(names).toEqual(['rs-1']); // rs-2 added then deleted, rs-1 kept (modified)
    expect((data.objects.value[0].spec as { replicas?: number }).replicas).toBe(3);

    scope.stop();
  });
});
