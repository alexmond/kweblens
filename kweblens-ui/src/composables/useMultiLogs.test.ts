import { effectScope, ref, watch } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { DockSession } from '../dock';
import { useMultiLogs } from './useMultiLogs';

// Multi-source logs multiply the arrival rate: following a whole Deployment means N streams
// landing in the same tab, so the rAF-batching invariant that protects the resource list
// (see useResourceData.test.ts) matters even more here. These tests pin (1) that a burst
// coalesces into ONE update per animation frame, and (2) that source visibility and the text
// filter are applied at RENDER time, so toggling them re-reveals lines already received
// rather than only affecting future ones.

vi.mock('../api', () => ({ clusterBase: (c: string) => `/api/v1/clusters/${c}` }));

const createdEventSources: FakeEventSource[] = [];
class FakeEventSource {
  listeners: Record<string, (e: { data: string }) => void> = {};
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

beforeEach(() => {
  createdEventSources.length = 0;
  rafQueue = [];
  vi.stubGlobal('EventSource', FakeEventSource);
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => rafQueue.push(cb));
  vi.stubGlobal('cancelAnimationFrame', () => {});
});

const SESSION: DockSession = {
  id: 'logs:ns/web#1',
  kind: 'logs',
  namespace: 'ns',
  pod: 'web-0',
  containers: ['app', 'sidecar'],
  logScope: 'workload',
  workload: { resourceId: 'deployments', name: 'web' },
};

const POD_A = 'ns/web-a/app';
const POD_B = 'ns/web-b/app';

// Realistic replica names: a long shared generated prefix, differing only in the suffix.
const REPLICA_A = 'demo/podinfo-6fb65cb78-6gn6z/podinfo';
const REPLICA_B = 'demo/podinfo-6fb65cb78-t9mht/podinfo';

function start(session: DockSession = SESSION) {
  const scope = effectScope();
  let logs!: ReturnType<typeof useMultiLogs>;
  const paused = ref(false);
  scope.run(() => {
    logs = useMultiLogs(
      () => 'c1',
      () => session,
      () => '',
      () => paused.value,
    );
  });
  const es = createdEventSources[createdEventSources.length - 1];
  return { scope, logs, es, paused };
}

const line = (source: string, text: string, timestamp: string | null = '2026-07-29T12:00:00Z') => ({
  source,
  timestamp,
  text,
});

describe('useMultiLogs batching', () => {
  it('coalesces a burst of lines from several sources into one update per frame', () => {
    const { scope, logs, es } = start();
    es.emit('sources', { sources: [POD_A, POD_B], truncated: false, totalFound: 2 });

    let updates = 0;
    scope.run(() => watch(logs.lines, () => (updates += 1), { flush: 'sync' }));

    for (let i = 0; i < 120; i += 1) {
      es.emit('line', line(i % 2 === 0 ? POD_A : POD_B, `msg-${i}`));
    }
    // Buffered: nothing applied until the frame flushes.
    expect(logs.lines.value).toHaveLength(0);
    expect(updates).toBe(0);

    flushFrame();
    expect(logs.lines.value).toHaveLength(120);
    expect(updates).toBe(1);

    // Still flushing on later frames, one update each.
    es.emit('line', line(POD_A, 'later'));
    flushFrame();
    expect(logs.lines.value).toHaveLength(121);
    expect(updates).toBe(2);

    scope.stop();
  });

  it('drops lines while paused, and resumes without replaying them', () => {
    const { scope, logs, es, paused } = start();
    es.emit('sources', { sources: [POD_A], truncated: false, totalFound: 1 });

    paused.value = true;
    es.emit('line', line(POD_A, 'while-paused'));
    flushFrame();
    expect(logs.lines.value).toHaveLength(0);

    paused.value = false;
    es.emit('line', line(POD_A, 'after-resume'));
    flushFrame();
    expect(logs.lines.value.map((l) => l.text)).toEqual(['after-resume']);

    scope.stop();
  });
});

describe('useMultiLogs sources and filtering', () => {
  it('assigns each source a distinct colour and a pod/container label in workload scope', () => {
    const { scope, logs, es } = start();
    es.emit('sources', { sources: [POD_A, POD_B], truncated: false, totalFound: 2 });

    expect(logs.sources.value.map((s) => s.label)).toEqual(['web-a/app', 'web-b/app']);
    expect(logs.colourOf(POD_A)).not.toBe(logs.colourOf(POD_B));
    expect(logs.showPrefix.value).toBe(true);

    scope.stop();
  });

  it('strips the shared replica prefix so the gutter labels differ where it is truncated', () => {
    // Without this, both labels render as "podinfo-6fb65cb7…" in the fixed-width gutter and
    // colour becomes the ONLY way to tell two replicas apart.
    const { scope, logs, es } = start();
    es.emit('sources', { sources: [REPLICA_A, REPLICA_B], truncated: false, totalFound: 2 });

    expect(logs.sources.value.map((s) => s.label)).toEqual(['6gn6z/podinfo', 't9mht/podinfo']);
    // Distinct within the first few characters, which is all the gutter shows.
    const [a, b] = logs.sources.value.map((s) => s.label.slice(0, 4));
    expect(a).not.toBe(b);

    scope.stop();
  });

  it('keeps the full pod name when there is nothing shared to strip', () => {
    const { scope, logs, es } = start();
    es.emit('sources', { sources: [REPLICA_A], truncated: false, totalFound: 1 });

    // A single source has no shared prefix to remove — trimming would leave nothing.
    expect(logs.sources.value[0].label).toBe('podinfo-6fb65cb78-6gn6z/podinfo');

    scope.stop();
  });

  it('hides a source without discarding its lines, so re-enabling brings them back', () => {
    const { scope, logs, es } = start();
    es.emit('sources', { sources: [POD_A, POD_B], truncated: false, totalFound: 2 });
    es.emit('line', line(POD_A, 'from-a'));
    es.emit('line', line(POD_B, 'from-b'));
    flushFrame();

    logs.setVisible(POD_B, false);
    expect(logs.visibleLines.value.map((l) => l.text)).toEqual(['from-a']);
    expect(logs.lines.value).toHaveLength(2); // still buffered, just not rendered

    logs.setVisible(POD_B, true);
    expect(logs.visibleLines.value).toHaveLength(2);

    logs.showOnly(POD_B);
    expect(logs.visibleLines.value.map((l) => l.text)).toEqual(['from-b']);

    logs.showAll();
    expect(logs.visibleLines.value).toHaveLength(2);

    scope.stop();
  });

  it('filters on text case-insensitively and reports truncation', () => {
    const { scope, logs, es } = start();
    es.emit('sources', { sources: [POD_A], truncated: true, totalFound: 60 });
    es.emit('line', line(POD_A, 'Connection refused'));
    es.emit('line', line(POD_A, 'ready'));
    flushFrame();

    logs.filter.value = 'REFUSED';
    expect(logs.visibleLines.value.map((l) => l.text)).toEqual(['Connection refused']);

    // Truncation must be visible — silently following a subset would read as "all the pods".
    expect(logs.truncated.value).toEqual({ shown: 1, totalFound: 60 });

    scope.stop();
  });

  it('marks a single failing source without blanking the others', () => {
    const { scope, logs, es } = start();
    es.emit('sources', { sources: [POD_A, POD_B], truncated: false, totalFound: 2 });
    es.emit('source-error', { source: POD_B, message: 'container is not running' });
    es.emit('line', line(POD_A, 'still-streaming'));
    flushFrame();

    expect(logs.sources.value.find((s) => s.id === POD_B)?.error).toBe('container is not running');
    expect(logs.error.value).toBeNull();
    expect(logs.visibleLines.value.map((l) => l.text)).toEqual(['still-streaming']);

    scope.stop();
  });

  it('prefixes copied text with the source only when following more than one', () => {
    const single = start({ ...SESSION, logScope: 'container' });
    single.es.emit('sources', { sources: [POD_A], truncated: false, totalFound: 1 });
    single.es.emit('line', line(POD_A, 'solo'));
    flushFrame();
    expect(single.logs.showPrefix.value).toBe(false);
    expect(single.logs.asText()).toBe('solo');
    single.scope.stop();

    const many = start();
    many.es.emit('sources', { sources: [POD_A, POD_B], truncated: false, totalFound: 2 });
    many.es.emit('line', line(POD_A, 'one'));
    flushFrame();
    expect(many.logs.asText()).toBe('[web-a/app] one');
    many.scope.stop();
  });
});
