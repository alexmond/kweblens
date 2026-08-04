/**
 * How big is an object, really — per kind, against whatever cluster is registered.
 *
 * Exists because the answer was got badly wrong once and cost a whole planning pass.
 * `docs/design/scale-measurements.md` concluded "the API is comfortable, ~88 bytes/row"
 * from numbers taken against the built-in simulator, and the real cluster turned out to be
 * ~8 KB per pod and ~43 KB per secret — 50-500x out. The measurement itself was fine; the
 * RIG was not, and nothing in the loop compared the two. This script is that comparison,
 * so "the simulator is representative" is a claim someone can re-check in a minute rather
 * than a thing the last person believed.
 *
 * It reports two different numbers per kind, and the difference between them matters:
 *
 *   list bytes/row   the projected list payload (`/objects`) divided by its row count —
 *                    what the browser actually pulls. Post-#276 this is missing
 *                    `managedFields` and every ConfigMap/Secret VALUE, so it is a measure
 *                    of the wire, not of the object.
 *   object bytes     the full single-object payload (`/object`), sampled. This is the
 *                    object as the cluster stores it, and the one to compare across rigs.
 *
 * Distribution, not just the mean: p50/p90/max are printed because a rig where every
 * object is the mean cannot reproduce the failures that matter (the largest Secret on the
 * live cluster is 948 KB against a 43 KB average). `mf%` is the managedFields share, the
 * single most reliable tell that a generated object is not a real one.
 *
 * Usage:
 *   node scripts/payload-bytes.mjs                       # every kind below, cluster auto-detected
 *   PORT=8131 node scripts/payload-bytes.mjs             # a specific instance
 *   CLUSTER=sim KINDS=pods,secrets node scripts/payload-bytes.mjs
 *   SAMPLE=100 node scripts/payload-bytes.mjs            # sample more objects per kind
 *   JSON=1 node scripts/payload-bytes.mjs                # machine-readable, for diffing runs
 *
 * Env: PORT (8080) | BASE_URL | CLUSTER (first registered) | KINDS | NAMESPACE | SAMPLE (60)
 *
 * No auth: every endpoint it touches is a GET, which open-mode leaves public.
 */

const BASE = (process.env.BASE_URL ?? `http://localhost:${process.env.PORT ?? 8080}/`).replace(/\/$/, '');
const SAMPLE = Number(process.env.SAMPLE ?? 60);
const NAMESPACE = process.env.NAMESPACE ?? '';
const AS_JSON = !!process.env.JSON;

const DEFAULT_KINDS = [
  'pods',
  'deployments',
  'replicasets',
  'services',
  'configmaps',
  'secrets',
  'ingresses',
  'nodes',
  'events',
];

const KINDS = (process.env.KINDS ?? DEFAULT_KINDS.join(','))
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

/** Body bytes, not characters: a UTF-8 payload is what crosses the wire. */
async function fetchBytes(url) {
  const res = await fetch(url);
  if (!res.ok) return { ok: false, status: res.status, bytes: 0, text: '' };
  const text = await res.text();
  return { ok: true, status: res.status, bytes: Buffer.byteLength(text, 'utf8'), text };
}

function stat(values) {
  if (!values.length) return { n: 0 };
  const s = [...values].sort((a, b) => a - b);
  const at = (q) => s[Math.min(s.length - 1, Math.floor(q * s.length))];
  return {
    n: s.length,
    mean: Math.round(s.reduce((a, b) => a + b, 0) / s.length),
    p50: at(0.5),
    p90: at(0.9),
    max: s[s.length - 1],
    min: s[0],
  };
}

const sizeOf = (v) => (v === undefined || v === null ? 0 : Buffer.byteLength(JSON.stringify(v), 'utf8'));

async function cluster() {
  if (process.env.CLUSTER) return process.env.CLUSTER;
  const res = await fetch(`${BASE}/api/v1/clusters`);
  const list = await res.json();
  if (!list.length) throw new Error('no clusters registered');
  return list[0].id ?? list[0].name;
}

/**
 * Sample evenly across the list rather than taking the first N. Real clusters group their
 * big objects (one namespace's Helm release secrets, say), so the head of a list is not a
 * sample of it — taking the first 60 rows of `secrets` on the live cluster missed every
 * large one.
 */
function spread(rows, n) {
  if (rows.length <= n) return rows;
  const stride = rows.length / n;
  return Array.from({ length: n }, (_, i) => rows[Math.floor(i * stride)]);
}

async function measureKind(clusterId, kind) {
  const nsParam = NAMESPACE ? `?namespace=${encodeURIComponent(NAMESPACE)}` : '';
  const list = await fetchBytes(`${BASE}/api/v1/clusters/${clusterId}/resources/${kind}/objects${nsParam}`);
  if (!list.ok) return { kind, error: `list HTTP ${list.status}` };
  let rows;
  try {
    rows = JSON.parse(list.text);
  } catch {
    return { kind, error: 'list body was not JSON' };
  }
  if (!rows.length) return { kind, objects: 0, listBytes: list.bytes, note: 'empty' };

  const objectBytes = [];
  const mfBytes = [];
  const dataBytes = [];
  const annBytes = [];
  for (const row of spread(rows, SAMPLE)) {
    const md = row.metadata ?? {};
    const q = new URLSearchParams({ name: md.name ?? '' });
    if (md.namespace) q.set('namespace', md.namespace);
    const one = await fetchBytes(`${BASE}/api/v1/clusters/${clusterId}/resources/${kind}/object?${q}`);
    if (!one.ok) continue;
    objectBytes.push(one.bytes);
    try {
      const obj = JSON.parse(one.text);
      mfBytes.push(sizeOf(obj.metadata?.managedFields));
      annBytes.push(sizeOf(obj.metadata?.annotations));
      dataBytes.push(sizeOf(obj.data) + sizeOf(obj.stringData) + sizeOf(obj.binaryData));
    } catch {
      /* counted in bytes; shape stats just skip it */
    }
  }

  const obj = stat(objectBytes);
  const total = objectBytes.reduce((a, b) => a + b, 0) || 1;
  return {
    kind,
    objects: rows.length,
    listBytes: list.bytes,
    listBytesPerRow: Math.round(list.bytes / rows.length),
    object: obj,
    managedFieldsPct: Math.round((mfBytes.reduce((a, b) => a + b, 0) / total) * 100),
    annotationsPct: Math.round((annBytes.reduce((a, b) => a + b, 0) / total) * 100),
    dataPct: Math.round((dataBytes.reduce((a, b) => a + b, 0) / total) * 100),
  };
}

const kb = (n) => (n >= 1024 ? `${(n / 1024).toFixed(1)}K` : String(n));
const pad = (s, w) => String(s).padStart(w);

async function main() {
  const clusterId = await cluster();
  const results = [];
  for (const kind of KINDS) results.push(await measureKind(clusterId, kind));

  if (AS_JSON) {
    console.log(JSON.stringify({ base: BASE, cluster: clusterId, sample: SAMPLE, results }, null, 2));
    return;
  }

  console.log(`\npayload bytes — ${BASE}  cluster=${clusterId}  sample=${SAMPLE}/kind\n`);
  console.log(
    ['kind'.padEnd(14), pad('objs', 6), pad('list KB', 9), pad('B/row', 8), pad('obj mean', 9), pad('p50', 8), pad('p90', 8), pad('max', 9), pad('mf%', 5), pad('ann%', 5), pad('data%', 6)].join(' '),
  );
  console.log('-'.repeat(96));
  for (const r of results) {
    if (r.error || !r.objects) {
      console.log(`${r.kind.padEnd(14)} ${pad(r.objects ?? 0, 6)}   ${r.error ?? r.note ?? ''}`);
      continue;
    }
    console.log(
      [
        r.kind.padEnd(14),
        pad(r.objects, 6),
        pad((r.listBytes / 1024).toFixed(1), 9),
        pad(r.listBytesPerRow, 8),
        pad(kb(r.object.mean), 9),
        pad(kb(r.object.p50), 8),
        pad(kb(r.object.p90), 8),
        pad(kb(r.object.max), 9),
        pad(r.managedFieldsPct, 5),
        pad(r.annotationsPct, 5),
        pad(r.dataPct, 6),
      ].join(' '),
    );
  }
  console.log('');
}

main().catch((err) => {
  console.error(err.message ?? err);
  process.exit(1);
});
