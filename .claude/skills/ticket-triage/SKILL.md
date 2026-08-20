---
name: ticket-triage
description: >-
  Review the open tickets, rank them, work out what is genuinely startable versus blocked, and
  run the startable ones in parallel through to merged — then KEEP GOING: re-triage every time an
  agent finishes, so the backlog drains without being re-asked. Use whenever the ask is "what's
  next", "review tickets", "prioritise", "continue with priorities", "start in parallel", or a bare
  "go" against this repo's backlog, and stay in the loop until the queue is empty or everything
  left needs the user. Covers where the backlog actually lives (issues AND the roadmap), how to
  rank, how to size the parallel width honestly, how to brief an agent so its result is
  trustworthy, and how to merge and clean up. Self-improving by rule: when a run misjudges a
  priority, a dependency or the width, fix this file and log the miss below.
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
fixing the doc is itself a ticket-sized piece of work worth doing (see #329, and #356 which found
five wrong rows where three were briefed).

## 2. This is a LOOP, not a one-shot

Triage does not end when the agents are dispatched. **Every time an agent finishes, run the same
steps again** — snapshot, rank, check what is startable, dispatch or skip — so the backlog keeps
draining without the user re-asking. A completion is the trigger; the steps are unchanged.

### The cycle, on each completion

1. **Finish the finished one first.** Verify the load-bearing claims (§6), merge if green, delete
   the branch and its worktree, close the issue, update the task list. **Do this before choosing
   the next ticket**, because merging is what moves `main` and frees the files the next agent may
   need. Dispatching before merging is how two agents end up rebasing onto each other.
2. **Re-snapshot.** Do not rank from the list you took an hour ago. Merges close tickets, agents
   file new ones, and priorities change: today a research ticket produced a P1 release blocker
   that outranked everything already queued.
3. **Re-rank the whole open set** (§3), not just the tail you remember. A sweep finding is ranked
   on its own merits the moment it lands, not queued behind the plan.
4. **Pick the top startable ticket** and check it against the skip list below.
5. **Pick the role, then dispatch it** with a full brief (§5), in **its own worktree** (§4), or
   **skip** with one line saying why.
6. **Wait for the next completion.** Do not poll, do not spin, do not start something weaker just
   to be starting something.

### When to skip — start nothing, say why in one line, try again next completion

- **Blocked by an open dependency.** #338–#341 all needed #337 to define one status vocabulary
  first. #143's tickets need #360. Blocked means blocked.
- **Would collide on files with a running agent.** Partition by file *before* dispatch. If the top
  ticket wants a file a running agent owns, skip it and take the next one that does not — or wait,
  if nothing else is startable. Two agents in one checkout nearly destroyed each other's work
  today.
- **Needs the user's decision.** Irreversible (publishing to Maven Central) or preference (whether
  to keep stray files). Never dispatch these; surface them separately (§8).
- **Parked by decision**, with a stated gate that has not fired (#148, #143 before its breakdown).
  Re-check the gate rather than assuming — #148's was found half-fired today.
- **The ranking itself is uncertain** because an instrument is suspect. Fix the instrument first;
  it outranks the feature (§3).
- **The queue is empty**, or everything left is one of the above.

A skip is a normal outcome, not a failure. Say `"nothing startable — #367 is blocked on #360,
#357's files are held"` and stop there.

### When to stop looping

When **every** remaining item is parked, user-decision, or blocked, say so **once**, list what
would unblock the queue, and stop. Do not re-announce the same standstill on every subsequent
completion — that is noise, and it trains the user to ignore the report. Resume when a merge, a
user decision, or a newly filed ticket changes the set.

### What the loop must never do

- **Never widen scope to keep busy.** An empty queue is a result. Inventing work, or starting a
  P3 nobody ranked, is worse than reporting that the backlog is drained.
- **Never start something irreversible** because it was next in the ranking.
- **Never exceed the honest parallel width** (§4) just because agents are free.

## 3. Ranking

In order:

1. **Data loss or a wrong answer presented confidently.** A pane claiming "0 Warnings" when the
   check failed; a keystroke that freezes a tab and loses the edit; sign-out that does not sign
   out; **a published artifact that cannot start**. These outrank everything.
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

**Raise a label when you know something the filer could not.** #363 was filed P2 by an agent that
had no reason to know `kweblens-cli` is one of only two modules that publish to Maven Central,
which made a broken jar a **release blocker** — P1, and a reason to hold R2. Say what you knew that
they did not, in a comment on the ticket, so the change is auditable.

## 4. Parallel width is what is INDEPENDENT, not what is open

The most common misjudgement. Nine open tickets does not mean nine agents.

- **Blocked means blocked.** #338–#341 all depended on #337 defining one status vocabulary.
  Starting them together would have produced four incompatible definitions — the exact failure
  #337 existed to prevent. The honest width was 2, not 5.
- **Partition by FILE before launching**, and tell each agent which files the others own. Three
  agents ran cleanly in one round because one owned `Overview.vue`/`api.ts`, one owned twelve
  named error-div components, and one owned the nav label code.
- **Give every agent its own worktree** (`isolation: "worktree"`). This is not optional politeness:
  two agents working in the shared checkout put one agent's `git checkout -b` under another's
  uncommitted `styles.css`, one `git add -A` away from either committing or destroying it. A
  worktree costs ~200–500 ms and removes the entire class.
- When an agent's work genuinely needs a file another owns, it should **say so in its report**
  rather than edit it.
- Say the width out loud and why. "Two, because the other four are blocked by design" is a
  result; quietly starting two and not mentioning the rest is not.

## 5. Pick the role, then write the brief

### 5a. The role — `general-purpose` is the fallback, not the default

Every ticket dispatched from here went to a **generic** agent for months, which meant every agent
started from zero: no method for the kind of work in front of it, and no memory of the last time
this repo taught it something. The `roles` plugin (`~/IdeaProjects/alexmskills/plugins/roles`) is
the fix, and the payoff is the second half — **a role file accumulates repo-specific learnings and
the next agent to wear it inherits them.** A generic agent has nowhere to put what it learned.

**Choose the role from what the ticket IS, never from its `area/` label.** An `area/spa` ticket
whose cause is unknown is a `debugger` job; an `area/spa` ticket that is a decision between two
designs is an `architect` job. The label says where the code lives; the role says what the work is.

| The ticket is… | Role | Worked example from this backlog |
|---|---|---|
| A failure whose cause is not yet established | `debugger` | #480 — two candidate causes needing different fixes; establishing which IS the work |
| A choice between designs, or a contract others build against | `architect` | #476 — a fourth `KeyMap` table vs. a derived-but-tableless bar |
| Something slow, allocating, or fetching more than it needs | `optimizer` | #459 — a 10 MB CRD list to answer a one-kind question |
| Restructuring where behaviour must not move | `refactorer` | #473 — the dead-CSS sweep |
| Mapping unfamiliar ground, or auditing quality before changing it | `reviewer` | a sweep across a slice nobody has read recently |
| Attacking a claim that looks too clean | `skeptic` | verifying an agent's "measured 40× faster" before merging |
| New surface from nothing | `builder` | rare here — this codebase is not greenfield |

**Then dispatch like this** — the role is loaded by the agent, not merely named at it:

- The role's canonical copy is **`.claude/roles/<role>.md`** in this repo. It does not exist until
  a role is first used; the plugin seeds it from `plugins/roles/skills/<role>/role.md`.
- The brief says: *"Work as the `<role>` role. Read `.claude/roles/<role>.md`; if it is absent,
  copy `~/IdeaProjects/alexmskills/plugins/roles/skills/<role>/role.md` there verbatim first.
  Adopt its Charter and Body and honour every entry under `## Learnings (core)` and
  `## Learnings (solo)`."* The wrapper skills are `disable-model-invocation: true`, so **reading
  the file is the reliable path** and `/roles:as <role>` is the nicety — never depend on it.
- The brief ends with the loop that makes this worth doing: *"If the run surfaced a durable,
  reusable lesson — a fix that took more than one cycle, a stack- or repo-specific gotcha — append
  one line under `## Learnings (solo)`: `- YYYY-MM-DD — <one sentence>`. Do not log a routine
  one-pass outcome, and never edit the Charter or Body: propose those to the user instead."*
- **Role files are repo state, so they belong in the PR.** A worktree agent that appends a
  learning and never commits it has thrown it away.
- **One role file, one writer per round.** Two agents in two worktrees wearing the same role
  both append to `.claude/roles/<role>.md` and collide on merge — and the first round of this
  rule dispatched exactly that: two `debugger`s and one `architect`. Name the owner in each
  brief; the others are told to **report** their learning line so you apply it. And **seed the
  registry before dispatching** if `.claude/roles/` does not yet hold the roles this round needs
  — otherwise every agent *creates* the file rather than reading it, which is the same collision
  one step earlier.
- `general-purpose` stays correct for work that is genuinely none of the above — filing tickets,
  a docs sweep, running a measurement someone else will interpret. Say so when you use it.

**When no role fits, create one — but the bar is a stated gap, not a nicer name.** The `as` skill's
rule is *"don't improvise a persona"*, and the same discipline applies to minting them: name the
work the seven existing roles genuinely do not cover, in one sentence, before writing anything. Then
write `.claude/roles/<name>.md` in the same shape as the seeds — `# <name>`, `## Charter`,
`## When to use`, `## Body` (method and deliverables), `## Learnings (core)`, `## Learnings (solo)`
— commit it with the PR, and **say in the report that a role was minted and why**, because a role
nobody agreed to is a persona invented mid-run wearing a file for cover. Keep the new role in
`.claude/roles/` only; propose graduating it into the `roles` plugin **separately**, and only if it
is portable to a repo that is not this one.

### 5b. The brief

The brief is most of the quality. What has repeatedly mattered:

- **Point at the issue and tell it to verify, not assume.** "It was written after verifying both
  halves — trust it but confirm as you go."
- **Never hand over an unverified hypothesis as fact.** A brief once said `autosize` draws a
  grabber that snaps back; the truth was that naive-ui draws no grabber at all when `autosize` is
  set. The brief that said *"do not inherit my guess — measure it"* got the right answer.
- **Separate what you verified from what you are passing on.** "I verified these four facts
  myself; verify anything else you rely on" is the line that has repeatedly produced good work,
  and it is also what lets an agent correct you cleanly.
- **State the constraints that are already decided** so they are not re-litigated per ticket:
  client-side filtering (GH#263), one shared operator identity (ADR-001), remediation is
  suggest→approve→apply, Secret values are themselves sensitive, the status vocabulary is **open**
  rather than a closed enum.
- **Demand a control, not a green run.** "A green test that has never been shown to fail pins
  nothing." Ask for the mutation, the pre-fix rebuild, the positive control.
- **Name the verification the repo already requires**: `scripts/dev-verify.sh`, hermetic tests,
  measure-don't-eyeball, `contrast-check` in both themes, the sweep rule (unrelated findings
  become issues, not scope creep).
- **Protect the user's instance.** Say which port is theirs and that it must not be stopped,
  restarted or measured against. Agents pick their own port and stop it when done.
- Boilerplate every brief needs: no `cd` prefix; absolute paths; commit trailers; PR body
  footer; **never put AI attribution in issue/PR comment text**; `kubectl` needs an explicit
  `--context`; never assume a cluster id `default` exists; `.playwright/` stays gitignored;
  **do not merge — report back**.

## 6. Treat the report as evidence, not as gospel

Agents have overturned roughly a dozen claims — including several of mine — and have also been
wrong. Verify anything load-bearing yourself, **before merging**:

- A security change: read the diff. The `PresentedCredentialsFilter` fix was right, and confirming
  its scope and filter order took two commands.
- A claimed fix to a broken artifact: **run the artifact.** #373 said the CLI starts; building and
  running it took four minutes and made the merge a fact rather than a trust exercise.
- A claimed control: **re-run it.** #359's new gate was said to fail on the old CSS; running it
  against the pre-fix stylesheet reproduced 2 of 4 failing with the exact messages claimed.
- A surprising negative ("this cannot be one rule") deserves the same scrutiny as a surprising
  positive. That one held up.
- **When two agents disagree, go to the code** — do not average them. On `relations.ts` one said
  "a client-side mirror", the other "just rendering"; the file has `TITLES` (3 of 12 keys),
  `PROJECTIONS` (3 of 12) and a literal `order` array, so the first was right and the second was
  overstating. Both had verified something real; only the code settled it.
- When an agent corrects *you*, check it and then say so plainly. The "0 bare error divs" I
  reported was a grep artefact — `<div class="error"` requires `class` to be the first attribute,
  and every one of the twelve leads with `v-if`.

## 7. Merging and cleaning up

```bash
gh pr view <n> --repo <repo> --json mergeable,mergeStateStatus,statusCheckRollup \
  -q '.mergeable + " / " + .mergeStateStatus + " / " + ([.statusCheckRollup[]?|.conclusion]|join(","))'
scripts/pr-watch.sh <n>                                  # blocks until checks settle
gh pr merge <n> --repo <repo> --squash --admin --delete-branch
```

- `UNKNOWN / UNKNOWN` means GitHub has not computed mergeability yet — wait a few seconds and
  re-check rather than concluding anything. `UNSTABLE` with no conclusions means checks are still
  running; `pr-watch.sh` is the honest way to wait.
- **Check the PR's file list against what the agent said it changed.** It is one command and it is
  how you catch another agent's work swept into the branch.
- **Merge order matters** when two PRs touch the same file (CLAUDE.md and the skills' Learnings
  logs are the usual collisions). Merge, then let the next agent rebase.
- After each merge: remove the **finished** agent's worktree and branch, and leave running agents'
  worktrees alone (they show as `locked`).
- **Do not `git pull` the shared checkout while an agent is working in it.** Sync it when it is
  free; read merged files with `git show origin/main:<path>` in the meantime. Reading a stale
  working copy and calling it HEAD has already produced one wrong conclusion.
- Restart the user's instance when merges have made it `STALE` — but say so rather than doing it
  silently mid-session.

## 8. Report back in the shape that is useful

A table of what merged and what it found, the corrections (yours included), what is still
blocked and why, and — kept separate — **what needs the user's decision**. Irreversible things
(publishing to Maven Central) and preference things (whether to keep stray files) are theirs,
and burying them in a status list is how they get missed.

In loop mode, keep the per-completion report **short**: what merged, what it actually found, what
started next. Save the full shape for a round that ends, or a finding that changes the plan.

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
- 2026-08-14 — **Two agents in the SHARED CHECKOUT nearly destroyed each other's work.** One ran
  `git checkout -b` in `/home/alexm/IdeaProjects/kweblens` while another had uncommitted
  `styles.css` and a new test file sitting there; a single `git add -A` from either would have
  committed the other's half-finished work or lost it. Both handled it correctly once warned, and
  the agent that had used a worktree unprompted was the only one never at risk. → **Worktree
  isolation is now the default for every dispatched agent** (§4), and briefs say "stage only your
  own paths by name; never `-A`, `-a`, `stash`, `checkout .`, or `clean`".
- 2026-08-14 — **That git rule was then carried into worktree briefs, where it is wrong**, and an
  agent reported "breaking" it: a path-scoped `git stash push -- scripts/contrast-check.mjs`,
  popped immediately, to test whether a syntax error pre-existed. In its **own** worktree there was
  no other agent's work in the tree, so the hazard the rule was written for did not exist — and the
  ban cost a legitimate bisection technique and a line of the report. → **Scope the rule to the
  hazard.** In a shared checkout: never `-A`, `-a`, bare `stash`, `checkout .`, `clean`. In an
  isolated worktree: the only rule is *stage what you meant to stage* — path-scoped stash and
  checkout are fine. A rule that fires where its reason does not apply trains people to break
  stated rules, which is worse than the thing it prevented.
- 2026-08-14 — **I read a stale working copy and drew a conclusion from it.** I had deliberately
  not synced the shared checkout (an agent was in it), then grepped it for a doc another agent had
  just merged, and got the pre-merge text. → When the checkout is deliberately stale, read merged
  content with `git show origin/main:<path>`; never grep a working copy you have chosen not to
  update.
- 2026-08-14 — **A ticket's priority was wrong for a reason its filer could not have known.** #363
  (the CLI jar cannot start) was filed P2; `kweblens-cli` is one of only two modules that publish
  to Maven Central and publishing is irreversible, making it a release blocker. → Raised to P1
  with a comment stating what I knew that the filer did not. Re-rank on facts the filer lacked,
  and record the reasoning on the ticket.
- 2026-08-16 — **I merged three PRs onto a red `main` without noticing, because I only ever
  checked the PR's own checks.** `983486d`, `8216a2a` and `315211d` each went in with
  `statusCheckRollup = SUCCESS` on the branch, and `main`'s own post-merge run failed on all
  three — a `kweblens-tui` test that passes locally and fails on CI. §7 said to check the PR; it
  never said to check what the merge produced. → **After merging, check `main`'s run, not just the
  PR's.** `gh run list --branch main --limit 5` is the whole cost. A branch that was green before
  the merge is not evidence about the commit that the merge created, and three rounds of "all
  checks passed" hid a red trunk.
- 2026-08-16 — **A gate that cannot reach CI is worse than no gate, and it can look perfect
  locally.** #421's first draft lived in a Java package called `build`, which `.gitignore:5`
  silently swallows: 4/4 green locally, `git status --porcelain` empty, and it would never have
  been committed. Its author caught it by asserting **the gate's own file is in `git ls-files`**.
  → A new check should assert that it is itself tracked and that it scanned what it claims to
  scan; "it passed" and "it ran" are different facts.
- 2026-08-14 — **Two agents reached opposite conclusions about the same file and both had done
  real work.** Averaging them would have produced a wrong doc. Reading `relations.ts` settled it
  in one command. → When reports conflict, the code is the tiebreaker; say plainly which one was
  right rather than softening both.
- 2026-08-19 — **Every ticket had been going to a generic agent, and the skill never said to.**
  `general-purpose` was my habit, not a written rule — §5 talked only about the *text* of the brief,
  so nothing ever chose a method for the work or carried anything forward between tickets. The
  `roles` plugin already had seven personas and a learning loop (`.claude/roles/<role>.md`,
  `## Learnings (solo)`) and triage was using none of it. → §5a: **pick the role from what the
  ticket IS, not from its `area/` label**, have the agent *read* the role file rather than be told
  the role's name (the wrapper skills are `disable-model-invocation: true`), and require the
  learning to be **committed in the PR** — a worktree agent that appends a learning and does not
  commit it has thrown it away. Minting a new role needs a stated gap and a line in the report.
- 2026-08-20 — **The role rule collided with itself on its first use.** Ranking sent two tickets
  to `debugger` and one to `architect`, in three worktrees, against a repo where `.claude/roles/`
  did not exist yet — so all three would have *created* their role file, two of them the same one,
  and collided on merge. → Seed the registry on `main` **before** dispatching (#492 did), and name
  **one writer per role file** per round; everyone else reports their learning line. A rule about
  shared mutable state needs an owner, and this one had described the append without saying who
  may do it.
- 2026-08-20 — **A file-ownership rule threw away a working instrument.** The #506 agent built a
  runtime affordance sweep (CDP `getEventListeners` over every pointer-cursor element — the thing
  that turned "is this defect general?" from a grep into 1-of-294), was correctly told `scripts/`
  belonged to another agent that round, offered the wording instead of editing — and I removed its
  worktree with the file in it. Refiled as #511, at the cost of writing it twice. → **Ownership
  partitions EDITS to existing files; it must never stop an agent committing something it built.**
  A brief that reserves a directory now says: if you write a new script or probe, **commit it** in
  your PR under a name nobody else holds, and send me only the *documentation* wording for files
  someone else owns. And before removing a finished worktree, ask what is in it that is not in the
  PR.
