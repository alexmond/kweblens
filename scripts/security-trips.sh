#!/usr/bin/env bash
# Measure how often the security-guidance plugin INTERRUPTS, and how often it finds something.
#
# The metric that matters is benign trips per session, not hook invocations: the hook firing
# silently costs nothing, the hook interrupting costs a round trip. So this reports both, plus
# the wasted runs (empty review sets, git-baseline failures) that are pure overhead.
#
# WHY THIS EXISTS RATHER THAN AN AD-HOC GREP: the log's format defeats the obvious patterns.
# Searching it for "finding" or "category" returns one line, which reads exactly like "the plugin
# never fires" — a false zero. The shape that works is to strip timestamps, normalise numbers and
# quoted strings, then rank distinct messages. Getting that wrong once already produced a wrong
# conclusion (see .claude/skills/security-trip-review/SKILL.md, 2026-08-13).
#
# Read-only. Touches nothing but the plugin's own log.
set -uo pipefail

LOG="${SECURITY_GUIDANCE_DEBUG_LOG:-$HOME/.claude/security/log.txt}"
SINCE=""

while [[ $# -gt 0 ]]; do
	case "$1" in
	--since)
		SINCE="${2:-}"
		shift 2
		;;
	--log)
		LOG="${2:-}"
		shift 2
		;;
	-h | --help)
		echo "usage: $0 [--since YYYY-MM-DD] [--log PATH]"
		exit 0
		;;
	*)
		echo "unknown argument: $1" >&2
		exit 2
		;;
	esac
done

if [[ ! -r "$LOG" ]]; then
	echo "!!  no readable plugin log at $LOG" >&2
	echo "    The plugin writes one only after it has run; check the plugin is enabled." >&2
	exit 1
fi

# Window. `--since` filters on the leading [YYYY-MM-DD timestamp.
if [[ -n "$SINCE" ]]; then
	body=$(awk -v s="$SINCE" 'match($0, /^\[([0-9]{4}-[0-9]{2}-[0-9]{2})/, m) { if (m[1] >= s) print; next } { print }' "$LOG")
else
	body=$(cat "$LOG")
fi

count() { grep -cF -- "$1" <<<"$body" || true; }

first=$(grep -oE '^\[[0-9-]+ [0-9:]+' <<<"$body" | head -1 | tr -d '[')
last=$(grep -oE '^\[[0-9-]+ [0-9:]+' <<<"$body" | tail -1 | tr -d '[')

invocations=$(count 'Hook called with args:')
bash_calls=$(count 'hook_event=PostToolUse, tool=Bash')
edit_calls=$(count 'hook_event=PostToolUse, tool=Edit')
write_calls=$(count 'hook_event=PostToolUse, tool=Write')
stop_hooks=$(count 'hook_event=Stop')

stop_empty=$(count 'Stop hook: empty review set')
stop_clean=$(count 'Stop hook: no security issues found')
diff_clean=$(count 'LLM code review: no vulnerabilities found')
commit_clean=$(count 'Commit review: no security issues found')
push_clean=$(count 'Push sweep: no new findings')
baseline_fail=$(count 'Failed to capture git baseline')
not_a_repo=$(count 'not a git repository')

clean=$((stop_clean + diff_clean + commit_clean + push_clean))

# A "finding" line is any review outcome that is NOT one of the known-clean phrasings. Kept as a
# denylist of clean phrases rather than an allowlist of finding phrases ON PURPOSE: a new finding
# category the plugin adds must show up here as an unknown, not be silently dropped.
#
# The denylist has two halves. The first is the known-CLEAN outcomes. The second is the plugin's
# own PLUMBING chatter — reflog recovery, push-sweep bookkeeping, HEAD-vs-pushed-tip mismatches.
# Those look alarming and are not findings; leaving them in reported 14 "findings" on a window
# whose real count is 0. Two of them are worth understanding rather than merely filtering:
#   "no push-success signal in bash output" / "pushed tip X != HEAD Y" / "new-branch X != HEAD Y"
# are the multi-agent artefact — another agent moved HEAD between this worker's turns. They are
# the empirical case for ENABLE_STOP_REVIEW=0 in .claude/settings.json, not a security signal.
findings=$(grep -E 'review|sweep|finding|vulnerab|severity' <<<"$body" |
	grep -viE 'no vulnerabilities found|no security issues found|no new findings|no reviewable|empty review set|reviewing [0-9]+ changed|review_set=|reviews took|detected git commit|sha\(s\) resolved|Background security review' |
	grep -viE 'stdout had no|reflog shows|no push-success signal|range=[0-9]+ prefix_advanced|new-branch .* != HEAD|pushed tip .* != HEAD|skipping baseline capture|preserving prior baseline' || true)
finding_count=$(if [[ -n "$findings" ]]; then wc -l <<<"$findings"; else echo 0; fi)

printf '\n\033[1m== SECURITY-GUIDANCE TRIPS\033[0m  %s → %s\n\n' "${first:-?}" "${last:-?}"

printf '  \033[1mInterruptions (what actually costs you)\033[0m\n'
printf '    findings reported      : %s\n' "$finding_count"
printf '    reviews completed clean: %s  (stop %s, diff %s, commit %s, push %s)\n' \
	"$clean" "$stop_clean" "$diff_clean" "$commit_clean" "$push_clean"
if [[ "$clean" -gt 0 || "$finding_count" -gt 0 ]]; then
	printf '    signal rate            : %s of %s reviews found something\n' \
		"$finding_count" "$((clean + finding_count))"
fi

printf '\n  \033[1mOverhead (fires that reviewed nothing)\033[0m\n'
printf '    stop hooks             : %s, of which %s had an EMPTY review set\n' "$stop_hooks" "$stop_empty"
printf '    git-baseline failures  : %s  (hook running in a non-git cwd, e.g. the scratchpad)\n' "$baseline_fail"
printf '    "not a git repository" : %s\n' "$not_a_repo"

printf '\n  \033[1mInvocations (cheap; here for context, NOT the metric)\033[0m\n'
printf '    total                  : %s\n' "$invocations"
printf '    PostToolUse Bash       : %s\n' "$bash_calls"
printf '    PostToolUse Edit/Write : %s / %s\n' "$edit_calls" "$write_calls"

if [[ "$finding_count" -gt 0 ]]; then
	printf '\n  \033[1mLines that are not a known-clean outcome — read these\033[0m\n'
	sed 's/^/    /' <<<"$findings" | head -20
	printf '\n  A line here is either a real finding or a phrasing this script does not know.\n'
	printf '  If it is the latter, teach the filter rather than widening it to catch-all.\n'
fi

printf '\n  Classify and act on any finding with the security-trip-review skill:\n'
printf '  .claude/skills/security-trip-review/SKILL.md\n\n'
