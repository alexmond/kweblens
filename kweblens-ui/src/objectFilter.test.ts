import { describe, expect, it } from 'vitest';

import {
  activeQuery,
  FILTER_HELP,
  FILTER_HELP_NOTES,
  matchesFilter,
  parseFilter,
  statusQuery,
  statusQueryable,
  withStatusTerm,
} from './objectFilter';
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
    expect(parseFilter('phase:Running').error).toMatch(/Unknown field “phase:”/);
    expect(parseFilter('phase:Running').error).toContain('name:, ns:, kind:, status: and label:');
  });

  it('rejects a field prefix with nothing after it', () => {
    expect(parseFilter('ns:').error).toMatch(/Empty search text/);
  });
});

// ---- status: --------------------------------------------------------------------------------
// The term GH#337 exists for. Its ONE requirement is that a state an overview card counted is
// selectable and selects exactly those objects, so most of what follows is about the ways a
// looser match would quietly break that equality rather than fail loudly.

/** A row as the server ships it: `kweblensState` is computed by StatusVocabulary, never typed. */
const stated = (name: string, label: string, tone: 'ok' | 'warn' | 'err' | 'idle'): KubeObject => ({
  kind: 'Pod',
  metadata: { name, namespace: 'prod', labels: {} },
  kweblensState: { label, tone },
});

/** One of each tone, plus the pair whose labels share a stem — the substring trap. */
const STATED: KubeObject[] = [
  stated('web-1', 'Running', 'ok'),
  stated('web-2', 'Running', 'ok'),
  stated('job-done', 'Completed', 'idle'),
  stated('job-run', 'Complete', 'ok'),
  stated('sched-1', 'Pending', 'warn'),
  stated('crash-1', 'CrashLoopBackOff', 'err'),
  stated('pull-1', 'ImagePullBackOff', 'err'),
  stated('svc-1', 'No endpoints', 'err'),
  pod('unjudged', 'prod'),
];

describe('status: selects on the state the server computed', () => {
  it('matches the state label, case-insensitively', () => {
    expect(kept('status:Running', STATED)).toEqual(['web-1', 'web-2']);
    expect(kept('status:running', STATED)).toEqual(['web-1', 'web-2']);
    expect(kept('status:PENDING', STATED)).toEqual(['sched-1']);
  });

  it('matches the WHOLE label, so one state cannot drag another in with it', () => {
    // The failure this term is built to prevent. As a substring match, "Complete" would take
    // "Completed" too — a card saying 1 Complete opening a list of 2, which is GH#336's whole
    // premise. The stem pair is in the fleet precisely so a regression here fails.
    expect(kept('status:Complete', STATED)).toEqual(['job-run']);
    expect(kept('status:Completed', STATED)).toEqual(['job-done']);
    expect(kept('status:Backoff', STATED)).toEqual([]);
    expect(kept('status:Run', STATED)).toEqual([]);
  });

  it('takes a quoted value, because a state can have a space in it', () => {
    expect(kept('status:"No endpoints"', STATED)).toEqual(['svc-1']);
  });

  it('takes a regex for the genuinely fuzzy question', () => {
    expect(kept('status:/backoff/', STATED)).toEqual(['crash-1', 'pull-1']);
    expect(kept('status:/^Complete$/', STATED)).toEqual(['job-run']);
  });

  it('negates like every other term', () => {
    expect(kept('-status:Running', STATED)).toEqual([
      'job-done',
      'job-run',
      'sched-1',
      'crash-1',
      'pull-1',
      'svc-1',
      'unjudged',
    ]);
  });

  it('ANDs with the rest of the grammar', () => {
    expect(kept('status:Running name:web-1', STATED)).toEqual(['web-1']);
    expect(kept('status:Running ns:staging', STATED)).toEqual([]);
  });

  it('never selects a row the server reached no verdict about', () => {
    // "unjudged" carries no kweblensState — an uncovered kind. No pattern may claim it, not
    // even one that matches everything, because absence of a verdict is not a state.
    expect(kept('status:Running', STATED)).not.toContain('unjudged');
    expect(kept('status:/.*/', STATED)).not.toContain('unjudged');
    expect(kept('status:/^$/', STATED)).not.toContain('unjudged');
    // ...and the negation keeps it, which is the same fact stated the other way.
    expect(kept('-status:Running', STATED)).toContain('unjudged');
  });

  it('ignores a state the server sent empty rather than counting it as one', () => {
    const blank: KubeObject[] = [
      { kind: 'Pod', metadata: { name: 'blank' }, kweblensState: { label: '', tone: 'ok' } },
    ];
    expect(kept('status:/.*/', blank)).toEqual([]);
  });

  it.each([
    ['status:', /Missing state after “status:”/],
    ['status:""', /Missing state after “status:”/],
    ['status://', /Empty regex/],
    ['status:/bad(/', /Invalid regex/],
  ])('%s is refused with an explanation and leaves every row on screen', (query, expected) => {
    const filter = parseFilter(query);
    expect(filter.error).toMatch(expected);
    expect(filter.terms).toHaveLength(0);
    expect(kept(query, STATED)).toHaveLength(STATED.length);
  });

  it('partitions a fleet exactly: every state selects its own objects and no others', () => {
    // The ticket's equality, expressed without a server: for each distinct label the rows
    // carry — which is exactly the set of labels a card tallies — the query selects the rows
    // with that label and nothing else, and the selections add up to the judged population.
    const judged = STATED.filter((o) => o.kweblensState !== undefined);
    const labels = [...new Set(judged.map((o) => o.kweblensState?.label ?? ''))];
    let selected = 0;
    for (const label of labels) {
      const expected = judged.filter((o) => o.kweblensState?.label === label).map((o) => o.metadata?.name ?? '');
      expect(kept(`status:"${label}"`, STATED)).toEqual(expected);
      selected += expected.length;
    }
    expect(selected).toBe(judged.length);
  });
});

describe('statusQuery writes what statusMatcher reads', () => {
  // The writer's half of `status:` (#338). A card's state line is a link only because a query
  // can be BUILT from the label, and the card's number equals the rows it opens only while the
  // writing and the reading agree — so every case here is a round trip, never a string
  // compared with a string someone typed into the test.

  it('round-trips every state in a fleet back to exactly its own objects', () => {
    const judged = STATED.filter((o) => o.kweblensState !== undefined);
    for (const label of new Set(judged.map((o) => o.kweblensState?.label ?? ''))) {
      const expected = judged.filter((o) => o.kweblensState?.label === label).map((o) => o.metadata?.name ?? '');
      expect(kept(statusQuery(label), STATED)).toEqual(expected);
    }
  });

  it('leaves a bare state bare and quotes only what would otherwise split into two terms', () => {
    expect(statusQuery('Running')).toBe('status:Running');
    expect(statusQuery('CrashLoopBackOff')).toBe('status:CrashLoopBackOff');
    expect(statusQuery('No endpoints')).toBe('status:"No endpoints"');
  });

  it('round-trips the stem pair, which a substring match would collapse', () => {
    expect(kept(statusQuery('Complete'), STATED)).toEqual(['job-run']);
    expect(kept(statusQuery('Completed'), STATED)).toEqual(['job-done']);
  });

  it('refuses a label the grammar cannot express rather than writing one that means something else', () => {
    // There is no escape character, so a `"` inside a value ends the quote. The honest answer
    // is that such a state has no query — the caller renders it as text — because the
    // alternative is a link whose rows are not the ones that were counted.
    expect(statusQueryable('Running')).toBe(true);
    expect(statusQueryable('No endpoints')).toBe(true);
    expect(statusQueryable('say "hi"')).toBe(false);
    expect(statusQueryable('')).toBe(false);
    expect(statusQueryable('   ')).toBe(false);
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

// ---- writing a status term back into the query ------------------------------------------------
// The parser's inverse (GH#341): what a status chip's click does to the box. Every case below
// is about the query still saying the truth afterwards — the box is the one mechanism that
// owns filtering, so a rewrite that loses a term hides rows nobody asked to hide.

describe('withStatusTerm', () => {
  it('adds one to an empty query', () => {
    expect(withStatusTerm('', 'Running')).toBe('status:Running');
  });

  it('replaces the positive status term rather than ANDing a second one', () => {
    // Terms are ANDed and the grammar has no "either", so two status terms select NOTHING.
    // A rail that appended would answer its second click with an empty table.
    expect(withStatusTerm('status:Running', 'Pending')).toBe('status:Pending');
    expect(kept('status:Running status:Pending', STATED)).toEqual([]);
  });

  it('keeps every other term, wherever the status term sat', () => {
    expect(withStatusTerm('ns:prod status:Running -web app=x', 'Pending')).toBe('ns:prod -web app=x status:Pending');
  });

  it('keeps quoted text, regexes and value lists whole', () => {
    // The reason this lives beside the parser: knowing where one term ends is the tokenizer's
    // knowledge, and a second splitter written next to a caller is a copy that goes stale.
    expect(withStatusTerm('name:"two words" /^web-\\d+$/ env in (dev,stage)', 'Pending')).toBe(
      'name:"two words" /^web-\\d+$/ env in (dev,stage) status:Pending',
    );
  });

  it('leaves a NEGATED status term alone — it is a different question', () => {
    // "everything except the healthy ones" ANDs with a positive term perfectly well, and
    // dropping it would silently widen the list under the operator.
    expect(withStatusTerm('-status:Running', 'Pending')).toBe('-status:Running status:Pending');
    expect(withStatusTerm('-status:Running', null)).toBe('-status:Running');
    expect(kept('-status:Running status:Pending', STATED)).toEqual(['sched-1']);
  });

  it('removes the term when given no label', () => {
    expect(withStatusTerm('ns:prod status:Running', null)).toBe('ns:prod');
    expect(withStatusTerm('status:Running', null)).toBe('');
  });

  it('quotes a state whose name has a space in it, and the term selects it back', () => {
    // A bare `status:No endpoints` is two terms selecting nothing. No workload state has a
    // space today; the kinds GH#336 goes on to cover do.
    const q = withStatusTerm('', 'No endpoints');
    expect(q).toBe('status:"No endpoints"');
    expect(kept(q, STATED)).toEqual(['svc-1']);
  });

  it('round-trips every state the covered kinds can be in', () => {
    // WorkloadHealth's whole vocabulary, plus the waiting reasons a pod's state is named
    // after. A term that does not select its own label back is a chip offering a number and
    // then showing zero rows.
    for (const label of [
      'Running',
      'Completed',
      'Pending',
      'CrashLoopBackOff',
      'ImagePullBackOff',
      'ErrImagePull',
      'Healthy',
      'Unavailable',
      'Idle',
      'Ready',
      'Active',
      'Succeeded',
      'Failed',
      'Suspended',
      'Scheduled',
      'Unknown',
    ]) {
      const q = withStatusTerm('', label);
      expect(parseFilter(q).error, `${label}: ${parseFilter(q).error}`).toBeNull();
      expect(kept(q, [stated('hit', label, 'ok'), stated('miss', 'Something else', 'ok')]), label).toEqual(['hit']);
    }
  });

  it('returns a query it cannot even tokenize unchanged', () => {
    // Half-typed, not broken forever: rewriting the string someone is in the middle of typing
    // would move the caret out from under them, and there is no term structure there to edit.
    expect(withStatusTerm('name:"half typed', 'Running')).toBe('name:"half typed');
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
