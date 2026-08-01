import { describe, expect, it } from 'vitest';

import { countLine, type Finding, parseSummary, severityOf, sortFindings } from './diagnosis';

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

describe('countLine', () => {
  it('reads as good news when there is nothing wrong', () => {
    expect(countLine([])).toBe('No problems found.');
  });

  it('counts each severity present and omits the ones that are not', () => {
    expect(countLine([f('critical'), f('critical'), f('warning')])).toBe('2 critical, 1 warning');
    expect(countLine([f('info')])).toBe('1 info');
  });
});
