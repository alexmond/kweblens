export const meta = {
  name: 'kweblens-harden-audit',
  description: 'Adversarially-verified correctness audit across six dimensions the build gate cannot see',
  phases: [
    { title: 'Find', detail: 'six independent auditors, one per dimension' },
    { title: 'Verify', detail: 'refute each finding against the code' },
    { title: 'Synthesise', detail: 'rank what survived, propose the gate' },
  ],
}

const REPO = '/home/alexm/IdeaProjects/kweblens'

const COMMON = `
You are auditing the kweblens repo at ${REPO}. Read CLAUDE.md first.

READ-ONLY. Do not edit, commit, or open PRs. You are finding and evidencing defects, not fixing them.

Context that shapes what matters:
- The project is in a HARDEN-AND-SHIP phase (see docs/design/roadmap.md, re-cut in PR #295). Not a feature phase.
- ADR-001: a single trusted operator. Multi-tenancy is NOT a goal. Do not report "no per-user identity" as a gap — it is a decided position.
- The seed for this whole audit: HelmResourcesModal.vue used <ErrorNotice> WITHOUT importing it, so its error path rendered nothing at all — and it passed BOTH vue-tsc AND eslint. The build gate cannot see that class of defect. Assume there are more like it.

EVIDENCE RULES — a finding without these is worthless:
- Cite file:line. Quote the code.
- State the CONCRETE user-visible consequence: what does someone see or lose?
- If you cannot demonstrate the consequence from the code, mark confidence "low" and say why.
- Do NOT report style preferences, "could be refactored", or anything already documented as a deliberate decision. Check CLAUDE.md's gotchas before calling something a bug.
- Prefer five real defects over fifty speculative ones.
`

const FINDING_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['dimension', 'findings'],
  properties: {
    dimension: { type: 'string' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['title', 'file', 'line', 'evidence', 'consequence', 'confidence'],
        properties: {
          title: { type: 'string', description: 'One sentence, the defect itself' },
          file: { type: 'string' },
          line: { type: 'integer' },
          evidence: { type: 'string', description: 'Quoted code and why it is wrong' },
          consequence: { type: 'string', description: 'What a user sees or loses' },
          confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
        },
      },
    },
  },
}

const VERDICT_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['real', 'reasoning'],
  properties: {
    real: { type: 'boolean' },
    reasoning: { type: 'string' },
    severity: { type: 'string', enum: ['high', 'medium', 'low'] },
    correction: { type: 'string', description: 'If the claim is partly wrong, what is actually true' },
  },
}

const DIMENSIONS = [
  {
    key: 'templates',
    prompt: `${COMMON}
DIMENSION: Vue templates that reference things which do not resolve.

This is the dimension the seed defect came from, so start here and be thorough.
Hunt for, across every .vue file in kweblens-ui/src:
- components used in a template but never imported in <script setup> (the ErrorNotice bug)
- props passed to a child that the child does not declare
- events emitted that no parent handles, or handled that are never emitted
- template refs, slots or directives that do not exist
- v-if/v-else chains that can render nothing when they should render a state

For each, prove the runtime consequence. A component that does not resolve renders NOTHING and only warns at runtime — say what the user sees instead.`,
  },
  {
    key: 'error-empty',
    prompt: `${COMMON}
DIMENSION: Error and empty states (roadmap item T4).

The re-cut roadmap says this is "still true, worse": ErrorNotice used in 8 files, ~11 bare <div class="error"> with no retry, ~20 ad-hoc empty sites under 5 class names, no EmptyState component, and ResourceTable.vue has no #empty slot.

Verify those counts yourself against the code — do not trust them — then find the cases that MATTER: where a failure or an empty result leaves the user with no information and no way forward. Rank by how stuck the user is, not by count. A silent failure is worse than an ugly one.`,
  },
  {
    key: 'contract',
    prompt: `${COMMON}
DIMENSION: Drift between the server's JSON and the client's types.

Compare kweblens-ui/src/types.ts (and any inline shapes) against the Java DTOs and controllers in kweblens-web/src/main/java/.../web/api/. Look for:
- fields the client reads that the server never sends (undefined at runtime, often rendering as blank or NaN)
- fields the server sends that nothing consumes (dead payload)
- optionality mismatches: client assumes required, server can omit
- enum/string values the client switches on that the server can produce outside that set

NOTE a recent change that makes this live: PR #279 added ListProjection, which nulls Secret/ConfigMap data VALUES in list payloads while keeping keys. Check every consumer of those fields copes with a null value.`,
  },
  {
    key: 'dead',
    prompt: `${COMMON}
DIMENSION: Code that cannot execute.

PR #272 removed 10 CSS rules no element carried, and PR #294 removed a screenshot of a deleted feature. Find what is left:
- CSS selectors in styles.css that no template can produce (check dynamic class bindings too, not just literal class= attributes)
- exported TS symbols with no importer (knip should catch these — if you find one it did not, that is itself a finding about the gate)
- Java methods/endpoints with no caller
- config properties defined but never read

Be careful: a selector may be produced dynamically, and a Java method may be called reflectively or by Spring. Prove absence before claiming it.`,
  },
  {
    key: 'docs-claims',
    prompt: `${COMMON}
DIMENSION: Documentation that the code contradicts.

PR #295 found the README claimed "✅ Maven Central" for artifacts that 404 on repo1.maven.org — nothing has ever been tagged or released. That is the class: a confident claim in user-facing text that the code or the world does not support.

Audit README.md, CLAUDE.md, and docs/ for claims that are checkable and wrong: counts (tool counts, kind counts), capabilities, endpoints, defaults, install paths, version numbers, security properties. CHECK each one against the code or by probing. CLAUDE.md warns that Maven Central's search index lies — verify artifacts by fetching repo1.maven.org/maven2/org/alexmond/<artifact>/ directly.

A stale doc that UNDERSTATES the product counts too: PR #295 found the roadmap claimed 3 relation keys where the code has 12.`,
  },
  {
    key: 'resources',
    prompt: `${COMMON}
DIMENSION: Resources acquired and not released.

PR #283 and #288 found four leaks: SseEmitter learns of a disconnect only from a FAILED WRITE, so a quiet stream never notices; and fabric8's LogWatch.close() is a no-op on the watchLog() path because close() awaits a future that flavour never completes. SseKeepAlive and LogService.release fixed those, and SseEndpointKeepAliveTest guards new SSE endpoints.

Find what those passes did not cover:
- executors, threads, schedulers created but never shut down
- fabric8 clients, watches or streams acquired outside the paths already audited
- try-with-resources that should exist and does not; close() calls on paths that can throw before reaching them
- caches or collections that grow without bound (AuditService is deliberately bounded at 500 — that is decided, not a bug)
- anything holding a cluster-side resource per request

docs/design/watch-fanout.md carries the previous audit. Do not re-report what it already covers; find what it missed.`,
  },
]

phase('Find')
log(`Auditing ${DIMENSIONS.length} dimensions, each finding verified adversarially before it counts`)

const audited = await pipeline(
  DIMENSIONS,
  (d) => agent(d.prompt, { label: `find:${d.key}`, phase: 'Find', schema: FINDING_SCHEMA }),
  (result, dimension) => {
    if (!result || !result.findings || result.findings.length === 0) {
      log(`${dimension.key}: nothing found`)
      return []
    }
    log(`${dimension.key}: ${result.findings.length} claimed, verifying`)
    return parallel(
      result.findings.map((f) => () =>
        agent(
          `You are REFUTING a claimed defect in the kweblens repo at ${REPO}. Default to refuted=false only if you cannot break it.

CLAIM: ${f.title}
FILE: ${f.file}:${f.line}
EVIDENCE OFFERED: ${f.evidence}
CLAIMED CONSEQUENCE: ${f.consequence}

Open the file. Try hard to show the claim is WRONG. Specifically consider:
- Is the code actually reachable? Is the branch dead, or the component conditionally rendered such that the bug cannot occur?
- Is it a deliberate decision? Check CLAUDE.md's gotchas and docs/design/. ADR-001 makes single-operator assumptions CORRECT, not defects.
- Is something else already handling it — a parent, a wrapper, a global handler, a Spring default?
- Is the stated consequence overstated? "The user sees nothing" is very different from "the user sees a slightly wrong label".
- Did the finder misread the code?

Return real=true ONLY if you tried to refute it and could not. If it is real but the consequence was overstated, set real=true and put the accurate version in "correction". Assign severity by what the user actually loses.`,
          { label: `verify:${f.file.split('/').pop()}:${f.line}`, phase: 'Verify', schema: VERDICT_SCHEMA },
        ).then((v) => ({ ...f, dimension: dimension.key, verdict: v })),
      ),
    )
  },
)

const all = audited.flat().filter(Boolean)
const confirmed = all.filter((f) => f.verdict && f.verdict.real)
const refuted = all.filter((f) => f.verdict && !f.verdict.real)
log(`${confirmed.length} confirmed, ${refuted.length} refuted out of ${all.length} claims`)

if (confirmed.length === 0) {
  return { confirmed: [], refuted: refuted.length, summary: 'No claim survived adversarial verification.' }
}

phase('Synthesise')
const summary = await agent(
  `You are writing the conclusion of a correctness audit of kweblens (${REPO}), a Kubernetes IDE in a harden-and-ship phase.

These findings each survived an adversarial attempt to refute them:

${JSON.stringify(confirmed.map((f) => ({ dimension: f.dimension, title: f.title, at: `${f.file}:${f.line}`, consequence: f.consequence, severity: f.verdict.severity, correction: f.verdict.correction })), null, 1)}

${refuted.length} other claims were refuted and are excluded.

Write a report that:
1. Ranks the confirmed findings by what a USER actually loses — not by count and not by dimension.
2. Names any PATTERN across dimensions. The audit was seeded by a component used without an import that passed both vue-tsc and eslint; if several findings share a root cause or a common blind spot, that is the most valuable output here.
3. Proposes what would have CAUGHT each class automatically. Be concrete and honest about cost — a lint rule, a test, a build step. If the honest answer for something is "only review catches this", say so rather than inventing a gate.
4. Says plainly what is NOT worth fixing, if anything.

Be concise and specific. Cite file:line. Do not pad.`,
  { label: 'synthesise', phase: 'Synthesise' },
)

return { confirmed: confirmed.length, refuted: refuted.length, findings: confirmed, report: summary }
