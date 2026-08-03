import { describe, expect, it } from 'vitest';

import type { KubeObject } from '../types';
import type { SectionRank } from './overview';
import {
  OVERVIEW_FIELDS,
  OVERVIEW_SECTIONS,
  dataValueText,
  fromPodTemplate,
  hasContainerRuntime,
  rankOf,
  readyText,
  restartsText,
  secretValueText,
  splitByRank,
} from './overview';
import { relationSections } from './relations';

// The Environment section encodes a SECURITY decision, so it is pinned here: a value sourced
// from a Secret is rendered as a REFERENCE (secret <name>/<key>) and the secret's value is
// never fetched or displayed. Literal values ARE shown, deliberately — they live in the pod
// spec, which the YAML tab already displays in full, so masking them here would be theatre.

const section = (title: string) => {
  const s = OVERVIEW_SECTIONS.find((x) => x.title === title);
  if (!s) {
    throw new Error(`no section titled ${title}`);
  }
  return s;
};
const rowsOf = (title: string, o: KubeObject): string[][] => {
  const body = section(title).body(o);
  if (body.type !== 'table') {
    throw new Error('expected a table body');
  }
  return body.rows.map((r) => r.map((c) => c.text));
};

const pod: KubeObject = {
  kind: 'Pod',
  metadata: { name: 'db-0', namespace: 'app' },
  spec: {
    containers: [
      {
        name: 'postgres',
        env: [
          { name: 'PGDATA', value: '/var/lib/postgresql/data' },
          { name: 'PASSWORD', valueFrom: { secretKeyRef: { name: 'db-creds', key: 'password' } } },
          { name: 'SETTING', valueFrom: { configMapKeyRef: { name: 'db-config', key: 'tuning' } } },
          { name: 'MY_NODE', valueFrom: { fieldRef: { fieldPath: 'spec.nodeName' } } },
          { name: 'NOTHING' },
        ],
        envFrom: [{ secretRef: { name: 'extra-creds' } }, { configMapRef: { name: 'extra-config' } }],
      },
    ],
    initContainers: [{ name: 'migrate', env: [{ name: 'MODE', value: 'up' }] }],
  },
  status: {
    containerStatuses: [
      {
        name: 'postgres',
        ready: true,
        restartCount: 3,
        state: { running: { startedAt: '2026-07-22T05:34:31Z' } },
        lastState: { terminated: { reason: 'OOMKilled', exitCode: 137 } },
      },
    ],
    initContainerStatuses: [
      { name: 'migrate', ready: true, restartCount: 0, state: { terminated: { reason: 'Completed', exitCode: 0 } } },
    ],
  },
};

describe('Environment section', () => {
  it('shows secret-sourced values as a reference, never a value', () => {
    const rows = rowsOf('Environment', pod);
    expect(rows).toContainEqual(['postgres', 'PASSWORD', 'secret db-creds/password']);
    // whole-map imports name the source object only
    expect(rows).toContainEqual(['postgres', '(all keys)', 'secret extra-creds']);
  });

  it('shows literal, configMap, field and empty sources', () => {
    const rows = rowsOf('Environment', pod);
    expect(rows).toContainEqual(['postgres', 'PGDATA', '/var/lib/postgresql/data']);
    expect(rows).toContainEqual(['postgres', 'SETTING', 'configMap db-config/tuning']);
    expect(rows).toContainEqual(['postgres', 'MY_NODE', 'field spec.nodeName']);
    expect(rows).toContainEqual(['postgres', 'NOTHING', '—']);
    expect(rows).toContainEqual(['postgres', '(all keys)', 'configMap extra-config']);
  });

  it('covers init containers too, and hides itself when there is no env', () => {
    expect(rowsOf('Environment', pod)).toContainEqual(['migrate', 'MODE', 'up']);
    const bare: KubeObject = { kind: 'Pod', metadata: { name: 'p' }, spec: { containers: [{ name: 'c' }] } };
    expect(section('Environment').applies(bare)).toBe(false);
    expect(section('Environment').applies(pod)).toBe(true);
  });
});

describe('Container Status section', () => {
  it('surfaces the previous run reason and exit code (the crashloop diagnostic)', () => {
    const rows = rowsOf('Container Status', pod);
    expect(rows).toContainEqual(['postgres', 'Yes', '3', 'Running since 2026-07-22T05:34:31Z', 'OOMKilled (exit 137)']);
  });

  it('includes init containers and renders terminated state', () => {
    expect(rowsOf('Container Status', pod)).toContainEqual(['migrate', 'Yes', '0', 'Completed (exit 0)', '—']);
  });
});

// ---- #248: the Containers table must not state runtime facts it does not have ----

const tableOf = (title: string, o: KubeObject) => {
  const body = section(title).body(o);
  if (body.type !== 'table') {
    throw new Error('expected a table body');
  }
  return body;
};

describe('Containers section: absent runtime status is not "not ready"', () => {
  it('reports a Pod’s real readiness and restarts', () => {
    const t = tableOf('Containers', pod);
    expect(t.headers).toEqual(['Name', 'Image', 'Ports', 'Requests', 'Ready', 'Restarts']);
    expect(t.rows[0].map((c) => c.text).slice(-2)).toEqual(['Yes', '3']);
    expect(t.note).toBeUndefined();
  });

  it('drops both columns for a kind whose containers come from a pod template', () => {
    // The bug: `Ready: No` / `Restarts: 0` on a healthy Deployment, four lines above a Rollout
    // section reading `Desired 3 / Ready 2`. Neither number existed to be read.
    const t = tableOf('Containers', deployment);
    expect(t.headers).toEqual(['Name', 'Image', 'Ports', 'Requests']);
    expect(t.rows.every((r) => r.length === 4)).toBe(true);
    expect(t.rows.flat().map((c) => c.text)).not.toContain('No');
    expect(t.note).toMatch(/per-pod runtime state/);
  });

  it('does not call a Pod a template when it simply has not started yet', () => {
    // Both cases drop the columns, but for different reasons, so they must not share a
    // sentence: a Deployment will never have container status; a Pending pod does not have it
    // YET. Saying "this object carries a pod template" of a Pod is a new false statement.
    const pending: KubeObject = {
      kind: 'Pod',
      metadata: { name: 'p' },
      spec: { containers: [{ name: 'app', image: 'nginx:latest' }] },
      status: { phase: 'Pending' },
    };
    const t = tableOf('Containers', pending);
    expect(t.headers).toEqual(['Name', 'Image', 'Ports', 'Requests']);
    expect(fromPodTemplate(pending)).toBe(false);
    expect(t.note).toMatch(/has not reported any container status yet/);
    expect(t.note).not.toMatch(/template/);
  });

  it('holds for every template-backed kind, not just Deployment', () => {
    for (const kind of ['StatefulSet', 'DaemonSet', 'ReplicaSet', 'Job']) {
      const o: KubeObject = {
        kind,
        metadata: { name: 'x' },
        spec: { template: { spec: { containers: [{ name: 'app', image: 'nginx:latest' }] } } },
      };
      expect(hasContainerRuntime(o)).toBe(false);
      expect(tableOf('Containers', o).headers).not.toContain('Ready');
    }
  });

  it('keeps the columns for a Pod but dashes the container that has no status yet', () => {
    // A Pod DOES answer these questions — for the containers it has started. A second
    // container with no status is unknown, not failing.
    const starting: KubeObject = {
      kind: 'Pod',
      metadata: { name: 'p' },
      spec: { containers: [{ name: 'app' }, { name: 'sidecar' }] },
      status: { containerStatuses: [{ name: 'app', ready: false, restartCount: 2 }] },
    };
    const rows = tableOf('Containers', starting).rows.map((r) => r.map((c) => c.text));
    expect(rows[0].slice(-2)).toEqual(['No', '2']);
    expect(rows[1].slice(-2)).toEqual(['—', '—']);
  });

  it('distinguishes known-false from absent in the cell helpers', () => {
    expect(readyText({ ready: true })).toBe('Yes');
    expect(readyText({ ready: false })).toBe('No');
    expect(readyText({})).toBe('—');
    expect(readyText(undefined)).toBe('—');
    expect(restartsText({ restartCount: 0 })).toBe('0');
    expect(restartsText({})).toBe('—');
    expect(restartsText(undefined)).toBe('—');
  });
});

// ---- #24 audit: pod-template resolution + the new diagnostic sections ----

const pairsOf = (title: string, o: KubeObject): Record<string, string> => {
  const body = section(title).body(o);
  if (body.type !== 'kv') {
    throw new Error('expected a kv body');
  }
  return Object.fromEntries(body.pairs);
};

/** A Deployment: containers live under spec.template.spec, NOT spec. */
const deployment: KubeObject = {
  kind: 'Deployment',
  metadata: { name: 'web', namespace: 'app' },
  spec: {
    replicas: 3,
    strategy: { type: 'RollingUpdate', rollingUpdate: { maxSurge: 1, maxUnavailable: 0 } },
    template: {
      spec: {
        serviceAccountName: 'web-sa',
        restartPolicy: 'Always',
        nodeSelector: { tier: 'front' },
        volumes: [{ name: 'cfg', configMap: { name: 'app-config' } }],
        containers: [
          {
            name: 'nginx',
            image: 'nginx:1.27',
            resources: { requests: { cpu: '100m', memory: '128Mi' }, limits: { memory: '256Mi' } },
            readinessProbe: { httpGet: { path: '/healthz', port: 8080 }, periodSeconds: 10, failureThreshold: 3 },
            volumeMounts: [{ name: 'cfg', mountPath: '/etc/nginx/conf.d', readOnly: true }],
          },
        ],
        initContainers: [{ name: 'migrate', image: 'migrate:1' }],
      },
    },
  },
  status: { replicas: 3, readyReplicas: 2, updatedReplicas: 3, unavailableReplicas: 1 },
};

describe('pod-template resolution', () => {
  // The bug this guards: every container/volume section read spec.* directly, which is only
  // right for a Pod — so a Deployment's detail drawer was almost entirely empty.
  it('finds a Deployment’s containers through spec.template.spec', () => {
    expect(section('Containers').applies(deployment)).toBe(true);
    expect(rowsOf('Containers', deployment)[0][0]).toBe('nginx');
  });

  it('finds template-level volumes, node selector and service account', () => {
    expect(section('Volumes').applies(deployment)).toBe(true);
    expect(section('Node Selector').applies(deployment)).toBe(true);
    const sa = OVERVIEW_FIELDS.find((f) => f.label === 'Service Account')?.get(deployment);
    expect(sa).toMatchObject({ text: 'web-sa', navKind: 'ServiceAccount', navNs: 'app' });
  });

  it('reaches through a CronJob’s extra jobTemplate level', () => {
    const cronJob: KubeObject = {
      kind: 'CronJob',
      metadata: { name: 'nightly' },
      spec: {
        schedule: '0 2 * * *',
        suspend: false,
        jobTemplate: { spec: { template: { spec: { containers: [{ name: 'backup', image: 'backup:2' }] } } } },
      },
    };
    expect(rowsOf('Containers', cronJob)[0][0]).toBe('backup');
    expect(pairsOf('Schedule & Runs', cronJob)).toMatchObject({ Schedule: '0 2 * * *', Suspended: 'No' });
  });
});

describe('Resources section', () => {
  it('shows requests and limits side by side, with a dash for what is unset', () => {
    // The gap between request and limit is the point: memory has a limit, cpu does not.
    expect(rowsOf('Resources', deployment)).toEqual([
      ['nginx', '100m', '—', '128Mi', '256Mi'],
      ['migrate (init)', '—', '—', '—', '—'],
    ]);
  });

  it('hides itself when no container declares any resources', () => {
    const bare: KubeObject = { kind: 'Pod', metadata: { name: 'p' }, spec: { containers: [{ name: 'c' }] } };
    expect(section('Resources').applies(bare)).toBe(false);
  });
});

describe('Probes section', () => {
  it('renders the target and the thresholds, not just the path', () => {
    // An over-aggressive liveness probe looks like an app crash, so timing is load-bearing.
    expect(rowsOf('Probes', deployment)).toEqual([
      ['nginx', 'readiness', 'http://:8080/healthz', 'every 10s, fail x3'],
    ]);
  });

  it('handles exec and tcp probes', () => {
    const p: KubeObject = {
      kind: 'Pod',
      metadata: { name: 'p' },
      spec: {
        containers: [
          {
            name: 'c',
            livenessProbe: { exec: { command: ['sh', '-c', 'true'] } },
            startupProbe: { tcpSocket: { port: 5432 } },
          },
        ],
      },
    };
    expect(rowsOf('Probes', p)).toEqual([
      ['c', 'liveness', 'sh -c true', '—'],
      ['c', 'startup', 'tcp :5432', '—'],
    ]);
  });
});

describe('Volume Mounts section', () => {
  it('joins each mount to the volume source it resolves to', () => {
    // A path alone says nothing; "/etc/nginx/conf.d -> configMap app-config" is the answer.
    expect(rowsOf('Volume Mounts', deployment)).toEqual([['nginx', '/etc/nginx/conf.d', 'ro', 'configMap app-config']]);
  });
});

describe('Rollout section', () => {
  it('summarises strategy plus the replica breakdown', () => {
    expect(pairsOf('Rollout', deployment)).toMatchObject({
      Strategy: 'RollingUpdate',
      'Max surge': '1',
      'Max unavailable': '0',
      Desired: '3',
      Ready: '2',
      Unavailable: '1',
    });
  });

  it('applies only to rollout kinds', () => {
    expect(section('Rollout').applies(deployment)).toBe(true);
    expect(section('Rollout').applies({ kind: 'Pod', metadata: {}, spec: {} })).toBe(false);
  });
});

describe('Storage section', () => {
  it('shows the binding, class, modes and capacity for a PVC', () => {
    const pvc: KubeObject = {
      kind: 'PersistentVolumeClaim',
      metadata: { name: 'data' },
      spec: {
        storageClassName: 'nfs',
        accessModes: ['ReadWriteOnce'],
        volumeName: 'pv-123',
        resources: { requests: { storage: '10Gi' } },
      },
      status: { phase: 'Bound', capacity: { storage: '10Gi' } },
    };
    expect(pairsOf('Storage', pvc)).toMatchObject({
      Phase: 'Bound',
      'Storage class': 'nfs',
      'Access modes': 'ReadWriteOnce',
      Requested: '10Gi',
      Capacity: '10Gi',
      'Bound volume': 'pv-123',
    });
  });
});

describe('RBAC sections', () => {
  it('renders Role rules from the TOP-LEVEL rules field', () => {
    // Roles carry `rules` at the top level, not under spec — which is why the Ingress-shaped
    // Rules section never matched them and RBAC detail used to be blank.
    const role = {
      kind: 'Role',
      metadata: { name: 'reader' },
      rules: [{ apiGroups: [''], resources: ['pods'], verbs: ['get', 'list'] }],
    } as unknown as KubeObject;
    expect(section('RBAC Rules').applies(role)).toBe(true);
    expect(rowsOf('RBAC Rules', role)).toEqual([['core', 'pods', 'get, list']]);
  });

  it('leads a binding with the role it grants, then its subjects', () => {
    const binding = {
      kind: 'RoleBinding',
      metadata: { name: 'reader-binding' },
      roleRef: { kind: 'Role', name: 'reader' },
      subjects: [{ kind: 'ServiceAccount', name: 'app-sa', namespace: 'app' }],
    } as unknown as KubeObject;
    expect(rowsOf('Subjects', binding)).toEqual([
      ['→ Role', 'reader', '—'],
      ['ServiceAccount', 'app-sa', 'app'],
    ]);
  });
});

describe('Service fields', () => {
  it('reports a LoadBalancer address and hides a "None" session affinity', () => {
    const svc: KubeObject = {
      kind: 'Service',
      metadata: { name: 'web' },
      spec: { type: 'LoadBalancer', sessionAffinity: 'None' },
      status: { loadBalancer: { ingress: [{ ip: '192.0.2.77' }] } },
    };
    const field = (label: string) => OVERVIEW_FIELDS.find((f) => f.label === label)?.get(svc);
    expect(field('External')).toMatchObject({ text: '192.0.2.77' });
    expect(field('Session Affinity')).toBeNull();
  });
});

// ---- primary / secondary (#231) ----
// The classification a wide pane's layout depends on: what the object IS goes in the main
// column, what is merely recorded ABOUT it goes to the sidebar. Pinned here because it is a
// judgement, and a judgement that moved silently would move content between columns.
describe('section rank', () => {
  const sectionRanks = (): Record<string, SectionRank> =>
    Object.fromEntries(OVERVIEW_SECTIONS.map((s) => [s.title, rankOf(s)]));
  const fieldRanks = (): Record<string, SectionRank> =>
    Object.fromEntries(OVERVIEW_FIELDS.map((f) => [f.label, rankOf(f)]));

  it('ranks provenance secondary — and only provenance', () => {
    const secondary = OVERVIEW_SECTIONS.filter((s) => rankOf(s) === 'secondary').map((s) => s.title);
    expect(secondary).toEqual(['Labels', 'Annotations']);
  });

  it('ranks the object’s own substance primary', () => {
    const ranks = sectionRanks();
    for (const title of ['Containers', 'Ports', 'Selector', 'Rules', 'Resources', 'Probes', 'Conditions']) {
      expect(ranks[title]).toBe('primary');
    }
  });

  it('ranks the bookkeeping summary rows secondary, and the identity rows primary', () => {
    const ranks = fieldRanks();
    expect(ranks['Created']).toBe('secondary');
    expect(ranks['Managed By']).toBe('secondary');
    for (const label of ['Kind', 'Name', 'Namespace', 'Status', 'Controlled By']) {
      expect(ranks[label]).toBe('primary');
    }
  });

  it('ranks relation tables primary — they are what the object is wired to', () => {
    const [endpoints] = relationSections({
      endpoints: { items: [], truncated: false, error: null, notPermitted: false },
    });
    expect(rankOf(endpoints)).toBe('primary');
  });

  it('gives every entry one of the two ranks, so nothing is unclassified', () => {
    const all = [...Object.values(sectionRanks()), ...Object.values(fieldRanks())];
    expect(all.length).toBeGreaterThan(0);
    for (const rank of all) {
      expect(['primary', 'secondary']).toContain(rank);
    }
  });
});

// ---- The wide drawer's two columns (#232) ----
// What these pin is that the split is a consequence of the registries' own ranks rather than
// a second list of titles kept in the layout: change a `rank` in this file and the column an
// entry lands in changes with it. That is the whole reason `rankOf` is a function and the
// layout consumes it instead of matching on section titles.
describe('splitByRank', () => {
  it('sends secondary entries to the aside and everything else to the main column', () => {
    const items = [
      { id: 'a' },
      { id: 'b', rank: 'secondary' as SectionRank },
      { id: 'c', rank: 'primary' as SectionRank },
      { id: 'd', rank: 'secondary' as SectionRank },
    ];
    const { main, aside } = splitByRank(items, (i) => i);
    expect(main.map((i) => i.id)).toEqual(['a', 'c']);
    expect(aside.map((i) => i.id)).toEqual(['b', 'd']);
  });

  it('reads the rank through the accessor, so a wrapped entry needs no unwrapping first', () => {
    const rows = OVERVIEW_FIELDS.map((field) => ({ field, value: null }));
    const { main, aside } = splitByRank(rows, (r) => r.field);
    expect(aside.map((r) => r.field.label)).toEqual(['Created', 'Managed By']);
    expect(main.map((r) => r.field.label)).toContain('Controlled By');
  });

  it('puts Labels and Annotations, and only those, in the aside of the section list', () => {
    const { main, aside } = splitByRank(OVERVIEW_SECTIONS, (s) => s);
    expect(aside.map((s) => s.title)).toEqual(['Labels', 'Annotations']);
    expect(main.map((s) => s.title)).toContain('Containers');
  });

  it('keeps relation tables in the main column — the object is what it is wired to', () => {
    const rels = relationSections({
      endpoints: { items: [], truncated: false, error: null, notPermitted: false },
    });
    const { main, aside } = splitByRank(rels, (r) => r);
    expect(main.map((r) => r.title)).toEqual(['Endpoints']);
    expect(aside).toEqual([]);
  });

  it('loses nothing: every entry lands in exactly one column', () => {
    const { main, aside } = splitByRank(OVERVIEW_SECTIONS, (s) => s);
    expect(main.length + aside.length).toBe(OVERVIEW_SECTIONS.length);
    expect(main.filter((s) => aside.includes(s))).toEqual([]);
  });

  it('preserves order within a column — the wide layout moves sections, it does not sort them', () => {
    const { main } = splitByRank(OVERVIEW_SECTIONS, (s) => s);
    const expected = OVERVIEW_SECTIONS.filter((s) => rankOf(s) === 'primary').map((s) => s.title);
    expect(main.map((s) => s.title)).toEqual(expected);
  });
});

// A list payload ships ConfigMap/Secret KEYS with `null` values (GH#276); the drawer refetches
// the whole object. What is pinned here is the state in between — and, more importantly, the
// state after a refetch that FAILED, which is the only way this change could quietly lie:
// rendering a null as an empty string would say "this key's value is empty", which is a claim
// nobody made.
describe('data values that were not shipped with the list', () => {
  const dataSection = (kind: string, o: KubeObject) => {
    const s = OVERVIEW_SECTIONS.find((x) => x.title === 'Data' && x.applies({ ...o, kind }));
    if (!s) {
      throw new Error(`no applicable Data section for ${kind}`);
    }
    return s;
  };

  it('renders a not-yet-loaded ConfigMap value as a dash, never as empty', () => {
    const o: KubeObject = { kind: 'ConfigMap', metadata: { name: 'cm' }, data: { a: null, b: '' } } as KubeObject;
    const body = dataSection('ConfigMap', o).body(o);
    expect(body.type).toBe('table');
    if (body.type !== 'table') {
      return;
    }
    expect(body.rows.map((r) => r.map((c) => c.text))).toEqual([
      ['a', '—'],
      ['b', ''],
    ]);
  });

  it('still counts the keys, so the section header matches the list column', () => {
    const o: KubeObject = { kind: 'Secret', metadata: { name: 's' }, data: { a: null, b: null } } as KubeObject;
    expect(dataSection('Secret', o).count?.(o)).toBe(2);
    expect(dataSection('Secret', o).applies(o)).toBe(true);
  });

  it('masks, decodes, or admits it does not have the value', () => {
    expect(secretValueText('YWRtaW4=', false)).toBe('••••••••');
    expect(secretValueText('YWRtaW4=', true)).toBe('admin');
    expect(secretValueText(null, true)).toBe('—');
    expect(secretValueText(null, false)).toBe('—');
  });

  it('truncates a long ConfigMap value but leaves a short one alone', () => {
    expect(dataValueText('x'.repeat(250))).toBe('x'.repeat(200) + '…');
    expect(dataValueText('short')).toBe('short');
    expect(dataValueText(null)).toBe('—');
  });
});
