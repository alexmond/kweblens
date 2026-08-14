import { describe, expect, it } from 'vitest';

import {
  age,
  badgeTone,
  columnKinds,
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
    // Schedulable joined them in #357: the Status column's state already carries
    // `,SchedulingDisabled`, so on by default it was the same fact in two columns.
    expect(hidden.has('schedulable')).toBe(true);
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

  it('takes the SERVER’s tone when the row carries one', () => {
    // The Status column renders `kweblensState.label`, so classifying it by keyword would be a
    // second opinion about a state the server already judged — the drift GH#336 removed from
    // the counts, one colour further on. `Completed` is the case that proves it: the keyword
    // table reads "complete" as ok, the verdict behind it says idle, and the card is muted.
    const row = (label: string, tone: 'ok' | 'warn' | 'err' | 'idle'): KubeObject => ({
      kind: 'Pod',
      kweblensState: { label, tone },
    });
    expect(toneFor('status', 'Unavailable', row('Unavailable', 'err'))).toBe('err');
    expect(toneFor('status', 'Pending', row('Pending', 'warn'))).toBe('warn');
    // ok and idle both render as plain text — badgeTone's convention, unchanged: a pill marks
    // an exception, and neither "healthy" nor "finished" is one.
    expect(toneFor('status', 'Running', row('Running', 'ok'))).toBe('');
    expect(toneFor('status', 'Completed', row('Completed', 'idle'))).toBe('');
    // A state the keyword table would have called an error, that the server calls idle.
    expect(statusTone('Failed')).toBe('err');
    expect(toneFor('status', 'Failed', row('Failed', 'idle'))).toBe('');
  });

  it('falls back to the keyword table for a row with no state', () => {
    // A PersistentVolume's phase and a CRD's own printer column are Status cells nothing has
    // judged, so they still need classifying — and so does any row whose context-carrying
    // verdict could not be reached this request.
    const pv: KubeObject = { kind: 'PersistentVolume', metadata: { name: 'pv' } };
    expect(toneFor('status', 'Failed', pv)).toBe('err');
    expect(toneFor('status', 'Bound', pv)).toBe('');
  });
});

describe('the Status column of a judged kind is the server’s state', () => {
  // THE RULE, and it is the whole of it: if the server judges the kind, its Status column renders
  // `kweblensState` — never `status.phase`, never a local read of `status.conditions`.
  //
  // The version of this block that shipped with #341 pinned SEVEN kinds and called the other six
  // "not covered by the vocabulary yet". That was true the day it was written. #339 then gave the
  // server a verdict for Node and Namespace and #340 gave it one for Service, PVC, ConfigMap and
  // Secret — and because this test pinned the split rather than the rule, the gate stayed green
  // over six kinds showing a second opinion for two whole tickets (GH#357). So it is now written
  // in both directions: what MUST read the state, and what must NOT — with a decoy in the second
  // set, so converting the wrong kind fails just as loudly as failing to convert the right one.
  //
  // Kept in step with `StatusVocabulary.covers() || needsContext()` on the server. There is no way
  // to derive it from here, so it is a list; what stops it going stale silently is that both
  // directions are asserted and neither has a default.
  const SERVER_JUDGES = [
    // WorkloadHealth
    'pods',
    'deployments',
    'statefulsets',
    'daemonsets',
    'replicasets',
    'jobs',
    'cronjobs',
    // ClusterObjectHealth (#339)
    'nodes',
    'namespaces',
    // the context-carrying kinds (#340)
    'services',
    'persistentvolumeclaims',
    'configmaps',
    'secrets',
  ];

  // Kinds with a Status column the server does NOT judge, each for a stated reason — the decoy
  // half of the control. A PersistentVolume has no producer at all (StorageHealthService judges
  // the CLAIM), so `kweblensState` never ships for one and this column is the cluster's own phase.
  // Wiring `serverState` in here would give every PV row a `—`, and this test says so.
  const NOT_JUDGED_WITH_A_STATUS_COLUMN = ['persistentvolumes'];

  // Carries a state AND a conflicting phase, so which one comes out is not ambiguous. This is the
  // mutation detector: every kind is rendered against it, and a column that reads the object
  // instead of the verdict returns the other word.
  const conflicted: KubeObject = {
    kind: 'Pod',
    metadata: { name: 'p' },
    status: {
      phase: 'Succeeded',
      // What the old hand-rolled Nodes column read. A cordoned-but-healthy node is the case it
      // could not express: it said `Ready` where the server says `Ready,SchedulingDisabled`.
      conditions: [{ type: 'Ready', status: 'True' }],
    },
    kweblensState: { label: 'Completed', tone: 'idle' },
  };

  const statusOf = (id: string, o: KubeObject): string | undefined =>
    columnsFor(id)
      .find((c) => c.key === 'status')
      ?.render(o);

  it('gives every judged kind a Status column that renders the state', () => {
    for (const id of SERVER_JUDGES) {
      expect(statusOf(id, conflicted), id).toBe('Completed');
    }
  });

  it('renders the state, not the phase and not the conditions', () => {
    expect(statusOf('pods', conflicted)).not.toBe('Succeeded');
    expect(statusOf('nodes', conflicted)).not.toBe('Ready');
    expect(statusOf('namespaces', conflicted)).not.toBe('Succeeded');
    expect(statusOf('persistentvolumeclaims', conflicted)).not.toBe('Succeeded');
  });

  it('renders — when the server reached no verdict, rather than guessing from the object', () => {
    // "We did not send it" and "it is fine" are different claims, and the second one would be
    // invented here. Same rule as ListProjection's withheld values. This is also what a failed
    // StatusContext looks like on a Service or a ConfigMap: unjudged, not judged well.
    const unjudged: KubeObject = { kind: 'Pod', status: { phase: 'Running', conditions: [{ type: 'Ready' }] } };
    for (const id of SERVER_JUDGES) {
      expect(statusOf(id, unjudged), id).toBe('—');
    }
  });

  it('leaves a kind the server does not judge on the cluster’s own value', () => {
    // The decoy. A PV's phase is not a second opinion about a verdict — there is no verdict.
    for (const id of NOT_JUDGED_WITH_A_STATUS_COLUMN) {
      expect(statusOf(id, conflicted), id).toBe('Succeeded');
    }
  });

  it('accounts for every Status column in the app', () => {
    // The one that catches a kind added later. A new Status column is either the server's state
    // or an explicit exemption; there is no third bucket to quietly land in.
    const withStatus = columnKinds().filter((id) => columnsFor(id).some((c) => c.key === 'status'));
    expect([...withStatus].sort()).toEqual([...SERVER_JUDGES, ...NOT_JUDGED_WITH_A_STATUS_COLUMN].sort());
  });
});
