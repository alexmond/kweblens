import { describe, expect, it } from 'vitest';

import { formFieldsFor, isCurated, resolveNode, schemaAt, shortDescription } from './schemaForm';

// The Kubernetes schema does not inline object properties — it puts them in `definitions` and
// points at them with `allOf: [{$ref}]`. Everything here depends on following that, so the
// fixture reproduces the real shape rather than a flattened convenience version.
const K8S_SCHEMA = {
  properties: {
    spec: { description: 'Spec.', allOf: [{ $ref: '#/definitions/DeploymentSpec' }] },
    status: { allOf: [{ $ref: '#/definitions/DeploymentStatus' }] },
  },
  definitions: {
    DeploymentSpec: {
      properties: {
        replicas: {
          type: 'integer',
          description: 'Number of desired pods. This is a pointer to distinguish between zero and unset.',
        },
        paused: { type: 'boolean', description: 'Indicates that the deployment is paused.' },
        selector: { allOf: [{ $ref: '#/definitions/LabelSelector' }], description: 'Label selector for pods.' },
        template: { allOf: [{ $ref: '#/definitions/PodTemplateSpec' }] },
      },
    },
    DeploymentStatus: { properties: { readyReplicas: { type: 'integer' } } },
    LabelSelector: { type: 'object', properties: { matchLabels: { type: 'object' } } },
    PodTemplateSpec: { properties: { spec: { allOf: [{ $ref: '#/definitions/PodSpec' }] } } },
    PodSpec: {
      properties: {
        containers: { type: 'array', items: { $ref: '#/definitions/Container' } },
      },
    },
    Container: {
      properties: {
        image: { type: 'string', description: 'Container image name. More info: https://kubernetes.io/docs/x' },
      },
    },
  },
};

const SERVICE_SCHEMA = {
  properties: { spec: { allOf: [{ $ref: '#/definitions/ServiceSpec' }] } },
  definitions: {
    ServiceSpec: {
      properties: {
        type: { type: 'string', enum: ['ClusterIP', 'NodePort', 'LoadBalancer', 'ExternalName'] },
        clusterIP: { type: 'string', description: 'clusterIP is the IP address of the service.' },
      },
    },
  },
};

describe('schemaAt', () => {
  it('walks through the allOf/$ref indirection the Kubernetes schema uses', () => {
    expect(schemaAt(K8S_SCHEMA, 'spec.replicas')?.type).toBe('integer');
    expect(schemaAt(K8S_SCHEMA, 'spec.paused')?.type).toBe('boolean');
  });

  it('steps into array items for a numeric segment', () => {
    // This is what makes a per-container field addressable at all.
    expect(schemaAt(K8S_SCHEMA, 'spec.template.spec.containers.0.image')?.type).toBe('string');
  });

  it('returns nothing for a path the schema does not have', () => {
    expect(schemaAt(K8S_SCHEMA, 'spec.nonsense')).toBeUndefined();
    expect(schemaAt(K8S_SCHEMA, 'spec.replicas.deeper')).toBeUndefined();
  });

  it('does not loop forever on a self-referential definition', () => {
    const cyclic = { properties: { a: { $ref: '#/definitions/Missing' } }, definitions: {} };
    expect(schemaAt(cyclic, 'a')).toBeUndefined();
  });
});

describe('resolveNode', () => {
  it('leaves a plain node alone', () => {
    expect(resolveNode(K8S_SCHEMA, { type: 'string' })?.type).toBe('string');
  });

  it('is undefined for a ref with no definition, rather than throwing', () => {
    expect(resolveNode(K8S_SCHEMA, { $ref: '#/definitions/Nope' })).toBeUndefined();
  });
});

describe('shortDescription', () => {
  it('keeps the first sentence and drops the doc link', () => {
    // Schema descriptions are paragraphs ending in a URL; a field label needs a sentence.
    expect(shortDescription({ description: 'Container image name. More info: https://kubernetes.io/docs/x' })).toBe(
      'Container image name.',
    );
  });

  it('handles a description with no sentence break or link', () => {
    expect(shortDescription({ description: 'Just this' })).toBe('Just this');
    expect(shortDescription({})).toBeUndefined();
  });
});

describe('formFieldsFor', () => {
  it('offers nothing at all without a schema', () => {
    // The form's whole claim is that it agrees with what the cluster accepts. Guessing
    // fields without a schema would be worse than the YAML tab beside it.
    expect(formFieldsFor('Deployment', null)).toEqual([]);
  });

  it('types each curated field from the schema', () => {
    const fields = formFieldsFor('Deployment', K8S_SCHEMA);
    const byPath = Object.fromEntries(fields.map((f) => [f.path, f]));
    expect(byPath['spec.replicas'].type).toBe('integer');
    expect(byPath['spec.paused'].type).toBe('boolean');
  });

  it('drops curated paths this cluster’s schema does not have', () => {
    // Field lists are per-kind but clusters differ by version; offering a field the API
    // server does not know would produce an edit it rejects.
    const fields = formFieldsFor('Deployment', K8S_SCHEMA);
    expect(fields.map((f) => f.path)).not.toContain('spec.progressDeadlineSeconds');
  });

  it('marks the selector read-only and says why', () => {
    const selector = formFieldsFor('Deployment', K8S_SCHEMA).find((f) => f.path === 'spec.selector');
    expect(selector?.readOnly).toBe(true);
    expect(selector?.readOnlyReason).toMatch(/immutable/i);
  });

  it('takes enum options from the schema rather than a hardcoded list', () => {
    const type = formFieldsFor('Service', SERVICE_SCHEMA).find((f) => f.path === 'spec.type');
    expect(type?.type).toBe('enum');
    expect(type?.options).toEqual(['ClusterIP', 'NodePort', 'LoadBalancer', 'ExternalName']);
  });

  it('marks clusterIP read-only, since the API server would reject the change', () => {
    const ip = formFieldsFor('Service', SERVICE_SCHEMA).find((f) => f.path === 'spec.clusterIP');
    expect(ip?.readOnly).toBe(true);
  });

  it('emits one image field per container that actually exists', () => {
    const fields = formFieldsFor('Deployment', K8S_SCHEMA, [{ name: 'app' }, { name: 'sidecar' }]);
    const images = fields.filter((f) => f.path.includes('containers'));
    expect(images.map((f) => f.path)).toEqual([
      'spec.template.spec.containers.0.image',
      'spec.template.spec.containers.1.image',
    ]);
    expect(images[1].label).toBe('Image · sidecar');
  });

  it('discovers spec scalars for a kind with no curated list, which is how CRDs are covered', () => {
    const crd = {
      properties: { spec: { $ref: '#/definitions/S' } },
      definitions: {
        S: {
          properties: {
            size: { type: 'integer', description: 'How many.' },
            mode: { type: 'string', enum: ['fast', 'slow'] },
            // Objects and arrays are skipped: this form cannot represent them honestly.
            nested: { type: 'object', properties: { a: { type: 'string' } } },
            list: { type: 'array', items: { type: 'string' } },
          },
        },
      },
    };
    expect(isCurated('Widget')).toBe(false);
    const fields = formFieldsFor('Widget', crd);
    expect(fields.map((f) => f.path)).toEqual(['spec.size', 'spec.mode']);
    expect(fields[1].options).toEqual(['fast', 'slow']);
  });

  it('never offers anything under status', () => {
    // status is the controller's to write; a form field there is an edit that gets
    // overwritten and confuses the reader about who owns the value.
    const paths = formFieldsFor('Deployment', K8S_SCHEMA, [{ name: 'app' }]).map((f) => f.path);
    expect(paths.some((p) => p.startsWith('status'))).toBe(false);
  });
});
