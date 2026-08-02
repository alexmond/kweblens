import { describe, expect, it } from 'vitest';

import { countLine, type Finding, groupFindings, parseSummary, severityOf, sortFindings } from './diagnosis';

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

  it('orders groups by severity, not by how many there are', () => {
    // A pile of warnings must not outrank one critical.
    const groups = groupFindings([f('warning', 'a'), f('warning', 'a'), f('warning', 'a'), f('critical', 'b')]);
    expect(groups.map((g) => g.title)).toEqual(['b', 'a']);
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
