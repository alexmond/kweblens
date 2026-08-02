import { describe, expect, it } from 'vitest';

import type { KubeObject } from '../types';
import type { SectionRank } from './overview';
import { OVERVIEW_FIELDS, OVERVIEW_SECTIONS, rankOf } from './overview';
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
