import { effectScope, nextTick, ref } from 'vue';
import { describe, expect, it } from 'vitest';

import { useDock } from './useDock';

// GH#323: the dock handed every open session whatever cluster was current, so switching cluster
// restarted a terminal against the new one while its tab still named the pod from the old. A
// namespace/pod pair is not an identity — `app/db-0` exists in every cluster running that
// StatefulSet, so the shell could land somewhere else entirely without saying so.

function dock(initial: string | null = 'kind-a') {
  const cluster = ref<string | null>(initial);
  const scope = effectScope();
  let out!: ReturnType<typeof useDock>;
  scope.run(() => (out = useDock(cluster)));
  return { cluster, scope, ...out };
}

describe('useDock', () => {
  it('hides another cluster’s sessions and brings them back on return', async () => {
    const { cluster, scope, sessions, openDock } = dock();
    openDock('terminal', 'app', 'db-0', ['db']);
    expect(sessions.value).toHaveLength(1);

    cluster.value = 'kind-b';
    await nextTick();
    expect(sessions.value).toEqual([]);

    cluster.value = 'kind-a';
    await nextTick();
    expect(sessions.value).toHaveLength(1);
    expect(sessions.value[0].pod).toBe('db-0');
    scope.stop();
  });

  it('stamps the session with the cluster it was opened against', () => {
    const { scope, sessions, openLogs } = dock('kind-a');
    openLogs('app', 'db-0', ['db'], 'container');
    expect(sessions.value[0].cluster).toBe('kind-a');
    scope.stop();
  });

  it('clears the active tab on a switch so no session from elsewhere stays selected', async () => {
    const { cluster, scope, active, openDock } = dock();
    openDock('terminal', 'app', 'db-0', ['db']);
    expect(active.value).not.toBeNull();

    cluster.value = 'kind-b';
    await nextTick();
    expect(active.value).toBeNull();
    scope.stop();
  });

  it('opens nothing when there is no cluster', () => {
    const { scope, sessions, openDock } = dock(null);
    openDock('terminal', 'app', 'db-0', ['db']);
    expect(sessions.value).toEqual([]);
    scope.stop();
  });

  it('closes only the named session and re-points active within this cluster', () => {
    const { scope, sessions, active, openDock, closeDock } = dock();
    openDock('terminal', 'app', 'db-0', ['db']);
    openDock('terminal', 'app', 'db-1', ['db']);
    const second = sessions.value[1].id;
    closeDock(second);
    expect(sessions.value).toHaveLength(1);
    expect(active.value).toBe(sessions.value[0].id);
    scope.stop();
  });
});
