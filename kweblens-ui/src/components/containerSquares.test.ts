import { describe, expect, it } from 'vitest';

import type { KubeObject } from '../types';
import { containerSquares } from './containerSquares';

// The regression this pins: init containers were omitted from the Containers column entirely, so
// a pod stuck in Init:CrashLoopBackOff showed nothing wrong — every app-container square was
// merely in its pre-start "waiting" tone, making a broken pod look like a slow one.

const pod = (over: Partial<KubeObject>): KubeObject => ({ kind: 'Pod', metadata: { name: 'p' }, ...over });

describe('container squares', () => {
  it('puts init containers FIRST, then app containers, in lifecycle order', () => {
    const o = pod({
      spec: { initContainers: [{ name: 'migrate' }], containers: [{ name: 'app' }, { name: 'sidecar' }] },
      status: {
        initContainerStatuses: [{ name: 'migrate', state: { terminated: { reason: 'Completed', exitCode: 0 } } }],
        containerStatuses: [
          { name: 'app', ready: true, state: { running: {} } },
          { name: 'sidecar', ready: true, state: { running: {} } },
        ],
      },
    });
    expect(containerSquares(o).map((s) => [s.role, s.key])).toEqual([
      ['init', 'init:migrate'],
      ['app', 'app:app'],
      ['app', 'app:sidecar'],
    ]);
  });

  it('makes a failing init container visible — the whole point of the fix', () => {
    const o = pod({
      spec: { initContainers: [{ name: 'migrate' }], containers: [{ name: 'app' }] },
      status: {
        initContainerStatuses: [
          { name: 'migrate', ready: false, restartCount: 5, state: { waiting: { reason: 'CrashLoopBackOff' } } },
        ],
        containerStatuses: [{ name: 'app', ready: false, state: { waiting: { reason: 'PodInitializing' } } }],
      },
    });
    const squares = containerSquares(o);
    // Red on the init square; the app container is legitimately just waiting to start.
    expect(squares[0]).toMatchObject({ role: 'init', tone: 'err' });
    expect(squares[0].title).toContain('migrate (init)');
    expect(squares[0].title).toContain('CrashLoopBackOff');
    expect(squares[0].title).toContain('Restarts: 5');
    expect(squares[1]).toMatchObject({ role: 'app', tone: 'wait' });
  });

  it('renders a completed init container in the muted done tone, not as an error', () => {
    // Exit 0 is the NORMAL end state for an init container, so it must not read as alarming.
    const o = pod({
      status: {
        initContainerStatuses: [{ name: 'migrate', state: { terminated: { reason: 'Completed', exitCode: 0 } } }],
        containerStatuses: [{ name: 'app', ready: true, state: { running: {} } }],
      },
    });
    const [init, app] = containerSquares(o);
    expect(init.tone).toBe('done');
    expect(app.tone).toBe('ok');
    // The shape difference is what stops that grey square looking like a broken app container.
    expect(init.role).toBe('init');
  });

  it('marks the last init square so the phases are separated', () => {
    const o = pod({
      status: {
        initContainerStatuses: [
          { name: 'a', state: { terminated: { exitCode: 0 } } },
          { name: 'b', state: { terminated: { exitCode: 0 } } },
        ],
        containerStatuses: [{ name: 'app', ready: true, state: { running: {} } }],
      },
    });
    expect(containerSquares(o).map((s) => s.gap)).toEqual([false, true, false]);
  });

  it('includes ephemeral debug containers, labelled, after the app containers', () => {
    const o = pod({
      status: {
        containerStatuses: [{ name: 'app', ready: true, state: { running: {} } }],
        ephemeralContainerStatuses: [{ name: 'debugger', state: { running: {} } }],
      },
    });
    const squares = containerSquares(o);
    expect(squares.map((s) => s.role)).toEqual(['app', 'ephemeral']);
    expect(squares[1].title).toContain('debugger (debug)');
  });

  it('falls back to the spec when the pod has not started yet', () => {
    const o = pod({ spec: { initContainers: [{ name: 'migrate' }], containers: [{ name: 'app' }] } });
    expect(containerSquares(o).map((s) => [s.role, s.tone])).toEqual([
      ['init', 'wait'],
      ['app', 'wait'],
    ]);
  });

  it('returns nothing for an object with no containers at all', () => {
    expect(containerSquares(pod({ spec: {} }))).toEqual([]);
  });

  it('draws one square per DECLARED container, even when only some have reported', () => {
    // #266. The kubelet reports statuses incrementally, so a three-container pod can be
    // seen with two. The list drew two squares while the drawer listed three rows — two
    // surfaces disagreeing about how many containers a pod has.
    const squares = containerSquares({
      spec: { containers: [{ name: 'app' }, { name: 'sidecar' }, { name: 'proxy' }] },
      status: {
        containerStatuses: [
          { name: 'app', ready: true, state: { running: {} } },
          { name: 'proxy', ready: true, state: { running: {} } },
        ],
      },
    });
    expect(squares).toHaveLength(3);
    expect(squares.map((s) => s.key)).toEqual(['app:app', 'app:sidecar', 'app:proxy']);
    // Spec order is kept, and the unreported one is neutral rather than absent or wrong.
    expect(squares[1].tone).toBe('wait');
    expect(squares[0].tone).not.toBe('wait');
  });

  it('keeps a status that has no spec entry rather than dropping it', () => {
    // Should not happen, but silently hiding a container the API server told us about is
    // the exact bug #266 was.
    const squares = containerSquares({
      spec: { containers: [{ name: 'app' }] },
      status: {
        containerStatuses: [
          { name: 'app', ready: true, state: { running: {} } },
          { name: 'ghost', ready: false, state: { waiting: { reason: 'Unknown' } } },
        ],
      },
    });
    expect(squares.map((s) => s.key)).toEqual(['app:app', 'app:ghost']);
  });

  it('still falls back to the statuses when there is no spec at all', () => {
    const squares = containerSquares({
      status: { containerStatuses: [{ name: 'only', ready: true, state: { running: {} } }] },
    });
    expect(squares).toHaveLength(1);
  });
});
