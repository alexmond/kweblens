/**
 * Turns the cluster's own JSON Schema into form fields.
 *
 * The point of generating rather than hand-writing: a per-kind form is UI to maintain, it
 * drifts from what the cluster actually accepts, and it never covers CRDs. Reading the
 * schema the cluster serves means the field types, the enum values and the help text all
 * come from the same place the API server validates against, so the form cannot disagree
 * with it.
 *
 * What the schema CANNOT tell us is which fields are safe to expose as a form. "Editable"
 * is a judgement about blast radius and about whether a form can represent the field
 * without lying — nested boolean affinity rules flatten into something misleading, and a
 * volume rename has to change two places at once. So the paths are an ALLOWLIST
 * (see {@link CURATED}); the schema supplies the type, not the decision.
 */

/** A JSON-Schema node, loosely typed — we only read the handful of keywords below. */
type Node = Record<string, unknown>;

/** Not exported: consumers read `FormField.type`, and knip rightly flags an export nobody imports. */
type FieldType = 'string' | 'integer' | 'boolean' | 'enum';

export interface FormField {
  /** Dotted path into the object, with numeric segments for array indices. */
  path: string;
  label: string;
  type: FieldType;
  /** The schema's own description, trimmed to something a label can carry. */
  description?: string;
  /** Present for `enum` — the values the API server will accept. */
  options?: string[];
  /** Rendered read-only. Offering an immutable field is a promise the API server refuses. */
  readOnly?: boolean;
  /** Why it is read-only. Shown to the user: a disabled field with no reason is just broken. */
  readOnlyReason?: string;
}

interface Curated {
  path: string;
  label: string;
  /** Set when the field cannot be changed after create; carries the reason shown to the user. */
  immutable?: string;
}

const IMMUTABLE_SELECTOR = 'The pod selector is immutable after create — the API server rejects a change.';
const IMMUTABLE_CLUSTER_IP = 'clusterIP is assigned by the cluster and immutable after create.';

/**
 * The allowlist, per kind. Deliberately small and scalar.
 *
 * Absent by design (see docs/design/detail-sections-audit.md): affinity and topology
 * spread, which are nested boolean logic a form flattens into a lie; volumes and
 * volumeMounts, where a rename must change both and editing one is a footgun; init
 * container ordering; and anything under `status`, which is the controller's to write.
 */
const CURATED: Record<string, Curated[]> = {
  Deployment: [
    { path: 'spec.replicas', label: 'Replicas' },
    { path: 'spec.paused', label: 'Paused' },
    { path: 'spec.minReadySeconds', label: 'Min ready seconds' },
    { path: 'spec.progressDeadlineSeconds', label: 'Progress deadline (s)' },
    { path: 'spec.revisionHistoryLimit', label: 'Revision history limit' },
    { path: 'spec.selector', label: 'Selector', immutable: IMMUTABLE_SELECTOR },
  ],
  StatefulSet: [
    { path: 'spec.replicas', label: 'Replicas' },
    { path: 'spec.minReadySeconds', label: 'Min ready seconds' },
    { path: 'spec.revisionHistoryLimit', label: 'Revision history limit' },
    { path: 'spec.selector', label: 'Selector', immutable: IMMUTABLE_SELECTOR },
  ],
  ReplicaSet: [
    { path: 'spec.replicas', label: 'Replicas' },
    { path: 'spec.minReadySeconds', label: 'Min ready seconds' },
    { path: 'spec.selector', label: 'Selector', immutable: IMMUTABLE_SELECTOR },
  ],
  DaemonSet: [
    { path: 'spec.minReadySeconds', label: 'Min ready seconds' },
    { path: 'spec.revisionHistoryLimit', label: 'Revision history limit' },
  ],
  CronJob: [
    { path: 'spec.schedule', label: 'Schedule' },
    { path: 'spec.suspend', label: 'Suspended' },
    { path: 'spec.concurrencyPolicy', label: 'Concurrency policy' },
    { path: 'spec.startingDeadlineSeconds', label: 'Starting deadline (s)' },
    { path: 'spec.successfulJobsHistoryLimit', label: 'Successful job history' },
    { path: 'spec.failedJobsHistoryLimit', label: 'Failed job history' },
  ],
  Job: [
    { path: 'spec.parallelism', label: 'Parallelism' },
    { path: 'spec.completions', label: 'Completions' },
    { path: 'spec.backoffLimit', label: 'Backoff limit' },
    { path: 'spec.activeDeadlineSeconds', label: 'Active deadline (s)' },
    { path: 'spec.suspend', label: 'Suspended' },
  ],
  Service: [
    { path: 'spec.type', label: 'Type' },
    { path: 'spec.sessionAffinity', label: 'Session affinity' },
    { path: 'spec.externalTrafficPolicy', label: 'External traffic policy' },
    { path: 'spec.clusterIP', label: 'Cluster IP', immutable: IMMUTABLE_CLUSTER_IP },
  ],
  Ingress: [{ path: 'spec.ingressClassName', label: 'Ingress class' }],
  PersistentVolumeClaim: [
    {
      path: 'spec.storageClassName',
      label: 'Storage class',
      immutable: 'The storage class is immutable after create.',
    },
  ],
};

/** Kinds whose pod template's container images are worth offering — the "deploy a new version" edit. */
const POD_TEMPLATE_KINDS = ['Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet', 'Job'];

/** How many generically-discovered fields to show for a kind with no curated list. */
const MAX_DISCOVERED = 30;

/** Follow `$ref` and the `allOf: [{$ref}]` form the Kubernetes schema uses for object fields. */
export function resolveNode(root: Node, node: Node | undefined): Node | undefined {
  if (!node) {
    return undefined;
  }
  const ref = typeof node.$ref === 'string' ? node.$ref : refFromAllOf(node);
  if (!ref) {
    return node;
  }
  const defs = (root.definitions ?? {}) as Record<string, Node>;
  const target = defs[ref.split('/').pop() ?? ''];
  // Resolve one more level: a definition can itself be a bare $ref.
  return target ? resolveNode(root, target) : undefined;
}

function refFromAllOf(node: Node): string | null {
  const all = node.allOf;
  if (!Array.isArray(all) || all.length === 0) {
    return null;
  }
  const first = all[0] as Node;
  return typeof first?.$ref === 'string' ? first.$ref : null;
}

/**
 * The schema node at a dotted path, resolving refs as it walks. Numeric segments step into
 * an array's `items`, so `…containers.0.image` resolves through the Container definition.
 */
export function schemaAt(root: Node, path: string): Node | undefined {
  let node: Node | undefined = resolveNode(root, root);
  for (const segment of path.split('.')) {
    if (!node) {
      return undefined;
    }
    if (/^\d+$/.test(segment)) {
      node = resolveNode(root, node.items as Node | undefined);
      continue;
    }
    const props = (node.properties ?? {}) as Record<string, Node>;
    node = resolveNode(root, props[segment]);
  }
  return node;
}

/** Descriptions in the Kubernetes schema are paragraphs with URLs; a label needs a sentence. */
export function shortDescription(node: Node | undefined): string | undefined {
  const raw = typeof node?.description === 'string' ? node.description : '';
  if (!raw) {
    return undefined;
  }
  // Sliced rather than regex-trimmed: a `.*$` after an optional-whitespace prefix backtracks
  // badly on the long paragraphs the Kubernetes schema carries.
  const moreInfo = raw.search(/more info:/i);
  const head = moreInfo >= 0 ? raw.slice(0, moreInfo) : raw;
  // Keep the terminating period: splitting on '. ' would eat it and leave a hint that reads
  // like a truncation.
  const stop = head.indexOf('. ');
  const firstSentence = (stop >= 0 ? head.slice(0, stop + 1) : head).trim();
  const cleaned = firstSentence.replace(/https?:\/\/\S+/g, '').trim();
  return cleaned.length > 160 ? `${cleaned.slice(0, 157)}…` : cleaned || undefined;
}

function fieldType(node: Node): FieldType | null {
  if (Array.isArray(node.enum) && node.enum.length > 0) {
    return 'enum';
  }
  const type = node.type;
  if (type === 'boolean') {
    return 'boolean';
  }
  if (type === 'integer' || type === 'number') {
    return 'integer';
  }
  return type === 'string' ? 'string' : null;
}

function toField(root: Node, spec: Curated): FormField | null {
  const node = schemaAt(root, spec.path);
  if (!node) {
    return null;
  }
  // An immutable field is shown even when it is an object (a selector), because the point
  // is to display it with its reason rather than to edit it.
  const type = fieldType(node) ?? (spec.immutable ? 'string' : null);
  if (!type) {
    return null;
  }
  return {
    path: spec.path,
    label: spec.label,
    type,
    description: shortDescription(node),
    options: type === 'enum' ? (node.enum as string[]) : undefined,
    readOnly: Boolean(spec.immutable),
    readOnlyReason: spec.immutable,
  };
}

/**
 * Container image fields, one per container that exists on the object.
 *
 * Indexed rather than modelled as a list editor: changing an image is the common,
 * high-value edit ("deploy this version"), while adding or removing a container is a
 * structural change better made in YAML where the whole shape is visible.
 */
function containerImageFields(root: Node, kind: string, containers: { name?: string }[]): FormField[] {
  if (!POD_TEMPLATE_KINDS.includes(kind)) {
    return [];
  }
  const base = 'spec.template.spec.containers';
  return containers.map((container, i) => {
    const node = schemaAt(root, `${base}.${i}.image`);
    return {
      path: `${base}.${i}.image`,
      label: container.name ? `Image · ${container.name}` : `Image · container ${i + 1}`,
      type: 'string' as FieldType,
      description: shortDescription(node),
    };
  });
}

/**
 * Scalar properties directly under `spec`, for a kind with no curated list — i.e. custom
 * resources.
 *
 * Depth-one and scalars only, on purpose. A CRD's spec is usually shallow configuration, and
 * going deeper would start surfacing structure this form cannot represent honestly. It is the
 * only way to cover custom resources at all: an allowlist by definition cannot know them.
 */
function discoveredFields(root: Node): FormField[] {
  const spec = schemaAt(root, 'spec');
  const props = (spec?.properties ?? {}) as Record<string, Node>;
  const out: FormField[] = [];
  for (const [name, raw] of Object.entries(props)) {
    const node = resolveNode(root, raw);
    const type = node ? fieldType(node) : null;
    if (!node || !type) {
      continue;
    }
    out.push({
      path: `spec.${name}`,
      label: name,
      type,
      description: shortDescription(node),
      options: type === 'enum' ? (node.enum as string[]) : undefined,
    });
    if (out.length >= MAX_DISCOVERED) {
      break;
    }
  }
  return out;
}

/**
 * The fields to render for one object.
 *
 * Returns nothing rather than guessing when there is no schema: the whole point is that the
 * form agrees with what the cluster accepts, and a form built on assumptions would be worse
 * than the YAML tab it sits beside.
 */
export function formFieldsFor(
  kind: string,
  schema: Node | null | undefined,
  containers: { name?: string }[] = [],
): FormField[] {
  if (!schema) {
    return [];
  }
  const curated = CURATED[kind];
  if (!curated) {
    return discoveredFields(schema);
  }
  const fields = curated.map((spec) => toField(schema, spec)).filter((f): f is FormField => f !== null);
  return [...fields, ...containerImageFields(schema, kind, containers)];
}

/** Whether this kind has hand-picked fields, as opposed to generically discovered ones. */
export function isCurated(kind: string): boolean {
  return kind in CURATED;
}
