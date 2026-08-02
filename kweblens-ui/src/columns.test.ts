import { describe, expect, it } from 'vitest';

import {
  age,
  badgeTone,
  columnsFor,
  defaultHiddenCols,
  eventTypeTone,
  printerColumnDefs,
  readyTone,
  statusTone,
} from './columns';
import { toneFor } from './table';
import type { KubeObject, PrinterColumn } from './types';

describe('statusTone', () => {
  it('classifies status vocab into tones', () => {
    expect(statusTone('Running')).toBe('ok');
    expect(statusTone('Pending')).toBe('warn');
    expect(statusTone('CrashLoopBackOff')).toBe('err');
    expect(statusTone('')).toBe('');
    expect(statusTone('—')).toBe('');
    expect(statusTone('Whatever')).toBe('');
  });
});

describe('readyTone', () => {
  it('classifies ready ratios', () => {
    expect(readyTone('2/2')).toBe('ok');
    expect(readyTone('0/2')).toBe('err');
    expect(readyTone('1/2')).toBe('warn');
    expect(readyTone('0/0')).toBe('');
    expect(readyTone('not-a-ratio')).toBe('');
  });
});

describe('age', () => {
  const ago = (ms: number) => new Date(Date.now() - ms).toISOString();

  it('formats an ISO timestamp into a compact age', () => {
    expect(age(undefined)).toBe('—');
    expect(age('not-a-date')).toBe('—');
    expect(age(ago(5_000))).toMatch(/^\d+s$/);
    expect(age(ago(5 * 60_000))).toBe('5m');
    expect(age(ago(3 * 3_600_000))).toBe('3h');
    expect(age(ago(2 * 86_400_000))).toBe('2d');
  });
});

describe('columnsFor', () => {
  const pod: KubeObject = {
    kind: 'Pod',
    metadata: { name: 'p', namespace: 'default', creationTimestamp: new Date().toISOString() },
    spec: { nodeName: 'node-1', containers: [{ name: 'c' }], ports: [{ port: 80, protocol: 'TCP' }] },
    status: { phase: 'Running', containerStatuses: [{ name: 'c', ready: true, restartCount: 2 }] },
  };

  it('returns column defs whose render() never throws, across kinds', () => {
    expect(columnsFor('pods').length).toBeGreaterThan(0);
    const kinds = [
      'pods',
      'deployments',
      'statefulsets',
      'daemonsets',
      'replicasets',
      'jobs',
      'cronjobs',
      'nodes',
      'services',
      'ingresses',
      'configmaps',
      'secrets',
      'namespaces',
      'persistentvolumeclaims',
      'persistentvolumes',
      'storageclasses',
      'events',
      'customresourcedefinitions',
    ];
    for (const id of kinds) {
      for (const c of columnsFor(id)) {
        expect(() => c.render(pod)).not.toThrow();
      }
    }
  });

  it('returns [] for an unknown kind', () => {
    expect(columnsFor('does-not-exist')).toEqual([]);
  });
});

describe('printerColumnDefs', () => {
  it('builds defs and resolves simple + filtered json paths', () => {
    const cols: PrinterColumn[] = [
      { name: 'Phase', type: 'string', jsonPath: '.status.phase' },
      { name: 'Age', type: 'date', jsonPath: '.metadata.creationTimestamp' },
      { name: 'Ready', type: 'string', jsonPath: '.status.conditions[?(@.type == "Ready")].status' },
    ];
    const defs = printerColumnDefs(cols);
    expect(defs.map((d) => d.header)).toEqual(['Phase', 'Age', 'Ready']);
    expect(defs[0].render({ status: { phase: 'Running' } })).toBe('Running');
    expect(defs[0].render({})).toBe('—');
    expect(defs[1].render({ metadata: { creationTimestamp: new Date().toISOString() } })).toMatch(/^\d+[smhd]$/);
    expect(defs[2].render({ status: { conditions: [{ type: 'Ready', status: 'True' }] } })).toBe('True');
  });
});

describe('node columns', () => {
  const node: KubeObject = {
    kind: 'Node',
    metadata: {
      name: 'k8s-1',
      labels: {
        'node-role.kubernetes.io/control-plane': 'true',
        'node.kubernetes.io/instance-type': 'm5.large',
        'topology.kubernetes.io/zone': 'us-east-1a',
      },
    },
    spec: { unschedulable: true, taints: [{ key: 'a' }, { key: 'b' }] },
    status: {
      capacity: { cpu: '4', memory: '16110544Ki', pods: '110' },
      addresses: [
        { type: 'InternalIP', address: '192.0.2.10' },
        { type: 'ExternalIP', address: '198.51.100.7' },
      ],
      conditions: [
        { type: 'MemoryPressure', status: 'False' },
        { type: 'EtcdIsVoter', status: 'True' },
        { type: 'Ready', status: 'True' },
      ],
      nodeInfo: {
        kubeletVersion: 'v1.35.6+k3s1',
        osImage: 'Ubuntu 24.04',
        kernelVersion: '6.8.0',
        containerRuntimeVersion: 'containerd://2.0.0',
        architecture: 'amd64',
      },
    },
  };
  const render = (key: string, o: KubeObject = node) =>
    columnsFor('nodes')
      .find((c) => c.key === key)
      ?.render(o);

  it('renders taints, schedulable and the True conditions', () => {
    expect(render('taints')).toBe('2');
    expect(render('schedulable')).toBe('False'); // spec.unschedulable → cordoned
    expect(render('conditions')).toBe('EtcdIsVoter, Ready'); // False ones omitted
  });

  it('renders addresses, capacity and node info', () => {
    expect(render('ip')).toBe('192.0.2.10');
    expect(render('ext-ip')).toBe('198.51.100.7');
    expect(render('pod-capacity')).toBe('110');
    expect(render('capacity')).toBe('4 CPU, 15.4Gi');
    expect(render('os-image')).toBe('Ubuntu 24.04');
    expect(render('runtime')).toBe('containerd://2.0.0');
    expect(render('arch')).toBe('amd64');
    expect(render('instance-type')).toBe('m5.large');
    expect(render('zone')).toBe('us-east-1a');
  });

  it('falls back to a dash on a bare node', () => {
    const bare: KubeObject = { kind: 'Node', metadata: { name: 'n' } };
    expect(render('taints', bare)).toBe('0');
    expect(render('schedulable', bare)).toBe('True');
    expect(render('conditions', bare)).toBe('—');
    expect(render('ext-ip', bare)).toBe('—');
    expect(render('instance-type', bare)).toBe('—');
    expect(render('capacity', bare)).toBe('—');
  });

  it('starts the detail-heavy columns hidden but offers them in the picker', () => {
    const hidden = defaultHiddenCols('nodes');
    expect(hidden.has('ext-ip')).toBe(true);
    expect(hidden.has('os-image')).toBe(true);
    // the common set stays visible
    expect(hidden.has('taints')).toBe(false);
    expect(hidden.has('conditions')).toBe(false);
    expect(defaultHiddenCols('pods').size).toBe(0);
    expect(defaultHiddenCols(undefined).size).toBe(0);
  });
});

describe('eventTypeTone', () => {
  it('colours a Warning amber, not red', () => {
    // A Warning means "something notable happened", and many are routine. Red would put the
    // loudest tone on the most common non-Normal row, which is how a list stops being scannable.
    expect(eventTypeTone('Warning')).toBe('warn');
  });

  it('classifies Normal as ok, and leaves the badging decision to badgeTone', () => {
    // Normal says what Running says, so it classifies the same way. It ends up unbadged
    // because of the shared convention, not because of a special case hidden in here.
    expect(eventTypeTone('Normal')).toBe('ok');
    expect(eventTypeTone('')).toBe('');
  });
});

describe('badgeTone', () => {
  it('badges exceptions and leaves the ordinary case plain', () => {
    // The one convention (#240): a pill marks something worth looking at. `ok` is the state
    // nearly every row is in on a healthy cluster, so a pill there marks nothing.
    expect(badgeTone('err')).toBe('err');
    expect(badgeTone('warn')).toBe('warn');
    expect(badgeTone('ok')).toBe('');
    expect(badgeTone('')).toBe('');
  });

  it('applies to Pods and Events identically — the point of the change', () => {
    // Before, these two disagreed: statusTone badged everything, eventTypeTone badged only
    // Warning. The same value class now renders the same way in both tables.
    expect(badgeTone(statusTone('Running'))).toBe('');
    expect(badgeTone(eventTypeTone('Normal'))).toBe('');
    expect(badgeTone(statusTone('CrashLoopBackOff'))).toBe('err');
    expect(badgeTone(eventTypeTone('Warning'))).toBe('warn');
  });
});

describe('toneFor', () => {
  it('does not colour the Type column of other kinds', () => {
    // `type` is also a column on Services and Secrets, and toneFor sees only the column key —
    // not the kind. Matching the exact word is what keeps ClusterIP and Opaque uncoloured.
    expect(toneFor('type', 'ClusterIP')).toBe('');
    expect(toneFor('type', 'LoadBalancer')).toBe('');
    expect(toneFor('type', 'kubernetes.io/tls')).toBe('');
    expect(toneFor('type', 'Warning')).toBe('warn');
  });

  it('classifies a ready ratio, which is not keyword-based', () => {
    // This tone is the one that used to be computed and then thrown away: StatusBadge
    // re-derived colour from the TEXT, and '1/3' matches no status keyword, so the Ready
    // column was badged and rendered plain.
    expect(toneFor('ready', '1/3')).toBe('warn');
    expect(toneFor('ready', '0/3')).toBe('err');
  });

  it('badges only exceptions, in every column it handles', () => {
    // The single convention, at the one place the table asks for a cell's tone.
    expect(toneFor('status', 'Running')).toBe('');
    expect(toneFor('status', 'Pending')).toBe('warn');
    expect(toneFor('status', 'CrashLoopBackOff')).toBe('err');
    expect(toneFor('ready', '3/3')).toBe('');
    expect(toneFor('type', 'Normal')).toBe('');
    expect(toneFor('type', 'Warning')).toBe('warn');
  });

  it('gives an unclassified column no tone', () => {
    expect(toneFor('message', 'anything')).toBe('');
  });
});
