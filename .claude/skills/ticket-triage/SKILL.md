---
name: ticket-triage
description: >-
  Review the open tickets, rank them, work out what is genuinely startable versus blocked, and
  run the startable ones in parallel through to merged. Use whenever the ask is "what's next",
  "review tickets", "prioritise", "continue with priorities", "start in parallel", or a bare
  "go" against this repo's backlog. Covers where the backlog actually lives (issues AND the
  roadmap), how to rank, how to size the parallel width honestly, how to brief an agent so its
  result is trustworthy, and how to merge and clean up. Self-improving by rule: when a run
  misjudges a priority, a dependency or the width, fix this file and log the miss below.
---

# Reviewing and running the backlog

The recurring instruction is some form of *"review tickets, prioritise, start in parallel"*.
This is how to do it here, and — more usefully — the specific ways it has gone wrong.

**Start with the facts, not the ticket titles:**

```bash
scripts/backlog-snapshot.sh          # issues, open PRs, roadmap markers, instances, worktrees
scripts/backlog-snapshot.sh --brief  # just issues + PRs
```

Read-only. It starts nothing and stops nothing.

## 1. The backlog is two places, and the second one drifts

Open issues are not the whole backlog. `docs/design/roadmap.md` is what CLAUDE.md names as the
answer to "what next", and **it goes stale in the direction that wastes the most time**: four
items once described shipped work as pending, and R1 prescribed a measurement
(`jcmd GC.class_histogram`) that cannot answer its own question — compact strings put the output
`String` and the model graph in the same `byte[]` row. Following it reached a wrong conclusion
twice.

So: read both, and **verify a roadmap item against the code before ranking it**. If it is stale,
fixing the doc is itself a ticket-sized piece of work worth doing (see #329).

## 2. Ranking

In order:

1. **Data loss or a wrong answer presented confidently.** A pane claiming "0 Warnings" when the
   check failed; a keystroke that freezes a tab and loses the edit; sign-out that does not sign
   out. These outrank everything.
2. **Instrument defects.** A broken tool corrupts every judgement made through it, including the
   ranking itself. Seven were found in one week here, and each one made a run *look* clean. They
   outrank feature work — and a check that fails on things that are fine is also an instrument
   defect, because it trains people to ignore the output.
3. **A blocking foundation.** The one ticket that N others depend on. Doing it first is what
   makes the N cheap; doing it last means N incompatible answers.
4. **Correctness the user can see.** Wrong counts, dead links, unreachable relations.
5. **Polish.** Layout, truncation, contrast.

A `P0`…`P3` label is an input, not the answer — labels are set when a ticket is filed and rarely
revisited. Rank on what the ticket *says*, then reconcile with the label.

## 3. Parallel width is what is INDEPENDENT, not what is open

The most common misjudgement. Nine open tickets does not mean nine agents.

- **Blocked means blocked.** #338–#341 all depended on #337 defining one status vocabulary.
  Starting them together would have produced four incompatible definitions — the exact failure
  #337 existed to prevent. The honest width was 2, not 5.
- **Partition by FILE before launching**, and tell each agent which files the others own. Three
  agents ran cleanly in one round because one owned `Overview.vue`/`api.ts`, one owned twelve
  named error-div components, and one owned the nav label code.
- When an agent's work genuinely needs a file another owns, it should **say so in its report**
  rather than edit it.
- Say the width out loud and why. "Two, because the other four are blocked by design" is a
  result; quietly starting two and not mentioning the rest is not.

## 4. Briefing an agent so the result can be trusted

The brief is most of the quality. What has repeatedly mattered:

- **Point at the issue and tell it to verify, not assume.** "It was written after verifying both
  halves — trust it but confirm as you go."
- **Never hand over an unverified hypothesis as fact.** A brief once said `autosize` draws a
  grabber that snaps back; the truth was that naive-ui draws no grabber at all when `autosize` is
  set. The brief that said *"do not inherit my guess — measure it"* got the right answer.
- **State the constraints that are already decided** so they are not re-litigated per ticket:
  client-side filtering (GH#263), one shared operator identity (ADR-001), remediation is
  suggest→approve→apply, Secret values are themselves sensitive.
- **Demand a control, not a green run.** "A green test that has never been shown to fail pins
  nothing." Ask for the mutation, the pre-fix rebuild, the positive control.
- **Name the verification the repo already requires**: `scripts/dev-verify.sh`, hermetic tests,
  measure-don't-eyeball, `contrast-check` in both themes, the sweep rule (unrelated findings
  become issues, not scope creep).
- **Protect the user's instance.** Say which port is theirs and that it must not be stopped,
  restarted or measured against. Agents pick their own port and stop it when done.
- Boilerplate every brief needs: no `cd` prefix; absolute paths; commit trailers; PR body
  footer; **never put AI attribution in issue/PR comment text**; `kubectl` needs an explicit
  `--context`; never assume a cluster id `default` exists; `.playwright/` stays gitignored.

## 5. Treat the report as evidence, not as gospel

Agents have overturned roughly a dozen claims — including several of mine — and have also been
wrong. Verify anything load-bearing yourself:

- A security change: read the diff. The `PresentedCredentialsFilter` fix was right, and confirming
  its scope and filter order took two commands.
- A surprising negative ("this cannot be one rule") deserves the same scrutiny as a surprising
  positive. That one held up.
- When an agent corrects *you*, check it and then say so plainly. The "0 bare error divs" I
  reported was a grep artefact — `<div class="error"` requires `class` to be the first attribute,
  and every one of the twelve leads with `v-if`.

## 6. Merging and cleaning up

```bash
gh pr view <n> --repo <repo> --json mergeable,mergeStateStatus,statusCheckRollup \
  -q '.mergeable + " / " + .mergeStateStatus + " / " + ([.statusCheckRollup[]?|.conclusion]|join(","))'
gh pr merge <n> --repo <repo> --squash --admin --delete-branch
```

- `UNKNOWN / UNKNOWN` means GitHub has not computed mergeability yet — wait a few seconds and
  re-check rather than concluding anything.
- **Merge order matters** when two PRs touch the same file (CLAUDE.md and the skill's Learnings
  log are the usual collisions). Merge, then let the next agent rebase.
- After each merge: sync `main`, remove **finished** agents' worktrees and their branches, and
  leave running agents' worktrees alone (they show as `locked`).
- Close the loop on the task list, and restart the user's instance when merges have made it
  `STALE` — but say so rather than doing it silently mid-session.

## 7. Report back in the shape that is useful

A table of what merged and what it found, the corrections (yours included), what is still
blocked and why, and — kept separate — **what needs the user's decision**. Irreversible things
(publishing to Maven Central) and preference things (whether to keep stray files) are theirs,
and burying them in a status list is how they get missed.

## Learnings

Format: `- YYYY-MM-DD — what happened → what changed.`

- 2026-08-12 — **The most valuable output of a planned item was repeatedly something unplanned.**
  R4's contract tests uncovered a dead relation (#313); R3's error sweep uncovered sign-out
  leaving the server session valid so any password signed back in (#320); R5's attach page
  uncovered docs claiming the MCP tool endpoints need no credential when they answer 401; the
  resizable-fields change uncovered a Form tab that froze on one keystroke (#334). **None was on
  any list.** → Budget for the sweep, not just the item, and rank a sweep finding on its own
  merits the moment it lands rather than queueing it behind the plan.
- 2026-08-12 — **Nine open tickets, honest width 2.** #338–#341 were all blocked by #337, and
  starting them in parallel would have meant four agents inventing four incompatible status
  vocabularies. → State the width and the reason; a blocked ticket started early is worse than a
  ticket not started.
- 2026-08-12 — **A brief carried my own unverified guess and would have sent the agent wrong.**
  I asserted naive-ui's `autosize` draws a grabber that snaps back on typing; it draws none at
  all. The brief happened to also say "measure it, do not inherit my guess", which is the only
  reason the right answer came back. → Mark every unverified claim in a brief as unverified,
  explicitly.
- 2026-08-12 — **I briefed an agent with a wrong number from a wrong grep.** `<div class="error"`
  returned 0 because every one of the twelve leads with `v-if`; the honest pattern is
  `<div [^>]*class="error"`. The agent caught it. → When a count feeds a brief, sanity-check the
  pattern against one known-positive instance before quoting it.
- 2026-08-12 — **My own navigation probes measured nothing three times** while I was trying to
  confirm a hypothesis, and an absent selector reads exactly like a passing run. The working
  chain was already in `scripts/resize-check.mjs`. → Before hand-rolling a browser probe, check
  whether a script already reaches that surface, and treat "not present" as a failed run.
- 2026-08-12 — Two agents' instances collided with the user's on shared ports, and one run spent
  ten minutes measuring **another agent's build**. → Every brief names the user's port as
  off-limits; `dev-run.sh` now compares `/proc/<pid>/cwd` and the served asset hashes.
