import { describe, expect, it } from 'vitest';

import {
  analyseLabel,
  analysisNote,
  analysisState,
  countLine,
  coverageNotice,
  type DiagnoseResult,
  type Finding,
  findingLink,
  groupFindings,
  KEY_SEPARATOR,
  parseSummary,
  relativeAge,
  severityOf,
  sortFindings,
} from './diagnosis';
import { matchesFilter, parseFilter } from './objectFilter';
import type { KubeObject } from './types';

const f = (severity: string, title = 't'): Finding => ({ severity, title, object: 'Pod/x' });

describe('parseSummary', () => {
  it('returns nothing for an absent summary', () => {
    // The common case: no key configured, so the server sends null. Not an error state.
    expect(parseSummary(null)).toEqual([]);
    expect(parseSummary(undefined)).toEqual([]);
    expect(parseSummary('')).toEqual([]);
  });

  it('classifies headings, bullets, numbered items and paragraphs', () => {
    const blocks = parseSummary('# Root Cause\n\n- first\n1. second\nplain line');
    expect(blocks.map((b) => b.kind)).toEqual(['heading', 'bullet', 'numbered', 'paragraph']);
  });

  it('splits bold runs into spans instead of leaving markup in the text', () => {
    // The whole point: the model's `**` must never reach the DOM as markup, and must not
    // be shown literally either.
    const [block] = parseSummary('**Audit** the manifests');
    expect(block.spans).toEqual([
      { text: 'Audit', bold: true },
      { text: ' the manifests', bold: false },
    ]);
  });

  it('never produces HTML, whatever the model writes', () => {
    // A model summarising a cluster is summarising attacker-influenceable text — an image
    // name, a container message. Nothing here may become markup.
    const blocks = parseSummary('- <img src=x onerror=alert(1)> and <b>bold</b>');
    const text = blocks
      .flatMap((b) => b.spans)
      .map((s) => s.text)
      .join('');
    expect(text).toContain('<img src=x onerror=alert(1)>');
    expect(blocks[0].kind).toBe('bullet');
    // It is carried as literal text in a span, which Vue's interpolation escapes.
    expect(blocks[0].spans.every((s) => typeof s.text === 'string')).toBe(true);
  });

  it('leaves unhandled markdown as literal text rather than guessing', () => {
    const [block] = parseSummary('use `kubectl get pods` and [a link](http://x)');
    const text = block.spans.map((s) => s.text).join('');
    expect(text).toContain('`kubectl get pods`');
    expect(text).toContain('[a link](http://x)');
  });

  it('records nesting depth for indented list items', () => {
    const blocks = parseSummary('- top\n    - nested');
    expect(blocks[0].depth).toBe(0);
    expect(blocks[1].depth).toBeGreaterThan(0);
  });

  it('drops a horizontal rule instead of printing it', () => {
    // The model emits "---" as a divider; rendering it literally shows punctuation as prose.
    expect(parseSummary('a\n---\nb').map((b) => b.kind)).toEqual(['paragraph', 'paragraph']);
    expect(parseSummary('***')).toEqual([]);
  });

  it('drops blank lines', () => {
    expect(parseSummary('a\n\n\nb')).toHaveLength(2);
  });
});

describe('severityOf', () => {
  it('passes through the two real severities', () => {
    expect(severityOf(f('critical'))).toBe('critical');
    expect(severityOf(f('WARNING'))).toBe('warning');
  });

  it('treats anything unrecognised as info rather than inventing alarm', () => {
    expect(severityOf(f('catastrophic'))).toBe('info');
    expect(severityOf(f(''))).toBe('info');
  });
});

describe('sortFindings', () => {
  it('puts critical first and info last', () => {
    const sorted = sortFindings([f('info', 'i'), f('critical', 'c'), f('warning', 'w')]);
    expect(sorted.map((x) => x.title)).toEqual(['c', 'w', 'i']);
  });

  it('does not mutate the input', () => {
    const input = [f('info', 'i'), f('critical', 'c')];
    sortFindings(input);
    expect(input.map((x) => x.title)).toEqual(['i', 'c']);
  });
});

describe('groupFindings', () => {
  it('collapses repeats of one title into a single group without dropping any', () => {
    // The case this exists for: 15 of 22 criticals were one check, which pushed the
    // ImagePullBackOff off the top of the list.
    const many = Array.from({ length: 15 }, (_, i) => ({
      severity: 'critical',
      title: 'Service has nothing behind it',
      object: `Service/s${i}`,
    }));
    const groups = groupFindings([...many, f('critical', 'ImagePullBackOff')]);
    expect(groups).toHaveLength(2);
    expect(groups.flatMap((g) => g.findings)).toHaveLength(16);
  });

  it('never puts two severities in one group', () => {
    // A group carries ONE badge, taken from its first member, so keying on title alone
    // would badge warnings as CRITICAL and hide the split entirely. #223 made this real
    // by splitting the no-endpoints check so the deliberately scaled-to-zero case reports
    // as a warning while the others stay critical.
    const groups = groupFindings([
      { severity: 'critical', title: 'Same title', object: 'Service/a' },
      { severity: 'warning', title: 'Same title', object: 'Service/b' },
      { severity: 'warning', title: 'Same title', object: 'Service/c' },
    ]);
    expect(groups).toHaveLength(2);
    expect(groups[0].severity).toBe('critical');
    expect(groups[0].findings).toHaveLength(1);
    expect(groups[1].severity).toBe('warning');
    expect(groups[1].findings).toHaveLength(2);
    for (const g of groups) {
      expect(new Set(g.findings.map((x) => x.severity)).size).toBe(1);
    }
  });

  it('orders groups by severity, not by how many there are', () => {
    // A pile of warnings must not outrank one critical.
    const groups = groupFindings([f('warning', 'a'), f('warning', 'a'), f('warning', 'a'), f('critical', 'b')]);
    expect(groups.map((g) => g.title)).toEqual(['b', 'a']);
  });

  it('keys on a separator that no severity or title can contain', () => {
    // U+001F. The server joins the same two fields with the same character — the pair is
    // pinned by DiagnoseKeySeparatorTest, because two surfaces that group differently would
    // disagree without saying so. Written as an escape, never as a literal byte: a raw
    // control byte in the source makes grep call the whole file binary and print nothing
    // for every search over it (#408).
    expect(KEY_SEPARATOR).toBe(String.fromCharCode(0x1f));
    expect(KEY_SEPARATOR).toHaveLength(1);
    // Positive control for the property the separator buys: titles carrying `|` and `:`
    // stay distinct groups. A printable separator is what makes two different pairs join
    // to one key, and both of those characters occur in real finding titles.
    const groups = groupFindings([f('critical', 'a|b'), f('critical', 'a'), f('critical', 'b: c')]);
    expect(groups.map((g) => g.title).sort()).toEqual(['a', 'a|b', 'b: c']);
  });

  it('hides nothing and reinterprets nothing', () => {
    const input = [f('critical', 'x'), f('critical', 'x'), f('info', 'y')];
    const groups = groupFindings(input);
    expect(groups.flatMap((g) => g.findings)).toHaveLength(input.length);
    expect(groups.find((g) => g.title === 'x')?.severity).toBe('critical');
  });
});

describe('countLine', () => {
  it('reads as good news when there is nothing wrong', () => {
    expect(countLine([])).toBe('No problems found.');
  });

  it('counts each severity present and omits the ones that are not', () => {
    expect(countLine([f('critical'), f('critical'), f('warning')])).toBe('2 critical, 1 warning');
    expect(countLine([f('info')])).toBe('1 info');
  });
});

// The coverage signal (#388). Every case here exists to prove ONE thing: the notice is
// built from what the server said about the list, and never from what a finding is called.
describe('coverageNotice', () => {
  const gapped = (incomplete: DiagnoseResult['incomplete'], findings: Finding[] = []): DiagnoseResult => ({
    findings,
    incomplete,
  });

  it('says nothing when the audit saw everything', () => {
    // The control. A notice on every diagnosis is worse than none, because it stops
    // meaning anything — so a scope with plenty of findings and no gaps stays silent.
    expect(coverageNotice(gapped([], [f('critical'), f('warning'), f('info')]))).toBeNull();
    expect(coverageNotice(gapped(undefined, [f('critical')]))).toBeNull();
    expect(coverageNotice(null)).toBeNull();
  });

  it('names each dimension that fell short, and what it was', () => {
    const notice = coverageNotice(
      gapped([
        { dimension: 'container privileges', reason: '5 further findings are not listed.' },
        { dimension: 'RBAC grants', reason: 'They could not be listed.' },
      ]),
    );
    expect(notice).toContain('not fully checked');
    expect(notice).toContain('container privileges — 5 further findings are not listed.');
    expect(notice).toContain('RBAC grants — They could not be listed.');
  });

  // THE POINT OF THE FIELD. Rename every finding on the server and the notice is unchanged,
  // because nothing here reads a title. A client that recognised "Further container
  // privileges not listed" would be keeping a second copy of a server rule, and it would go
  // stale the first time somebody reworded the finding — silently, which is the worst way.
  it('is unmoved by what the findings are called', () => {
    const gaps = [{ dimension: 'container privileges', reason: 'The cap bit.' }];
    const withOldTitles = coverageNotice(
      gapped(gaps, [f('info', 'Further container privileges not listed'), f('info', 'RBAC grants could not be read')]),
    );
    const withRenamed = coverageNotice(gapped(gaps, [f('info', 'Some entirely different wording, shipped tomorrow')]));
    const withNoFindingsAtAll = coverageNotice(gapped(gaps));

    expect(withRenamed).toBe(withOldTitles);
    expect(withNoFindingsAtAll).toBe(withOldTitles);
    expect(withOldTitles).toContain('The cap bit.');
  });

  // The other direction, which is the half a title match would pass: those exact two titles
  // are present and the server made no claim about coverage, so neither does the panel.
  it('does not invent a gap from a finding that happens to be titled like one', () => {
    expect(
      coverageNotice(
        gapped([], [f('info', 'Further container privileges not listed'), f('info', 'RBAC grants could not be read')]),
      ),
    ).toBeNull();
  });

  it('drops a gap carrying nothing to say rather than rendering an empty clause', () => {
    expect(coverageNotice(gapped([{ dimension: '', reason: '  ' }]))).toBeNull();
    expect(coverageNotice(gapped([{ dimension: 'RBAC grants', reason: '' }]))).toContain('RBAC grants');
  });
});

// The manual-trigger contract (#251). That opening the panel never spends an inference call
// is enforced server-side; what these cover is the half the UI owns — offering the trigger
// only where it can work, and never letting a cached reading pass as a current one.
const result = (over: Partial<DiagnoseResult> = {}): DiagnoseResult => ({
  findings: [f('critical', 'CrashLoopBackOff')],
  aiAvailable: true,
  ...over,
});

describe('analysisState', () => {
  it('offers nothing when the server has no model', () => {
    expect(analysisState(result({ aiAvailable: false }))).toBe('unavailable');
    expect(analysisState(result({ aiAvailable: undefined }))).toBe('unavailable');
    expect(analysisState(null)).toBe('unavailable');
  });

  it('offers nothing when there is nothing to summarise', () => {
    // A healthy scope: a button whose only possible answer is "no problems found" is not
    // worth an inference call.
    expect(analysisState(result({ findings: [] }))).toBe('unavailable');
  });

  it('separates never-analysed, current and overtaken', () => {
    expect(analysisState(result())).toBe('never');
    expect(analysisState(result({ summary: 'Restart the api pod.' }))).toBe('current');
    expect(analysisState(result({ summaryOutdated: true }))).toBe('outdated');
  });

  it('treats a summary as current only when the server actually sent one', () => {
    // The server withholds the prose the moment the findings stop matching, so an
    // `analysedAt` with no `summary` is the stale case, never the fresh one.
    expect(analysisState(result({ analysedAt: '2026-08-02T12:00:00Z', summaryOutdated: true }))).toBe('outdated');
  });
});

describe('analyseLabel', () => {
  it('says Analyse first and Re-analyse afterwards', () => {
    expect(analyseLabel('never')).toBe('Analyse');
    expect(analyseLabel('unavailable')).toBe('Analyse');
    expect(analyseLabel('current')).toBe('Re-analyse');
    // Overtaken still counts as "again": a call was already spent on this scope.
    expect(analyseLabel('outdated')).toBe('Re-analyse');
  });
});

describe('relativeAge', () => {
  const now = Date.parse('2026-08-02T12:00:00Z');
  const ago = (ms: number) => new Date(now - ms).toISOString();

  it('has nothing to say about a missing or unparseable instant', () => {
    expect(relativeAge(null, now)).toBeNull();
    expect(relativeAge(undefined, now)).toBeNull();
    expect(relativeAge('not a date', now)).toBeNull();
  });

  it('reads coarsely, in the unit a reader would use', () => {
    expect(relativeAge(ago(5_000), now)).toBe('just now');
    expect(relativeAge(ago(60_000), now)).toBe('1 minute ago');
    expect(relativeAge(ago(20 * 60_000), now)).toBe('20 minutes ago');
    expect(relativeAge(ago(3 * 3_600_000), now)).toBe('3 hours ago');
    expect(relativeAge(ago(3 * 86_400_000), now)).toBe('3 days ago');
  });

  it('does not report a negative age when the clocks disagree', () => {
    // The server stamps the instant; a browser a few seconds behind must not render
    // "-1 minutes ago", which reads as a bug rather than as skew.
    expect(relativeAge(new Date(now + 30_000).toISOString(), now)).toBe('just now');
  });
});

describe('analysisNote', () => {
  const now = Date.parse('2026-08-02T12:00:00Z');
  const at = (ms: number) => new Date(now - ms).toISOString();

  it('dates the summary it is shown with', () => {
    const note = analysisNote(result({ summary: 'Restart the api pod.', analysedAt: at(20 * 60_000) }), now);
    expect(note).toContain('language model');
    expect(note).toContain('20 minutes ago');
  });

  it('explains a withheld summary rather than pretending none was ever made', () => {
    // The failure this prevents: after a rollout the panel silently goes back to
    // "Analyse", and the reader cannot tell an overtaken verdict from a missing one.
    const note = analysisNote(result({ summaryOutdated: true, analysedAt: at(2 * 3_600_000) }), now);
    expect(note).toContain('findings have changed');
    expect(note).toContain('2 hours ago');
  });

  it('still explains a withheld summary when the age is unknown', () => {
    expect(analysisNote(result({ summaryOutdated: true }), now)).toContain('findings have changed');
  });

  it('says nothing when nothing has been analysed', () => {
    expect(analysisNote(result(), now)).toBeNull();
    expect(analysisNote(result({ aiAvailable: false }), now)).toBeNull();
  });
});

describe('findingLink', () => {
  const f = (target: unknown) => ({ severity: 'critical', title: 't', object: 'Pod/web-0', target }) as never;

  it('sends a finding to its kind list, filtered to exactly that object', () => {
    expect(findingLink(f({ kind: 'Pod', namespace: 'prod', name: 'web-0' }))).toEqual({
      kind: 'Pod',
      query: 'name:/^web-0$/ ns:/^prod$/',
    });
  });

  it('ANCHORS the terms, because these fields are substring matches', () => {
    // The bug this caught in review, measured on a running app before it was believed: a
    // finding about `sim-pod-2` opened a list of FIVE rows, because `name:` is a substring
    // match and `sim-pod-20`..`29` all contain it. Quoting does not help — `objectFilter.ts`
    // documents "a quoted value is exact too" under `status:`, the one field that compares
    // whole values; `name:`, `ns:` and `kind:` are substrings on purpose, since half a pod
    // name is the useful question in a search box. `/^…$/` is the only exact form they have.
    const link = findingLink(f({ kind: 'Pod', namespace: 'prod', name: 'web' }));
    expect(link?.query).toBe('name:/^web$/ ns:/^prod$/');
    // The property, stated as the regexes themselves rather than as the string: the term the
    // reader lands with must take `web` and leave `web-2` and `webhook` behind.
    const name = new RegExp(/^web$/);
    expect(name.test('web')).toBe(true);
    expect(name.test('web-2')).toBe(false);
    expect(name.test('webhook')).toBe(false);
  });

  it('escapes a dot, which is a real character in a real name', () => {
    // `.` is both a regex metacharacter and ordinary in Kubernetes names and namespaces, so
    // unescaped it matches ANY character — the same off-by-a-few defect in a quieter form.
    const link = findingLink(f({ kind: 'Service', namespace: 'kube-system', name: 'my.app' }));
    expect(link?.query).toBe('name:/^my\\.app$/ ns:/^kube-system$/');
    expect(new RegExp('^my\\.app$').test('myXapp')).toBe(false);
  });

  it('puts the namespace in the QUERY, not beside it', () => {
    // Passing a namespace separately would retarget the shell's own namespace filter — a side
    // effect well past "show me this object". As a term it narrows this list only, and stays
    // visible and editable next to the name. Both terms AND, which is what makes one row.
    const link = findingLink(f({ kind: 'Pod', namespace: 'kube-system', name: 'coredns-abc' }));
    expect(link?.query).toContain('ns:/^kube-system$/');
    expect(Object.keys(link ?? {})).toEqual(['kind', 'query']);
  });

  it('omits the namespace term for a cluster-scoped object', () => {
    // An `ns:` term would match nothing and the list would be honestly, uselessly empty.
    expect(findingLink(f({ kind: 'Node', namespace: null, name: 'node-1' }))?.query).toBe('name:/^node-1$/');
  });

  it('declines when the server sent no target', () => {
    // The security-audit case. Its own `object` is a display string in two documented shapes,
    // so there is nothing to address it with — and a reference that LOOKS clickable and lands
    // on the wrong object is worse than one that never offered.
    expect(findingLink(f(null))).toBeNull();
    expect(findingLink(f(undefined))).toBeNull();
  });

  it('declines on a half-built target rather than guessing', () => {
    expect(findingLink(f({ kind: 'Pod', namespace: 'prod', name: '' }))).toBeNull();
    expect(findingLink(f({ kind: '', namespace: 'prod', name: 'web-0' }))).toBeNull();
  });

  it('declines a kind the shell does not model, and asks BEFORE building anything', () => {
    // The same guard the Warnings table has used since its rows became clickable. A CRD the
    // nav has no leaf for has nowhere to send anyone, so the reference stays text.
    const target = { kind: 'Widget', namespace: 'prod', name: 'w-1' };
    expect(findingLink(f(target), (k) => k !== 'Widget')).toBeNull();
    expect(findingLink(f(target), (k) => k === 'Widget')).toEqual({
      kind: 'Widget',
      query: 'name:/^w-1$/ ns:/^prod$/',
    });
  });

  it('never reads the display string, however broken it is', () => {
    // The regression this design exists to prevent. `object` carries a container suffix and no
    // namespace, and four producers build it in four shapes — so a link parsed out of it would
    // be a fifth opinion any one of them could invalidate silently. Here the display string is
    // deliberate nonsense and the link is still right.
    const finding = {
      severity: 'critical',
      title: 'CrashLoopBackOff',
      object: 'Pod/web-0 container api',
      target: { kind: 'Pod', namespace: 'prod', name: 'web-0' },
    } as never;
    expect(findingLink(finding)).toEqual({ kind: 'Pod', query: 'name:/^web-0$/ ns:/^prod$/' });
  });
});

describe('findingLink: the query, fed to the REAL parser', () => {
  // String equality is what let BOTH of this function's bugs through: it agreed with
  // `name:"x"` while that selected five rows, and it would have agreed with an unescaped
  // slash while that silently widened the query. So these go through `objectFilter` itself.
  const obj = (name: string, namespace = 'prod', kind = 'Pod'): KubeObject =>
    ({ kind, metadata: { name, namespace } }) as KubeObject;

  const selects = (query: string, o: KubeObject): boolean => {
    const parsed = parseFilter(query);
    // Load-bearing, not a formality: `matchesFilter` returns TRUE for everything when the
    // query failed to parse ("a broken pattern is not zero rows"). Without this line a
    // mangled query reads as a passing test — the exact failure mode these tests exist for.
    expect(parsed.error, `query did not parse: ${query}`).toBeNull();
    return matchesFilter(o, parsed);
  };

  const queryFor = (name: string, namespace: string | null) =>
    findingLink({ severity: 'critical', title: 't', object: 'o', target: { kind: 'Pod', namespace, name } } as never)
      ?.query ?? '';

  it('selects the object the finding named, and nothing that merely contains it', () => {
    const q = queryFor('sim-pod-2', 'sim-ns-2');
    expect(selects(q, obj('sim-pod-2', 'sim-ns-2'))).toBe(true);
    expect(selects(q, obj('sim-pod-20', 'sim-ns-2'))).toBe(false);
    expect(selects(q, obj('sim-pod-2', 'sim-ns-20'))).toBe(false);
  });

  it('survives a SLASH in the name, although the term then contains the regex delimiter', () => {
    // This looked like a parser differential and is not — checked against the parser rather
    // than argued from `readDelimited`, which does stop at the first `/`. At the TOKEN level
    // the whole `name:/^foo/bar$/` stays one term and the value is taken from the first slash
    // to the LAST, so the inner one is ordinary regex content. Measured: two field terms,
    // `foo/bar` selected, `foo` and `foo/bar/baz` both rejected.
    //
    // So no escape is needed, and none is applied — but this is incidental behaviour of the
    // tokenizer rather than a stated guarantee, and a name CAN contain a slash: `EventService`
    // builds an event's object as `kind + "/" + name` from `involvedObject.name`, which the
    // API server does not validate, and `eventTarget` keeps everything after the FIRST slash
    // because the rest may contain more. Hence this test: if the tokenizer ever starts ending
    // the run at the inner slash, this is what says so.
    const q = queryFor('foo/bar', 'prod');
    expect(selects(q, obj('foo/bar'))).toBe(true);
    expect(selects(q, obj('foo'))).toBe(false);
    expect(selects(q, obj('foo/bar/baz'))).toBe(false);
  });

  it('survives a dot, which is ordinary in a name and a metacharacter in a pattern', () => {
    const q = queryFor('my.app', 'prod');
    expect(selects(q, obj('my.app'))).toBe(true);
    expect(selects(q, obj('myXapp'))).toBe(false);
  });

  it('survives a namespace with a slash in it too', () => {
    // The namespace goes through the same builder, so it gets the same proof rather than the
    // assumption that whatever is true of the name is true here — the two are separate terms
    // in the same query, and only the first one was measured above.
    const q = queryFor('web', 'ns/odd');
    expect(selects(q, obj('web', 'ns/odd'))).toBe(true);
    expect(selects(q, obj('web', 'ns'))).toBe(false);
  });

  it('still parses when the name carries regex metacharacters that could unbalance it', () => {
    // An unescaped `(` or `[` is an unterminated group: the regex throws, `parseFilter` reports
    // an error, and `matchesFilter` then matches EVERY row — a list that looks filtered and is
    // not. `selects` asserts the parse, so this fails loudly rather than quietly passing.
    for (const name of ['web(1)', 'web[0]', 'a+b', 'a|b', 'a$b', 'a^b', 'a*b', 'a?b']) {
      expect(selects(queryFor(name, 'prod'), obj(name)), name).toBe(true);
      expect(selects(queryFor(name, 'prod'), obj('web')), name).toBe(false);
    }
  });
});
