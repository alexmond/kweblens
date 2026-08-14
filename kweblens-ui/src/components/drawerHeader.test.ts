import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { parseRowActionKey, rowActionOptions } from '../rowActions';
import type { KubeObject } from '../types';
import { DRAWER_BUTTON_LIMIT, drawerActions, drawerBadges } from './drawerHeader';

// The stylesheet as TEXT, same reading as responsive.test.ts: comments stripped first, because
// the comments here talk ABOUT the padding reserve that was removed and a rule that exists only
// in prose is not a rule.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

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

describe('the drawer header lays its controls out as one row (#379)', () => {
  it('puts the close on the title first row rather than the middle of the header', () => {
    // Naive's close is a laid-out flex CHILD of `.n-drawer-header` (measured: it computes to
    // `position: relative`, whatever the `n-base-close--absolute` class says), and the header
    // centres its children. At >=900px the title grows a second row for the identity badges,
    // so a centred close dropped half that row's height below the expand toggle — measured
    // expand top=60 against close top=74.34 in the expanded drawer, at 1400px and at 1900px.
    expect(css).toMatch(/\.n-drawer-header\s*\{[^}]*align-items:\s*flex-start/);
  });

  it('sizes both header controls in ONE declaration, so they cannot drift apart', () => {
    // Same top is not the same line when an 18px icon sits beside a 21px button. Written as one
    // rule listing both selectors: two separate heights are two numbers to keep in step.
    expect(css).toMatch(
      /\.n-drawer-header\s*>\s*\.n-drawer-header__close,\s*\.drawer-title\s*>\s*\.drawer-expand\s*\{[^}]*height:/,
    );
  });

  it('reserves no corner for the close, because the close is not overlapping anything', () => {
    // `.drawer-title { padding-right: 28px }` stood here "to reserve the corner for Naive's
    // absolutely-positioned close". The close is in flow, the header's own padding already
    // holds that corner, and all the reserve did was hold the expand toggle 34px clear of the
    // control it is meant to sit beside. A spacing hack whose reason has gone comes out.
    const titleRules = [...css.matchAll(/\.drawer-title\s*\{([^}]*)\}/g)].map((m) => m[1]);
    expect(titleRules.length).toBeGreaterThan(0);
    for (const body of titleRules) {
      expect(body).not.toContain('padding-right');
    }
  });

  it('has no dead `.drawer-head` rule left to send the next reader to the wrong element', () => {
    // #379 was reported against `.drawer-head`, a leftover from the hand-rolled panel that
    // `NDrawer` replaced. No template has carried it for a long time; the live header is
    // `.n-drawer-header > .n-drawer-header__main > .drawer-head-pane > .drawer-title`.
    expect(css).not.toMatch(/\.drawer-head\s*[,{]/);
    expect(css).not.toMatch(/\.drawer-close\s*[,{:]/);
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
