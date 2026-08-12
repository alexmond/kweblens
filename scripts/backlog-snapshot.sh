#!/usr/bin/env bash
#
# One command that prints everything a triage run needs to rank work honestly.
#
# Exists because the expensive mistakes in triage are all mistakes of FACT, not of judgement:
# ranking a ticket that is already fixed, starting two agents that turn out to edit the same
# file, briefing an agent from a stale roadmap, or measuring against an instance somebody else
# is using. Each of those has happened here. Gathering the facts by hand is several commands
# and it is the step that gets skipped when the backlog looks obvious.
#
# Read-only: it starts nothing, stops nothing and writes nothing.
#
# Usage:
#   scripts/backlog-snapshot.sh            # everything
#   scripts/backlog-snapshot.sh --brief    # issues + PRs only
set -uo pipefail
cd "$(dirname "$0")/.."

REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo '')
BRIEF=false
[[ "${1:-}" == "--brief" ]] && BRIEF=true

hr() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

hr "OPEN ISSUES  (label order is the repo's own priority vocabulary)"
if [[ -n "$REPO" ]]; then
	gh issue list --repo "$REPO" --state open --limit 60 \
		--json number,title,labels,createdAt \
		-q '.[] | "#\(.number)  [\([.labels[].name] | join(","))]  \(.title)  (opened \(.createdAt[0:10]))"' \
		2>/dev/null || echo '  (gh failed — are you authenticated?)'
else
	echo '  (no GitHub remote resolved)'
fi

hr "OPEN PRs  (anything here is work already in flight — do not restart it)"
gh pr list --repo "$REPO" --state open --json number,title,headRefName,statusCheckRollup \
	-q '.[] | "#\(.number)  [\(.headRefName)]  \(.title)  checks=\([.statusCheckRollup[]?|.conclusion // .status] | join(","))"' \
	2>/dev/null || echo '  (none, or gh failed)'

if [[ "$BRIEF" == false ]]; then
	# The roadmap is what CLAUDE.md names as the answer to "what next", so its status markers
	# are part of the backlog whether or not anyone filed a ticket for them. They also go stale:
	# four items once described shipped work as pending, which is why this prints them raw.
	hr "ROADMAP ITEMS  (docs/design/roadmap.md — VERIFY these against the code, they drift)"
	grep -nE '^### (R[0-9]+|[0-9]+\.) ' docs/design/roadmap.md 2>/dev/null |
		sed 's/^/  /' || echo '  (no roadmap found)'

	# A triage run hands work to agents, and agents need ports. Two runs on one port is how a
	# measurement ends up describing somebody else's build.
	hr "RUNNING INSTANCES  (never stop one you did not start — it may be the user's)"
	./scripts/dev-run.sh --list 2>/dev/null | sed 's/^/  /'

	hr "LEFTOVER AGENT WORKTREES  (a finished agent's worktree is litter; a running one is not)"
	git worktree list 2>/dev/null | grep -c 'agent-' | sed 's/^/  count: /'
	git worktree list 2>/dev/null | grep 'agent-' | sed 's/^/  /' || true

	hr "MAIN  (is the local checkout current?)"
	git fetch -q --prune origin 2>/dev/null || true
	printf '  local : %s\n' "$(git log --oneline -1 2>/dev/null)"
	printf '  origin: %s\n' "$(git log --oneline -1 origin/main 2>/dev/null)"
	behind=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo '?')
	printf '  behind origin/main by: %s commit(s)\n' "$behind"
fi

hr "REMINDERS"
cat <<'EOF'
  - Parallel width is what is INDEPENDENT, not what is open. A blocked ticket started early
    means N agents inventing N incompatible answers to the same question.
  - Partition by FILE before launching, and tell each agent what the others own.
  - An instrument defect outranks a feature: every later judgement is made through it.
EOF
