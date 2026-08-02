import { describe, expect, it } from 'vitest';

import { parseRowActionKey, rowActionOptions } from '../rowActions';
import type { KubeObject } from '../types';
import { DRAWER_BUTTON_LIMIT, drawerActions, drawerBadges } from './drawerHeader';

const pod = (over: Partial<KubeObject> = {}): KubeObject => ({
  kind: 'Pod',
  metadata: { name: 'api-0', namespace: 'app' },
  spec: { containers: [{ name: 'api' }] },
  ...over,
});

const deployment: KubeObject = {
  kind: 'Deployment',
  metadata: { name: 'api', namespace: 'app' },
  spec: { replicas: 2, template: { spec: { containers: [{ name: 'api' }] } } },
};

describe('drawerBadges', () => {
  it('always leads with the kind, so the wide header says what the object is', () => {
    expect(drawerBadges(pod())[0]).toMatchObject({ text: 'Pod', tone: 'kind' });
  });

  it('adds the namespace when there is one', () => {
    expect(drawerBadges(pod()).map((b) => b.text)).toEqual(['Pod', 'app']);
  });

  it('omits the namespace for a cluster-scoped object rather than showing an em-dash', () => {
    const node: KubeObject = { kind: 'Node', metadata: { name: 'node-1' } };
    expect(drawerBadges(node).map((b) => b.text)).toEqual(['Node']);
  });

  it('names the owning Helm release when the annotation is there', () => {
    const managed = pod({
      metadata: { name: 'api-0', namespace: 'app', annotations: { 'meta.helm.sh/release-name': 'vmstack' } },
    });
    expect(drawerBadges(managed).map((b) => b.text)).toEqual(['Pod', 'app', 'Helm: vmstack']);
  });
});

describe('drawerActions', () => {
  it('never offers more buttons than the tab row has room for', () => {
    for (const obj of [pod(), deployment, { kind: 'Node', metadata: { name: 'n1' } } as KubeObject]) {
      expect(drawerActions(obj).buttons.length).toBeLessThanOrEqual(DRAWER_BUTTON_LIMIT);
    }
  });

  it('promotes the actions people reach for, not whatever the registry lists first', () => {
    // The registry's own order opens with Attach to Pod; a toolbar with three slots should
    // not spend one of them on it.
    expect(drawerActions(pod()).buttons.map((a) => a.id)).toEqual(['logs', 'terminal']);
    expect(drawerActions(deployment).buttons.map((a) => a.id)).toEqual(['logsAll', 'restart', 'scale']);
  });

  it('promotes nothing destructive — a one-click Drain or Delete is one mis-aimed click', () => {
    const node: KubeObject = { kind: 'Node', metadata: { name: 'node-1' } };
    for (const obj of [pod(), deployment, node]) {
      expect(drawerActions(obj).buttons.some((a) => a.danger)).toBe(false);
    }
  });

  it('keeps a container-scoped action in the menu on a multi-container pod', () => {
    // Which container is a question, and a question needs the submenu that only the menu has.
    const multi = pod({ spec: { containers: [{ name: 'api' }, { name: 'sidecar' }] } });
    expect(drawerActions(multi).buttons.map((a) => a.id)).toEqual([]);
    expect(
      drawerActions(multi)
        .menu.find((o) => o.key === 'logs')
        ?.children?.map((c) => c.label),
    ).toEqual(['All containers', 'api', 'sidecar']);
  });

  it('offers the COMPLETE action list in the menu, so a button is never the only way in', () => {
    const { buttons, menu } = drawerActions(deployment);
    const keys = menu.map((o) => o.key);
    for (const b of buttons) {
      expect(keys).toContain(b.id);
    }
    expect(keys).toContain('delete');
  });

  it('offers the same actions the resource list does — one registry, two surfaces', () => {
    expect(drawerActions(deployment).menu).toEqual(rowActionOptions(deployment));
  });
});

describe('rowActionOptions', () => {
  it('divides the kind-specific actions from Edit/Delete', () => {
    const menu = rowActionOptions(deployment);
    const divider = menu.findIndex((o) => o.type === 'divider');
    expect(divider).toBeGreaterThan(0);
    expect(menu.slice(divider + 1).map((o) => o.key)).toEqual(['edit', 'delete', 'forceDelete']);
  });

  it('marks the destructive entries so they can be styled as such', () => {
    expect(rowActionOptions(deployment).find((o) => o.key === 'delete')?.props?.class).toBe('menu-danger');
  });
});

describe('parseRowActionKey', () => {
  it('reads a plain action key', () => {
    expect(parseRowActionKey('restart')).toEqual({ action: 'restart', container: undefined });
  });

  it('reads the container a submenu scoped the action to', () => {
    expect(parseRowActionKey('logs::sidecar')).toEqual({ action: 'logs', container: 'sidecar' });
  });
});
