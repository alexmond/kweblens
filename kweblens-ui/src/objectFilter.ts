import { objName, objNs, objStateLabel } from './kube';
import type { KubeObject } from './types';

/**
 * The list header's filter language — parser and predicate, no DOM.
 *
 * <p>Roadmap item 6 (the T3 remainder). The header used to run three `.includes()` calls over
 * name, namespace and kind, so there was no way to say "not this", "matching this shape", or
 * "carrying this label" — the three things an operator asks a list of 3 000 pods.
 *
 * <p><b>This is deliberately client-side.</b> GH#263 refused a server-side `limit` on the
 * search path for one reason: a filter applied over a truncated page reports "no matches" for
 * an object that exists, which is a wrong answer delivered confidently. The list endpoint
 * fetches the whole collection (chunked internally since #302 — invisible to the client, and
 * it does not truncate), so a filter that runs here sees every object and cannot lie. Do not
 * move any of this to a query parameter.
 *
 * <p><b>A broken pattern is a sentence, not zero rows.</b> Every failure below produces a
 * message naming what is wrong, and {@link matchesFilter} then matches <i>everything</i> — a
 * filter that could not be compiled is not in force, so the list must not pretend it narrowed
 * anything. "No pods match" and "your pattern is broken" are different claims (#306, #316).
 *
 * <h2>The grammar</h2>
 *
 * A query is whitespace-separated <b>terms</b>, and every one of them must match (AND) — the
 * same conjunction `kubectl -l a=b,c=d` uses. Any term can be negated with a leading `-`.
 *
 * <ul>
 * <li><b>bare word</b> — case-insensitive substring over name, namespace and kind. This is
 * exactly what the box did before and is the common case; nothing about it changed.
 * <li><b>{@code "two words"}</b> — the same, for text with a space in it.
 * <li><b>{@code /regex/}</b> — a JavaScript regex over the same three fields, case-insensitive.
 * Opt-in via the slashes, because a bare `.` or `*` in a substring search has to keep meaning
 * itself.
 * <li><b>{@code name:} {@code ns:} {@code kind:}</b> — the same text matching against one
 * field only; the value may itself be a `/regex/` or a `"quoted string"`.
 * <li><b>{@code status:}</b> — the state the SERVER put the object in, matched
 * <b>exactly</b> (case-insensitively). See below.
 * <li><b>label requirements</b> — `app=web`, `app==web`, `app!=db`, `env in (dev,stage)`,
 * `tier notin (frontend)`, and `label:app` / `-label:app` for presence and absence.
 * </ul>
 *
 * <h2>`status:` is exact, and that is deliberate</h2>
 *
 * The other fields are free text about which a substring is the useful question — half a
 * pod name, part of a namespace. A state is not free text: it is drawn from a small closed
 * vocabulary the server publishes on the row (`kweblensState`, from `StatusVocabulary`),
 * and it is the same value an overview card counted. `status:` therefore compares the
 * whole label, because the ONE property this term exists to have is that the card's number
 * and the rows it selects are the same set — and a substring match breaks it silently the
 * first time two states share a stem (`Complete` would take `Completed` with it, and every
 * `…BackOff` would answer to `Backoff`). A `/regex/` value is still accepted for the
 * genuinely fuzzy question — `status:/backoff/` is every backoff state — so nothing is
 * lost except the way of getting a wrong count without noticing. A `"quoted"` value is
 * exact too; quotes are how a state with a space in it is written.
 *
 * <p>A row whose kind kweblens has no verdict for carries no state at all, and matches no
 * `status:` term. That is not a claim that it is healthy — it is the absence of a claim.
 *
 * <h2>Label semantics are Kubernetes', not ours</h2>
 *
 * Normalised to `apimachinery`'s `labels.Requirement.Matches`, including the part people get
 * wrong: <b>`!=` and `notin` match an object that does not carry the key at all</b>
 * (`environment!=production` selects resources with no `environment` label — the upstream docs
 * say so explicitly). `=` and `in` require the key to be present. Label values compare
 * <b>exactly</b>, case and all, like `kubectl -l`; only the free-text matching is
 * case-insensitive.
 *
 * <h2>Where this is knowingly a subset</h2>
 *
 * Stated here and in the UI's help popover rather than failing quietly:
 * <ul>
 * <li>Kubernetes writes label presence as a bare `partition` and absence as `!partition`. Here
 * they are `label:partition` and `-label:partition`, because a bare word is a text search and
 * always was — `nginx` cannot start meaning "has a label named nginx" without breaking the one
 * thing that must not change.
 * <li>The numeric requirements `key>1` / `key<1` are not supported. A token shaped like one is
 * an error rather than a silent substring search for `key>1`.
 * <li>`kubectl`'s field selectors (`--field-selector status.phase=Running`) are not a general
 * feature here: arbitrary JSON paths are not addressable. `status:` is the one field selector
 * that is, and it selects on kweblens' own state vocabulary rather than on `status.phase` —
 * which is the point, since the phase of a pod stuck on its image is `Pending` and its state
 * is `ImagePullBackOff`, and the phase of a finished one is `Succeeded` while the card that
 * counted it says `Completed`.
 * </ul>
 */

/** A test against one string value — a substring or a compiled regex, decided at parse time. */
type TextMatcher = (value: string) => boolean;

/** The fields a `name:` / `ns:` / `kind:` term can address. */
type FieldName = 'name' | 'namespace' | 'kind';

interface TextAtom {
  type: 'text';
  match: TextMatcher;
}
interface FieldAtom {
  type: 'field';
  field: FieldName;
  match: TextMatcher;
}
/**
 * A `status:` term. Separate from {@link FieldAtom} because it is a different question, not a
 * fourth field: it reads the server's computed state rather than a property of the manifest,
 * and it matches the whole label rather than a substring of it (see the header).
 */
interface StatusAtom {
  type: 'status';
  match: TextMatcher;
}
/**
 * One label requirement, normalised to the three operators apimachinery really has.
 *
 * <p>`=` and `==` collapse into `in` with a single value, `!=` into `notin` with a single
 * value. That is not a shortcut — it is how `labels.Requirement` itself is written, and
 * collapsing here is what stops the "key absent" rule being implemented twice and
 * inconsistently.
 */
interface LabelAtom {
  type: 'label';
  key: string;
  op: 'in' | 'notin' | 'exists';
  values: string[];
}
type Atom = TextAtom | FieldAtom | StatusAtom | LabelAtom;

/** One parsed term: an atom, and whether a leading `-` inverted it. */
interface FilterTerm {
  negated: boolean;
  atom: Atom;
}

/**
 * A parsed query.
 *
 * <p>`error` and `terms` are exclusive: a query that did not parse yields no terms, so nothing
 * can accidentally apply half a filter.
 */
export interface ParsedFilter {
  terms: FilterTerm[];
  /** What is wrong with the query, in a sentence for the operator, or null. */
  error: string | null;
}

/** Thrown by the parser internals; caught once in {@link parseFilter} and turned into `error`. */
class FilterError extends Error {}

const FIELDS: Record<string, FieldName> = {
  name: 'name',
  ns: 'namespace',
  namespace: 'namespace',
  kind: 'kind',
};

// A label key: Kubernetes' own shape, permissive about the optional `prefix/` part. Split in
// two because the single `^x([-x]*x)?$` spelling backtracks super-linearly (sonarjs), and a
// filter box is fed a character at a time.
const LABEL_KEY = /^[A-Za-z0-9][-A-Za-z0-9_./]*$/;
const LABEL_KEY_END = /[A-Za-z0-9]$/;

// Every alternation below is anchored and uses explicit character classes rather than a lazy
// `\S+?`, for the same reason.
const FIELD_TERM = /^([A-Za-z]+):(.*)$/s;
const SET_TERM = /^(?:label:)?([^\s()]+)\s+(in|notin)\s*\((.*)\)$/s;
const NUMERIC_TERM = /^(?:label:)?([^\s<>]+)([<>])([^\s<>]+)$/;
const CMP_TERM = /^(?:label:)?([^\s=!<>]+)(!=|==|=)(.*)$/s;
const EXISTS_TERM = /^label:(\S*)$/;

// --- tokenising ---------------------------------------------------------------------------

/** Read a `"quoted"` or `/regex/` run, delimiters included; throws when it never closes. */
function readDelimited(query: string, from: number, delim: string, what: string): { text: string; next: number } {
  const end = query.indexOf(delim, from + 1);
  if (end < 0) {
    throw new FilterError(`Unterminated ${what} — add a closing ${delim}`);
  }
  return { text: query.slice(from, end + 1), next: end + 1 };
}

/** Read a `( … )` value list, parens included; whitespace inside it does not end the token. */
function readGroup(query: string, from: number): { text: string; next: number } {
  const end = query.indexOf(')', from + 1);
  if (end < 0) {
    throw new FilterError('Unterminated ( — add a closing )');
  }
  return { text: query.slice(from, end + 1), next: end + 1 };
}

/**
 * Split a query into terms on whitespace, keeping quoted text, `/regex/` and `(a,b)` whole.
 *
 * <p>A `/` only opens a regex at the start of a term or straight after a `name:`-style prefix.
 * Anywhere else it is an ordinary character, so searching for a string that happens to contain
 * a slash does not silently become a regex.
 */
function tokenize(query: string): string[] {
  const tokens: string[] = [];
  let i = 0;
  while (i < query.length) {
    if (/\s/.test(query[i])) {
      i++;
      continue;
    }
    const token = readToken(query, i);
    tokens.push(token.text);
    i = token.next;
  }
  return tokens;
}

/** The delimited run starting here, or null when this character is an ordinary one. */
function openRun(query: string, at: number, buf: string): { text: string; next: number } | null {
  const c = query[at];
  if (c === '"') {
    return readDelimited(query, at, '"', 'quote');
  }
  if (c === '(') {
    return readGroup(query, at);
  }
  if (c === '/' && (buf === '' || buf === '-' || buf.endsWith(':'))) {
    return readDelimited(query, at, '/', '/regex/');
  }
  return null;
}

/** One term: everything up to the next top-level whitespace. */
function readToken(query: string, from: number): { text: string; next: number } {
  let buf = '';
  let i = from;
  while (i < query.length && !/\s/.test(query[i])) {
    const run = openRun(query, i, buf);
    if (run) {
      buf += run.text;
      i = run.next;
    } else {
      buf += query[i];
      i++;
    }
  }
  return { text: buf, next: i };
}

/**
 * Re-join `key in (a,b)` — three tokens to the splitter, one requirement to Kubernetes.
 *
 * <p>Handles `key in (a,b)`, `key in(a,b)` and `key notin (a, b)`. A stray `in` with no value
 * list after it is left alone and ends up as an ordinary text term, which is what someone
 * typing the English word meant.
 */
function mergeSetOperators(tokens: string[]): string[] {
  const out: string[] = [];
  for (let i = 0; i < tokens.length; i++) {
    const m = /^(in|notin)(\(.*\))?$/.exec(tokens[i]);
    const prev = out[out.length - 1];
    const group = m?.[2] ?? (tokens[i + 1]?.startsWith('(') ? tokens[i + 1] : undefined);
    if (m && prev !== undefined && group !== undefined) {
      out[out.length - 1] = `${prev} ${m[1]} ${group}`;
      if (!m[2]) {
        i++;
      }
      continue;
    }
    out.push(tokens[i]);
  }
  return out;
}

// --- term parsing -------------------------------------------------------------------------

/** The compiled matcher for a `/regex/` value, or null when the value is not one. */
function regexMatcher(raw: string): TextMatcher | null {
  if (!raw.startsWith('/') || !raw.endsWith('/') || raw.length < 2) {
    return null;
  }
  const source = raw.slice(1, -1);
  if (!source) {
    throw new FilterError('Empty regex — // needs a pattern between the slashes');
  }
  let re: RegExp;
  try {
    re = new RegExp(source, 'i');
  } catch (e) {
    // V8 prefixes its reason with the pattern it was already handed — "Invalid regular
    // expression: /vmstack(/i: Unterminated group" — so quoting it whole printed the
    // pattern twice and leaked the `i` flag the operator never typed. Strip that prefix
    // when it is there and keep whatever the engine actually said; other engines word it
    // differently and are passed through unchanged.
    const reason = (e as Error).message.replace(/^Invalid regular expression: \/.*\/[a-z]*: /, '');
    throw new FilterError(`Invalid regex /${source}/ — ${reason}`);
  }
  return (v) => re.test(v);
}

/** `"quoted text"` unwrapped, or the value as typed. */
function unquote(raw: string): string {
  return raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2 ? raw.slice(1, -1) : raw;
}

/** A `/regex/`, a `"quoted string"`, or a bare substring — compiled once, here. */
function textMatcher(raw: string): TextMatcher {
  const re = regexMatcher(raw);
  if (re) {
    return re;
  }
  const needle = unquote(raw).toLowerCase();
  if (!needle) {
    throw new FilterError('Empty search text — remove it, or quote the text you meant');
  }
  return (v) => v.toLowerCase().includes(needle);
}

/**
 * A `status:` value: a `/regex/`, or the WHOLE state label, compared case-insensitively.
 *
 * <p>Exact rather than substring on purpose — see the header. The empty case gets its own
 * sentence rather than "Empty search text", because `status:` with nothing after it is usually
 * a half-typed query and the useful reply names a state.
 */
function statusMatcher(raw: string): TextMatcher {
  const re = regexMatcher(raw);
  if (re) {
    return re;
  }
  const wanted = unquote(raw).toLowerCase();
  if (!wanted) {
    throw new FilterError('Missing state after “status:” — write it as status:Running');
  }
  return (v) => v.toLowerCase() === wanted;
}

/**
 * Can this state label be written as a `status:` term that selects exactly it?
 *
 * <p>The grammar has no escape character: a `"` inside a quoted value ends the quote, and there
 * is no other way to protect one. A label carrying one is therefore not expressible, and the
 * answer is to say so — a term built from it would parse as something else and select the wrong
 * rows, which is the one failure mode this whole family of links exists to avoid (#336). The
 * caller renders such a state as text rather than a link. No state in `StatusVocabulary` is
 * shaped like that today; this is what keeps that from becoming a silent assumption.
 */
export function statusQueryable(label: string): boolean {
  return label.trim().length > 0 && !label.includes('"');
}

/**
 * The query that selects exactly the objects in this state — the writer's half of `status:`.
 *
 * <p>It lives beside {@link statusMatcher} on purpose. The card's number and the rows the link
 * opens are the same set only while the way a state is WRITTEN and the way it is READ agree
 * about quoting, and two files cannot be made to agree about that; one can.
 *
 * <p>Quoted only when the label contains whitespace, because whitespace is what ends a term.
 * Everything else in the vocabulary — `CrashLoopBackOff`, `ImagePullBackOff` — is a bare word
 * and reads better as one in the filter box the reader lands on.
 */
export function statusQuery(label: string): string {
  return /\s/.test(label) ? `status:"${label}"` : `status:${label}`;
}

function labelKey(raw: string): string {
  const key = raw.startsWith('label:') ? raw.slice('label:'.length) : raw;
  if (!key) {
    throw new FilterError('Missing label key — write it as key=value');
  }
  if (!LABEL_KEY.test(key) || !LABEL_KEY_END.test(key)) {
    throw new FilterError(`“${key}” is not a valid label key`);
  }
  return key;
}

function setAtom(m: RegExpExecArray): LabelAtom {
  const values = m[3]
    .split(',')
    .map((v) => v.trim())
    .filter((v) => v.length > 0);
  if (values.length === 0) {
    throw new FilterError(`“${m[1]} ${m[2]} ()” has no values — list at least one inside the parentheses`);
  }
  return { type: 'label', key: labelKey(m[1]), op: m[2] === 'in' ? 'in' : 'notin', values };
}

/**
 * A `prefix:value` term's atom, or null when the prefix is `label:` — which is not a field but
 * the opening of a label requirement, and so belongs to the branches after this one.
 *
 * <p>An unrecognised prefix throws. Left as a substring search it would look for a colon in a
 * name that cannot contain one — zero rows, and no way to tell that from a genuinely empty
 * result.
 */
function fieldAtom(prefix: string, value: string): Atom | null {
  if (FIELDS[prefix]) {
    return { type: 'field', field: FIELDS[prefix], match: textMatcher(value) };
  }
  if (prefix === 'status') {
    return { type: 'status', match: statusMatcher(value) };
  }
  if (prefix !== 'label') {
    throw new FilterError(`Unknown field “${prefix}:” — the fields are name:, ns:, kind:, status: and label:`);
  }
  return null;
}

/**
 * One term's atom. Order matters: a delimited run is settled before anything looks for an
 * operator inside it, or the `=` in `/a=b/` would be read as a label requirement.
 */
function parseAtom(atom: string): Atom {
  if (atom.startsWith('/') || atom.startsWith('"')) {
    return { type: 'text', match: textMatcher(atom) };
  }
  if (atom.startsWith('=') || atom.startsWith('!=')) {
    throw new FilterError(`Missing label key before “${atom.startsWith('!=') ? '!=' : '='}” — write it as key=value`);
  }
  const field = FIELD_TERM.exec(atom);
  const named = field ? fieldAtom(field[1], field[2]) : null;
  if (named) {
    return named;
  }
  const set = SET_TERM.exec(atom);
  if (set) {
    return setAtom(set);
  }
  const numeric = NUMERIC_TERM.exec(atom);
  if (numeric) {
    throw new FilterError(`Numeric label requirements (${numeric[2]}) are not supported — use =, !=, in or notin`);
  }
  const cmp = CMP_TERM.exec(atom);
  if (cmp) {
    return { type: 'label', key: labelKey(cmp[1]), op: cmp[2] === '!=' ? 'notin' : 'in', values: [cmp[3]] };
  }
  const exists = EXISTS_TERM.exec(atom);
  if (exists) {
    return { type: 'label', key: labelKey(exists[1]), op: 'exists', values: [] };
  }
  return { type: 'text', match: textMatcher(atom) };
}

function parseTerm(token: string): FilterTerm {
  const negated = token.startsWith('-');
  const atom = negated ? token.slice(1) : token;
  if (!atom) {
    throw new FilterError('A bare “-” negates nothing — put the term to exclude straight after it');
  }
  return { negated, atom: parseAtom(atom) };
}

/**
 * Parse a filter query. Never throws: a bad query comes back as `error` with no terms.
 *
 * <p>Cheap enough to call on every keystroke — it walks the query string, not the object list,
 * and every regex is compiled here rather than once per row.
 */
export function parseFilter(query: string): ParsedFilter {
  const trimmed = query.trim();
  if (!trimmed) {
    return { terms: [], error: null };
  }
  try {
    return { terms: mergeSetOperators(tokenize(trimmed)).map(parseTerm), error: null };
  } catch (e) {
    if (e instanceof FilterError) {
      return { terms: [], error: e.message };
    }
    throw e;
  }
}

// --- matching -----------------------------------------------------------------------------

function fieldValue(o: KubeObject, field: FieldName): string {
  if (field === 'name') {
    return objName(o);
  }
  return field === 'namespace' ? (objNs(o) ?? '') : (o.kind ?? '');
}

/** `labels.Requirement.Matches`, transcribed: `notin` is true when the key is absent. */
function labelMatches(labels: Record<string, string>, atom: LabelAtom): boolean {
  const present = Object.prototype.hasOwnProperty.call(labels, atom.key);
  if (atom.op === 'exists') {
    return present;
  }
  if (!present) {
    return atom.op === 'notin';
  }
  const has = atom.values.includes(labels[atom.key]);
  return atom.op === 'in' ? has : !has;
}

function atomMatches(o: KubeObject, atom: Atom): boolean {
  if (atom.type === 'text') {
    return atom.match(objName(o)) || atom.match(objNs(o) ?? '') || atom.match(o.kind ?? '');
  }
  if (atom.type === 'field') {
    return atom.match(fieldValue(o, atom.field));
  }
  if (atom.type === 'status') {
    // '' when the server computed no state for this kind. Guarded rather than passed to the
    // matcher, so no pattern — not even /^$/ or a regex that matches everything — can select
    // an object on the strength of a verdict nobody reached.
    const state = objStateLabel(o);
    return state !== '' && atom.match(state);
  }
  return labelMatches(o.metadata?.labels ?? {}, atom);
}

/**
 * Does this object satisfy every term?
 *
 * <p>A filter that failed to parse matches everything. That is the whole "a broken pattern is
 * not zero rows" rule, in one line: the alternative is a list that looks filtered and is
 * hiding rows for a reason the reader can only guess at.
 */
export function matchesFilter(o: KubeObject, filter: ParsedFilter): boolean {
  if (filter.error !== null) {
    return true;
  }
  return filter.terms.every((t) => t.negated !== atomMatches(o, t.atom));
}

/**
 * The query as far as the header's count and empty state are concerned.
 *
 * <p>Empty when the filter did not parse, because nothing was narrowed: "0 of 137" over a full
 * table, or "no pods match “app=”", would both be the header contradicting what is on screen.
 */
export function activeQuery(query: string, filter: ParsedFilter): string {
  return filter.error === null ? query : '';
}

// --- writing a term back into the query -------------------------------------------------------

/**
 * The query with its positive `status:` terms replaced by `label` — or, with `null`, removed.
 *
 * <p><b>Why this lives in the grammar module.</b> It is the parser's inverse, and it needs the
 * same tokenizer: knowing where one term ends is exactly the knowledge that keeps
 * `name:"two words"` and `env in (dev,stage)` whole while a term beside them is dropped. A
 * second splitter written next to a caller would be a copy of that rule, and the copy is what
 * goes stale.
 *
 * <p><b>Why it replaces rather than appends.</b> Terms are ANDed — that is the grammar, and
 * the one thing it has no spelling for is "either of these". `status:Running status:Failed`
 * selects nothing at all, so a chip rail that appended would answer a second click with an
 * empty table. Replacing makes the positive status selection single-valued, which is what the
 * grammar can actually express.
 *
 * <p><b>Negated status terms are left alone</b>, because `-status:Running` is a different
 * question — "everything except the healthy ones" — and it ANDs with a positive term perfectly
 * well. Every other term (names, namespaces, labels, regexes) is untouched, so the box keeps
 * whatever else was typed.
 *
 * <p>A query that does not even tokenize (an unterminated quote) is returned unchanged: there is
 * no term structure to edit, and inventing one would rewrite what the operator is mid-way
 * through typing.
 *
 * <p>The term itself comes from {@link statusQuery} rather than being spelled here. A chip that
 * quoted a state differently from the card that links to it would be a second answer to the one
 * question this family of links exists to settle.
 */
export function withStatusTerm(query: string, label: string | null): string {
  let tokens: string[];
  try {
    tokens = tokenize(query);
  } catch {
    return query;
  }
  const kept = tokens.filter((t) => !t.startsWith('status:'));
  if (label !== null) {
    kept.push(statusQuery(label));
  }
  return kept.join(' ');
}

// --- the help the popover renders -----------------------------------------------------------

export interface FilterHelpRow {
  example: string;
  meaning: string;
}

/**
 * The syntax, as the operator reads it.
 *
 * <p>A filter language nobody can find is a filter language nobody uses, and the placeholder
 * cannot hold this. Kept next to the parser so an example that stops being true fails a test
 * here rather than lingering in a template.
 */
export const FILTER_HELP: FilterHelpRow[] = [
  { example: 'nginx', meaning: 'name, namespace or kind contains “nginx” (what the box always did)' },
  { example: '-nginx', meaning: 'everything that does NOT match — any term takes a leading “-”' },
  { example: 'web prod', meaning: 'both must match; terms are separated by spaces and ANDed' },
  { example: '"two words"', meaning: 'text with a space in it' },
  { example: '/^web-\\d+$/', meaning: 'regex over name, namespace and kind (case-insensitive)' },
  { example: 'ns:kube-system', meaning: 'one field only — also name: and kind:' },
  { example: 'name:/^web/', meaning: 'a field matched by regex' },
  { example: 'status:Pending', meaning: 'the state an overview card counts — the whole word, not part of it' },
  { example: 'status:/backoff/', meaning: 'states matched loosely — every kind of backoff' },
  { example: 'app=web', meaning: 'label equality — also app==web' },
  { example: 'app!=db', meaning: 'label differs, or the object has no app label (as in kubectl)' },
  { example: 'env in (dev,stage)', meaning: 'set membership — also notin' },
  { example: 'label:app', meaning: 'the label exists; -label:app for objects without it' },
];

/** Where this is knowingly narrower than `kubectl -l`, said out loud rather than left to be discovered. */
export const FILTER_HELP_NOTES: string[] = [
  'Text matching is case-insensitive; label values compare exactly, like kubectl.',
  'Kubernetes writes label presence as “partition” and absence as “!partition”. Here they are label:partition and -label:partition, because a bare word stays a text search.',
  'status: is the state the server computed — the one the overview cards count, so a card’s number and the rows it selects are the same objects. The whole label has to match, or “Complete” would take “Completed” with it and those two numbers would stop agreeing.',
  'A kind the server has no verdict for carries no state, and no status: term selects it — the absence of a claim, not a claim of health.',
  'Numeric label requirements (key>1, key<1) are not supported, and neither are arbitrary field selectors (status.phase=Running) — status: is the one there is.',
  'The filter runs over every object the server sent — nothing is truncated, so a term that matches nothing really matches nothing.',
];
