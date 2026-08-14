---
name: security-trip-review
description: >-
  Use whenever the security-guidance plugin interrupts a turn — a Stop/commit/push review finding,
  an async rewake, or a pattern warning on an Edit. Classifies the trip as a true or false positive,
  acts on it, and then changes the rule set so the same benign trip cannot recur. Also the place to
  measure whether tripping is actually going down. Self-improving by rule: every trip appends a
  dated line to the log below, and a false positive that is not written down here WILL happen again.
---

# When the security hook trips

The goal is **fewer benign interruptions without catching less**. Those two move in opposite
directions if you tune by loosening, so the loop below never loosens a rule — it either records a
decision the reviewer could not infer, or it fixes real code.

## The metric

```bash
scripts/security-trips.sh            # this window: invocations, reviews, findings, wasted runs
scripts/security-trips.sh --since 2026-08-14
```

Reads `~/.claude/security/log.txt` (the plugin's own log — categories and diffstate metadata only,
no file contents). What matters is **benign trips per session**, not total hook invocations: the
hook firing silently costs nothing, the hook *interrupting* costs a round trip.

**Baseline, measured 2026-08-12 23:03 → 2026-08-13 22:18 (~23 h, the multi-agent session):**

| | |
|---|---|
| Hook invocations | 1111, of which **847 (76%) were PostToolUse/Bash** |
| Stop hooks | 44, of which **33 (75%) had an empty review set** |
| Completed reviews | 26 (10 diff, 8 Stop, 7 commit, 1 push sweep) |
| **Findings** | **0** |
| Git-baseline failures | 67 lines — the hook running in a non-git cwd (the scratchpad) |

Zero findings in 26 reviews is the number that justified acting. **It is not evidence the plugin
never works**: the one true positive we know of — a GitHub Actions workflow interpolating
`inputs.version` straight into a `run:` block — was caught on 2026-08-09, *before this log window
opens*. That catch is why nothing below disables detection.

## The three levers, in the order to reach for them

1. **Record a decision the reviewer cannot infer** → `.claude/claude-security-guidance.md`.
   The supported mechanism, loaded into the diff review's prompt. This is where "kweblens is a
   single trusted operator, so audit entries naming no person are ADR-001, not a defect" belongs.
   **Note the plugin's own limit: the agentic commit reviewer (layer 3) does NOT read this file.**
   So a systemic exclusion silences the Stop/diff review and not the commit review — if a benign
   finding keeps arriving from commit review specifically, the fix is an inline justification.
2. **Justify one line inline** → a comment on the line saying why it is safe. The LLM reviewer
   treats inline justifications as exclusions, and unlike the guidance file it travels with the
   code and is visible to human readers too. Prefer this for one-off cases.
3. **Narrow the surface structurally** → environment variables, project-scoped.
   `ENABLE_STOP_REVIEW=0` is set in `.claude/settings.json` for this repo, on the plugin's own
   documented advice for "multi-agent / shared-worktree setups where another agent can move HEAD
   between a worker's turns" — which is exactly how this repo is worked. **Commit and push review
   stay on**, and they are the stronger layer anyway: the commit reviewer is agentic and reads
   related files to trace data flow across the codebase.

**Never reach for these:** `SECURITY_GUIDANCE_DISABLE=1`, `ENABLE_COMMIT_REVIEW=0`, or
`ENABLE_PATTERN_RULES=0`. Those trade the catch for the quiet, which is the thing this loop exists
to avoid. If tripping is still too high after the three levers, the answer is a better guidance
file, not a smaller gate.

## The loop, per trip

1. **Read the finding before judging it.** An agent's report that a hook "fired spuriously" is a
   claim, not a verdict.
2. **Classify.**
   - **True positive** — fix the code. Then ask whether the class of bug is preventable by a gate
     (a test, a lint rule), because a finding that a gate could have caught should not need an LLM.
   - **False positive** — decide *why* the reviewer could not have known. That reason picks the
     lever: a codebase-wide decision → the guidance file; a single surprising line → an inline
     comment; a structural artefact of how we work → an env var.
   - **Ambiguous** — treat as true. An unclear finding in `SecurityConfig`, the logout path or
     session handling is worth a round trip; #320 was real and looked like noise.
3. **Act, then close the loop in the same turn.** The rule change is part of handling the trip, not
   a follow-up. A false positive noticed and not written down recurs on the next diff that touches
   the same file.
4. **Append a dated line to the log below.** Include the verdict, because a log of only false
   positives will eventually be used to argue for switching the plugin off.
5. **Re-measure after a change**, don't assume. `scripts/security-trips.sh` before and after.

## Reporting a trip to the user

Say what tripped, the verdict, and what changed so it cannot recur. Keep it to a couple of lines —
a trip that was benign and is now recorded does not need a paragraph. A **true** positive gets the
detail, because that is the one they need to know about.

## Learnings

Format: `- YYYY-MM-DD — what tripped → verdict → what changed.`

- 2026-08-09 — **A GitHub Actions workflow interpolated `inputs.version` directly into a `run:`
  block** (the image-publish workflow, #311). → **TRUE POSITIVE.** Fixed by routing every untrusted
  value through `env:` first. → Recorded as a *must still flag* rule in the guidance file, so the
  exclusions can never be read as covering it. This is the catch that justifies the whole plugin
  staying on.
- 2026-08-13 — **Measured the baseline rather than tuning on impression.** 26 completed reviews in
  23 hours, 0 findings, 75% of Stop hooks with an empty review set, and 67 log lines of git-baseline
  failure from the hook running in the scratchpad (a non-git cwd). → The Stop review was doing
  almost no work and being interrupted by the multi-agent topology it is documented as unsuited to.
  → `ENABLE_STOP_REVIEW=0` project-scoped, guidance file written, this loop created. **Baseline
  recorded above so "it got better" is checkable rather than felt.**
- 2026-08-14 — **`page.$$eval(` in a Playwright script trips the `eval_injection` pattern rule.**
  → **FALSE POSITIVE**, and the verdict came with a correction: the agent reporting it said
  `page.evaluate` triggers it too, and that is impossible — the rule is
  `(?<![a-zA-Z0-9_\.])eval\(`, whose lookbehind excludes `.` precisely so `model.eval()` and
  `redis.eval()` do not match, and `evaluate(` is not `eval(` anyway. What actually matches is
  `$$eval(`: `$` is **not** in the lookbehind's character class, so the guard that catches `.`
  misses `$`. Five call sites, in `state-link-check.mjs`, `contrast-check.mjs` and
  `ui-measure.mjs`. → **Changed nothing in the scripts, deliberately.** `locator(sel)
  .evaluateAll(fn)` is an exact equivalent and would silence it, but these are the measurement
  instruments this project has been burned by more than any other code, and rewriting four of
  them to quiet a layer-1 *reminder* — which prints and costs no round trip — trades real risk
  for cosmetics. Recorded in `.claude/claude-security-guidance.md` so the LLM layer (which, unlike
  the regex layer, does read it) will not escalate it. **Layer 1 cannot be tuned per-pattern**:
  the guidance file feeds only the LLM review, and the sole switch is `ENABLE_PATTERN_RULES=0`,
  which is off the table. The `$`-in-lookbehind gap is worth reporting upstream.
- 2026-08-13 — **The log's own format defeated my first two greps** — I searched for `finding` and
  `category` and got one hit, which reads exactly like "nothing ever fires". The shape that worked
  was normalising timestamps and quoted strings, then ranking distinct messages. → Suspect the
  instrument before the conclusion; a zero from a search you have not proven can find a positive is
  not a measurement. `security-trips.sh` exists so nobody has to re-derive the pattern.
