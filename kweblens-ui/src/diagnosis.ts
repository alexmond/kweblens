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
  /** Present only when an analysis was run AND still describes these exact findings. */
  summary?: string | null;
  aiEnriched?: boolean;
  /** ISO instant this scope was last analysed — the summary's age, or the stale one's. */
  analysedAt?: string | null;
  /** This scope was analysed, but the findings changed, so the old reading is withheld. */
  summaryOutdated?: boolean;
  /** Whether the server could run an analysis at all (AI enabled and a key configured). */
  aiAvailable?: boolean;
}

/**
 * What the panel can say about the LLM summary, and therefore what it offers.
 *
 * <p>`unavailable` covers both "this server has no model" and "there is nothing to
 * summarise": in either case a trigger would be a button that cannot help, which is worse
 * than no button. The other three are the honest states of a cache keyed on the findings —
 * never analysed, analysed and still applicable, analysed and overtaken by the cluster.
 */
export type AnalysisState = 'unavailable' | 'never' | 'current' | 'outdated';

export function analysisState(result: DiagnoseResult | null | undefined): AnalysisState {
  if (!result || result.aiAvailable !== true || result.findings.length === 0) {
    return 'unavailable';
  }
  if (result.summary) {
    return 'current';
  }
  return result.summaryOutdated === true ? 'outdated' : 'never';
}

/**
 * The trigger's label. "Re-analyse" whenever this scope has been analysed before — including
 * when that reading has been overtaken, because the user is repeating something they already
 * did, and calling it "Analyse" would hide that a call was already spent here.
 */
export function analyseLabel(state: AnalysisState): string {
  return state === 'current' || state === 'outdated' ? 'Re-analyse' : 'Analyse';
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * How long ago an instant was, in words — the whole point of point 4 of the issue.
 *
 * <p>Deliberately coarse and never precise-looking: the reader needs "is this from before
 * the rollout I just did", not a duration. A future timestamp (clock skew between the
 * browser and a server in another zone) reads as "just now" rather than as a negative age.
 */
export function relativeAge(iso: string | null | undefined, now: number = Date.now()): string | null {
  if (!iso) {
    return null;
  }
  const at = Date.parse(iso);
  if (Number.isNaN(at)) {
    return null;
  }
  const ago = Math.max(0, now - at);
  if (ago < 45_000) {
    return 'just now';
  }
  if (ago < 90 * MINUTE) {
    return plural(Math.round(ago / MINUTE), 'minute');
  }
  if (ago < 36 * HOUR) {
    return plural(Math.round(ago / HOUR), 'hour');
  }
  return plural(Math.round(ago / DAY), 'day');
}

function plural(n: number, unit: string): string {
  return `${n} ${unit}${n === 1 ? '' : 's'} ago`;
}

/**
 * The sentence under the summary, or the one that replaces it.
 *
 * <p>A summary that reads as current when it predates a rollout is worse than none, so the
 * two cases that need saying out loud both do: a shown summary carries its age, and a
 * withheld one says why it is missing instead of silently reverting to "never analysed".
 */
export function analysisNote(result: DiagnoseResult | null | undefined, now: number = Date.now()): string | null {
  const state = analysisState(result);
  const age = relativeAge(result?.analysedAt, now);
  if (state === 'current') {
    const when = age ? `, ${age}` : '';
    return `Written by a language model from the findings below${when}.`;
  }
  if (state === 'outdated') {
    const when = age ? ` ${age}` : '';
    return `The findings have changed since this was analysed${when}, so that summary is no longer shown.`;
  }
  return null;
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

/** Findings sharing a title, kept together so one repeated check cannot crowd out the rest. */
export interface FindingGroup {
  title: string;
  severity: Severity;
  findings: Finding[];
}

/**
 * Group findings by title, worst severity first.
 *
 * <p>Why: on a real cluster one check produced 15 of 22 critical findings — every Service
 * with no endpoints, most of them a monitoring stack aimed at control-plane components that
 * are not pods on this distribution, plus a test namespace that is not deployed. Fifteen
 * rows saying the same thing pushed the ImagePullBackOff and the OOM kill off the top of
 * the list, which are the two anyone would act on.
 *
 * <p>Grouping is deliberately not filtering. Nothing is hidden and no severity is
 * reinterpreted — the fifteen are still there, still critical, still individually listed
 * under their heading. They just occupy one heading instead of fifteen.
 */
export function groupFindings(findings: Finding[]): FindingGroup[] {
  const rank: Record<Severity, number> = { critical: 0, warning: 1, info: 2 };
  const byTitle = new Map<string, FindingGroup>();
  for (const finding of sortFindings(findings)) {
    const existing = byTitle.get(finding.title);
    if (existing) {
      existing.findings.push(finding);
      continue;
    }
    byTitle.set(finding.title, { title: finding.title, severity: severityOf(finding), findings: [finding] });
  }
  return [...byTitle.values()].sort((a, b) => rank[a.severity] - rank[b.severity]);
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
