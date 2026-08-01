/**
 * The diagnosis surface: findings with their reasons, and the optional LLM summary.
 *
 * <p>Why this deepens the overview rather than becoming a third "what is wrong" view
 * (#218): the two answer the same question at different depths. The overview's
 * `needsAttention` carries `{kind, namespace, name, reason}` where reason is a terse status
 * — "ImagePullBackOff", "0/3 ready". A finding carries the scheduler's actual message, what
 * an exit code meant, and a suggested fix. Breadth and depth, not duplicates.
 *
 * <p>The markdown here is deliberately parsed to data and rendered with Vue templates. The
 * summary is written by a language model, and turning model output into HTML — `v-html` or
 * otherwise — would make every diagnosis a script-injection surface for whatever the model
 * was fed by the cluster it just read. Text stays text.
 */

/** One line of the summary, already classified. `text` is plain — never HTML. */
export interface SummaryBlock {
  kind: 'heading' | 'bullet' | 'numbered' | 'paragraph';
  /** Runs of the line, split so bold can be shown without markup reaching the DOM. */
  spans: { text: string; bold: boolean }[];
  /** Nesting for list items, so a sub-bullet indents. */
  depth: number;
}

/** Severity as the server reports it. Anything unrecognised is treated as info. */
export type Severity = 'critical' | 'warning' | 'info';

export interface Finding {
  severity: string;
  title: string;
  object: string;
  detail?: string | null;
  suggestedFix?: string | null;
  source?: string | null;
}

export interface DiagnoseResult {
  findings: Finding[];
  summary?: string | null;
  aiEnriched?: boolean;
}

const BOLD = /\*\*([^*]+)\*\*/g;

/** The list/heading marker a line opens with, and the text after it — or null. */
function leadingMarker(body: string): { kind: 'heading' | 'bullet' | 'numbered'; rest: string } | null {
  const after = (at: number): string | null => {
    // A marker only counts when whitespace follows it, so "#hashtag" and "1.5" are prose.
    if (at >= body.length || (body[at] !== ' ' && body[at] !== '\t')) {
      return null;
    }
    return body.slice(at).trim();
  };
  if (body[0] === '#') {
    let i = 0;
    while (i < body.length && body[i] === '#' && i < 6) {
      i += 1;
    }
    const rest = after(i);
    return rest !== null ? { kind: 'heading', rest } : null;
  }
  if (body[0] === '-' || body[0] === '*') {
    const rest = after(1);
    return rest !== null ? { kind: 'bullet', rest } : null;
  }
  let i = 0;
  while (i < body.length && body[i] >= '0' && body[i] <= '9') {
    i += 1;
  }
  if (i > 0 && body[i] === '.') {
    const rest = after(i + 1);
    return rest !== null ? { kind: 'numbered', rest } : null;
  }
  return null;
}

/**
 * Split a line into plain and bold runs.
 *
 * <p>Only `**bold**` is recognised. Everything else — backticks, links, emphasis — is left
 * as literal text on purpose: an unhandled construct should look slightly plain, never
 * become markup.
 */
function spansOf(line: string): { text: string; bold: boolean }[] {
  const spans: { text: string; bold: boolean }[] = [];
  let at = 0;
  for (const m of line.matchAll(BOLD)) {
    if (m.index! > at) {
      spans.push({ text: line.slice(at, m.index), bold: false });
    }
    spans.push({ text: m[1], bold: true });
    at = m.index! + m[0].length;
  }
  if (at < line.length) {
    spans.push({ text: line.slice(at), bold: false });
  }
  return spans.length ? spans : [{ text: line, bold: false }];
}

/**
 * Parse the summary into renderable blocks.
 *
 * <p>A deliberately small subset: ATX headings, `-`/`*` bullets, `1.` numbered items, and
 * paragraphs. Blank lines are dropped. Anything else becomes a paragraph, which is the safe
 * default — the worst outcome is a line that reads a little flat.
 */
export function parseSummary(summary: string | null | undefined): SummaryBlock[] {
  if (!summary) {
    return [];
  }
  const blocks: SummaryBlock[] = [];
  for (const raw of summary.split('\n')) {
    const line = raw.trimEnd();
    if (!line.trim()) {
      continue;
    }
    const body0 = line.trim();
    // A thematic break ("---", "***") is a divider, not a sentence. Dropping it beats
    // showing the model's punctuation as though it were text.
    if (body0.length >= 3 && /^([-*_])\1*$/.test(body0)) {
      continue;
    }
    const indent = line.length - line.trimStart().length;
    const depth = Math.min(Math.floor(indent / 2), 3);
    const body = line.trim();
    // Matched with string operations rather than regex on purpose: these run over text a
    // language model produced, so a pathological line is attacker-influenceable input.
    // `^#{1,6}\s+(.*)$` and friends backtrack super-linearly; scanning does not.
    const marker = leadingMarker(body);
    if (marker) {
      blocks.push({ kind: marker.kind, spans: spansOf(marker.rest), depth: marker.kind === 'heading' ? 0 : depth });
      continue;
    }
    blocks.push({ kind: 'paragraph', spans: spansOf(body), depth });
  }
  return blocks;
}

/** Normalise the server's severity, defaulting unknown values to the mildest. */
export function severityOf(finding: Finding): Severity {
  const s = (finding.severity ?? '').toLowerCase();
  return s === 'critical' || s === 'warning' ? s : 'info';
}

/** Critical first, then warning, then info; stable within a severity. */
export function sortFindings(findings: Finding[]): Finding[] {
  const rank: Record<Severity, number> = { critical: 0, warning: 1, info: 2 };
  return [...findings].sort((a, b) => rank[severityOf(a)] - rank[severityOf(b)]);
}

/**
 * A one-line count, e.g. "2 critical, 1 warning".
 *
 * <p>Says "No problems found" rather than "0 findings" when there are none: the absence of
 * findings is the good outcome and should read like one.
 */
export function countLine(findings: Finding[]): string {
  if (!findings.length) {
    return 'No problems found.';
  }
  const counts: Record<Severity, number> = { critical: 0, warning: 0, info: 0 };
  for (const f of findings) {
    counts[severityOf(f)] += 1;
  }
  const parts = (['critical', 'warning', 'info'] as Severity[])
    .filter((s) => counts[s] > 0)
    .map((s) => `${counts[s]} ${s}`);
  return parts.join(', ');
}
