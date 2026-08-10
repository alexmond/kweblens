import { describe, expect, it } from 'vitest';

import { activeQuery, FILTER_HELP, FILTER_HELP_NOTES, matchesFilter, parseFilter } from './objectFilter';
import type { KubeObject } from './types';

// The list header's filter language. It is nearly all logic, so it lives in a .ts and is
// tested with no DOM at all: the grammar, Kubernetes' own label semantics (including the two
// operators that match an object with no such label), and every malformed input that must
// produce a SENTENCE rather than an empty table.

const pod = (name: string, ns: string, labels: Record<string, string> = {}): KubeObject => ({
  kind: 'Pod',
  metadata: { name, namespace: ns, labels },
});

/** Names of the objects a query keeps, in order — the shape every case below asserts on. */
const kept = (query: string, objects: KubeObject[]): string[] => {
  const filter = parseFilter(query);
  return objects.filter((o) => matchesFilter(o, filter)).map((o) => o.metadata?.name ?? '');
};

const FLEET: KubeObject[] = [
  pod('web-1', 'prod', { app: 'web', tier: 'frontend', env: 'prod' }),
  pod('web-2', 'prod', { app: 'web', tier: 'frontend', env: 'prod' }),
  pod('db-0', 'prod', { app: 'db', tier: 'backend' }),
  pod('web-canary', 'staging', { app: 'web', env: 'staging' }),
  pod('cache', 'kube-system', {}),
];

describe('a bare word is what it always was', () => {
  // The one non-negotiable: this is the common case and the box behaved this way before the
  // grammar existed. Everything else in this file is opt-in on a character the old filter
  // could not have contained.

  it('matches a substring of the name, case-insensitively', () => {
    expect(kept('web', FLEET)).toEqual(['web-1', 'web-2', 'web-canary']);
    expect(kept('WEB', FLEET)).toEqual(['web-1', 'web-2', 'web-canary']);
  });

  it('matches the namespace and the kind too', () => {
    expect(kept('kube-system', FLEET)).toEqual(['cache']);
    expect(kept('pod', FLEET)).toHaveLength(FLEET.length);
  });

  it('treats regex metacharacters as literal text', () => {
    const objects = [pod('a.b', 'default'), pod('axb', 'default')];
    expect(kept('a.b', objects)).toEqual(['a.b']);
    expect(kept('web*', FLEET)).toEqual([]);
  });

  it('is inert when empty or whitespace', () => {
    expect(parseFilter('').terms).toHaveLength(0);
    expect(parseFilter('   ').terms).toHaveLength(0);
    expect(kept('   ', FLEET)).toHaveLength(FLEET.length);
  });

  it('survives an object with no namespace, no labels and no kind', () => {
    const bare: KubeObject = {};
    expect(matchesFilter(bare, parseFilter('web'))).toBe(false);
    expect(matchesFilter(bare, parseFilter('-web'))).toBe(true);
    expect(matchesFilter(bare, parseFilter('app=web'))).toBe(false);
    expect(matchesFilter(bare, parseFilter('app!=web'))).toBe(true);
  });
});

describe('terms combine with AND, and any of them can be negated', () => {
  it('requires every term to match', () => {
    expect(kept('web prod', FLEET)).toEqual(['web-1', 'web-2']);
    expect(kept('web prod nothing-like-this', FLEET)).toEqual([]);
  });

  it('excludes on a leading dash', () => {
    expect(kept('-web', FLEET)).toEqual(['db-0', 'cache']);
    expect(kept('web -canary', FLEET)).toEqual(['web-1', 'web-2']);
  });

  it('negates any kind of term, not just text', () => {
    expect(kept('-app=web', FLEET)).toEqual(['db-0', 'cache']);
    expect(kept('-ns:prod', FLEET)).toEqual(['web-canary', 'cache']);
    expect(kept('-label:env', FLEET)).toEqual(['db-0', 'cache']);
  });

  it('keeps quoted text with a space in it as one term', () => {
    const objects = [pod('two words here', 'default'), pod('two', 'default'), pod('words', 'default')];
    expect(kept('"two words"', objects)).toEqual(['two words here']);
    expect(kept('two words', objects)).toEqual(['two words here']);
  });
});

describe('regex is opt-in, and a broken one is a sentence', () => {
  it('matches name, namespace and kind, case-insensitively', () => {
    expect(kept('/^web-\\d+$/', FLEET)).toEqual(['web-1', 'web-2']);
    expect(kept('/^WEB-/', FLEET)).toEqual(['web-1', 'web-2', 'web-canary']);
    expect(kept('/^kube-/', FLEET)).toEqual(['cache']);
  });

  it('anchors work, which is the whole reason for the feature', () => {
    expect(kept('/^cache$/', FLEET)).toEqual(['cache']);
    expect(kept('/^ache/', FLEET)).toEqual([]);
  });

  it('reports an invalid pattern instead of matching nothing', () => {
    const filter = parseFilter('/web(/');
    expect(filter.error).toMatch(/Invalid regex/);
    expect(filter.terms).toHaveLength(0);
    expect(kept('/web(/', FLEET)).toHaveLength(FLEET.length);
  });

  it('says the pattern once, without the flag nobody typed', () => {
    // V8 hands back "Invalid regular expression: /web(/i: Unterminated group", so quoting it
    // whole printed the pattern twice and mentioned an `i` the operator never wrote.
    const error = parseFilter('/web(/').error ?? '';
    expect(error).toBe('Invalid regex /web(/ — Unterminated group');
  });

  it('reports an unterminated pattern — the state the box is in mid-typing', () => {
    expect(parseFilter('/^web').error).toMatch(/Unterminated \/regex\//);
    expect(parseFilter('name:/^web').error).toMatch(/Unterminated \/regex\//);
  });

  it('rejects an empty pattern rather than matching every row', () => {
    expect(parseFilter('//').error).toMatch(/Empty regex/);
  });

  it('does not turn a slash inside a word into a regex', () => {
    const objects = [pod('docker.io/nginx', 'default')];
    expect(kept('io/nginx', objects)).toEqual(['docker.io/nginx']);
  });
});

describe('field terms narrow to one field', () => {
  it('matches only that field', () => {
    // "prod" is in the namespace of three pods and in no name; ns: and name: must disagree.
    expect(kept('ns:prod', FLEET)).toEqual(['web-1', 'web-2', 'db-0']);
    expect(kept('name:prod', FLEET)).toEqual([]);
    expect(kept('namespace:staging', FLEET)).toEqual(['web-canary']);
    expect(kept('kind:pod', FLEET)).toHaveLength(FLEET.length);
    expect(kept('kind:service', FLEET)).toEqual([]);
  });

  it('takes a regex or quoted text as its value', () => {
    expect(kept('name:/^web-\\d/', FLEET)).toEqual(['web-1', 'web-2']);
    expect(kept('ns:"kube-system"', FLEET)).toEqual(['cache']);
  });

  it('names an unknown field instead of searching for a colon no name can contain', () => {
    expect(parseFilter('status:Running').error).toMatch(/Unknown field “status:”/);
    expect(parseFilter('status:Running').error).toContain('name:, ns:, kind: and label:');
  });

  it('rejects a field prefix with nothing after it', () => {
    expect(parseFilter('ns:').error).toMatch(/Empty search text/);
  });
});

describe('label requirements follow apimachinery, including the part people get wrong', () => {
  // labels.Requirement.Matches: In/Equals need the key present; NotIn/NotEquals are TRUE when
  // the key is absent. The upstream docs say it in words — "environment!=production ... also
  // selects resources with no environment label" — and a filter that quietly disagreed would
  // give a different answer from the kubectl the operator ran a minute ago.

  it('equality needs the key to be present', () => {
    expect(kept('app=web', FLEET)).toEqual(['web-1', 'web-2', 'web-canary']);
    expect(kept('app==web', FLEET)).toEqual(['web-1', 'web-2', 'web-canary']);
    expect(kept('app=nope', FLEET)).toEqual([]);
  });

  it('inequality also keeps objects that carry no such label', () => {
    // cache has no app label at all and must survive app!=web.
    expect(kept('app!=web', FLEET)).toEqual(['db-0', 'cache']);
    expect(kept('env!=prod', FLEET)).toEqual(['db-0', 'web-canary', 'cache']);
  });

  it('in () needs the key; notin () also keeps objects without it', () => {
    expect(kept('env in (prod,staging)', FLEET)).toEqual(['web-1', 'web-2', 'web-canary']);
    expect(kept('env in (staging)', FLEET)).toEqual(['web-canary']);
    expect(kept('env notin (prod)', FLEET)).toEqual(['db-0', 'web-canary', 'cache']);
  });

  it('accepts the spacing kubectl accepts', () => {
    const expected = ['web-1', 'web-2', 'web-canary'];
    expect(kept('env in (prod, staging)', FLEET)).toEqual(expected);
    expect(kept('env in(prod,staging)', FLEET)).toEqual(expected);
    expect(kept('env  in  ( prod , staging )', FLEET)).toEqual(expected);
  });

  it('negates a set requirement as a whole', () => {
    // The `-` binds to the term, and the term is re-joined from three whitespace-separated
    // pieces — so this is the one place where the splitter and the negation have to agree.
    expect(kept('-env in (prod)', FLEET)).toEqual(['db-0', 'web-canary', 'cache']);
    expect(kept('-label:app=web', FLEET)).toEqual(['db-0', 'cache']);
  });

  it('compares label values exactly — case and all, like kubectl', () => {
    const objects = [pod('a', 'default', { app: 'Web' }), pod('b', 'default', { app: 'web' })];
    expect(kept('app=web', objects)).toEqual(['b']);
    expect(kept('app=Web', objects)).toEqual(['a']);
  });

  it('supports the empty value the selector grammar allows', () => {
    const objects = [pod('a', 'default', { app: '' }), pod('b', 'default', { app: 'web' })];
    expect(kept('app=', objects)).toEqual(['a']);
    expect(kept('app!=', objects)).toEqual(['b']);
  });

  it('handles presence and absence through the label: prefix', () => {
    expect(kept('label:env', FLEET)).toEqual(['web-1', 'web-2', 'web-canary']);
    expect(kept('-label:env', FLEET)).toEqual(['db-0', 'cache']);
    expect(kept('label:app=web', FLEET)).toEqual(['web-1', 'web-2', 'web-canary']);
  });

  it('matches a prefixed label key', () => {
    const objects = [pod('a', 'default', { 'example.com/team': 'core' }), pod('b', 'default', {})];
    expect(kept('example.com/team=core', objects)).toEqual(['a']);
    expect(kept('label:example.com/team', objects)).toEqual(['a']);
  });

  it('combines several requirements, as -l a=b,c=d does', () => {
    expect(kept('app=web env=prod', FLEET)).toEqual(['web-1', 'web-2']);
    expect(kept('app=web -env=prod', FLEET)).toEqual(['web-canary']);
    expect(kept('tier in (frontend) ns:prod', FLEET)).toEqual(['web-1', 'web-2']);
  });
});

describe('malformed input produces a message, never a silently empty table', () => {
  it.each([
    ['=web', /Missing label key/],
    ['!=web', /Missing label key/],
    ['label:', /Missing label key/],
    ['-', /bare “-”/],
    ['env in (', /Unterminated \(/],
    ['env in ()', /has no values/],
    ['"unclosed', /Unterminated quote/],
    ['replicas>2', /Numeric label requirements/],
    ['replicas<2', /Numeric label requirements/],
    ['app!=db /bad(/', /Invalid regex/],
  ])('%s is refused with an explanation', (query, expected) => {
    const filter = parseFilter(query);
    expect(filter.error).toMatch(expected);
    expect(filter.terms).toHaveLength(0);
  });

  it('shows every row while the query is broken, rather than claiming nothing matched', () => {
    // The rule from #306/#316 applied to a filter: "no rows" and "your pattern is broken" are
    // different claims, so the one that was never established is never made.
    expect(kept('/bad(/', FLEET)).toHaveLength(FLEET.length);
    expect(kept('=web', FLEET)).toHaveLength(FLEET.length);
  });

  it('rejects a label key that could never exist', () => {
    expect(parseFilter('.bad=web').error).toMatch(/not a valid label key/);
  });

  it('never throws, whatever it is handed', () => {
    const nasty = ['((((', '////', '"', '-/', 'a=b=c=d', 'in (a)', 'notin', '- -', '/(?<'];
    for (const q of nasty) {
      expect(() => parseFilter(q)).not.toThrow();
    }
  });
});

describe('a query that did not parse is not narrowing anything', () => {
  it('reports an empty active query so the count and empty state do not describe a filter', () => {
    expect(activeQuery('web', parseFilter('web'))).toBe('web');
    expect(activeQuery('/bad(/', parseFilter('/bad(/'))).toBe('');
  });
});

describe('the help the popover renders stays true', () => {
  it('parses every example it shows', () => {
    for (const row of FILTER_HELP) {
      expect(parseFilter(row.example).error, `${row.example}: ${parseFilter(row.example).error}`).toBeNull();
    }
  });

  it('says where the grammar is knowingly narrower than kubectl', () => {
    const notes = FILTER_HELP_NOTES.join(' ');
    expect(notes).toContain('label:partition');
    expect(notes).toContain('field selectors');
    expect(notes).toContain('nothing is truncated');
  });
});

describe('scale', () => {
  it('filters three thousand objects without compiling a regex per row', () => {
    // The list endpoint returns the whole collection (#302), so the filter is the only thing
    // between the operator and 3 000 rows. Parsing happens once per query, not once per
    // object: the assertion here is that the work stays linear and cheap enough to run on a
    // keystroke. It is a floor, not a benchmark — perf-sweep.mjs measures the real thing.
    const many = Array.from({ length: 3000 }, (_, i) => pod(`web-${i}`, 'prod', { app: i % 2 === 0 ? 'web' : 'db' }));
    const filter = parseFilter('/^web-\\d+$/ app=web -web-1000');
    const started = performance.now();
    const hits = many.filter((o) => matchesFilter(o, filter));
    expect(hits).toHaveLength(1499);
    expect(performance.now() - started).toBeLessThan(500);
  });
});
