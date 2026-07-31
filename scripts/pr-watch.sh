#!/usr/bin/env bash
#
# Wait for a PR's checks to finish, printing each result as it lands.
#
# Usage:
#   scripts/pr-watch.sh 200          # watch PR 200
#   scripts/pr-watch.sh              # watch the PR for the current branch
#   scripts/pr-watch.sh 200 --merge  # ...and squash-merge it if everything passes
#
# Exits 0 if every check passed, 1 otherwise — so it can gate a follow-up step.
#
# --merge squash-merges with --admin --delete-branch, and ONLY when every check
# passed. It refuses on any failure. Deleting the branch fails harmlessly if a
# git worktree still holds it; clean those up with `git worktree remove`.

set -euo pipefail
cd "$(dirname "$0")/.."

MERGE=false
PR=""
for arg in "$@"; do
	case "$arg" in
		--merge) MERGE=true ;;
		*) PR="$arg" ;;
	esac
done

if [[ -z "$PR" ]]; then
	PR=$(gh pr view --json number -q .number)
	echo "==> current branch is PR #${PR}"
fi

seen=""
while true; do
	if ! state=$(gh pr checks "$PR" --json name,bucket 2>/dev/null); then
		# A PR with no checks yet returns non-zero; keep waiting rather than bailing.
		sleep 20
		continue
	fi
	[[ -z "$state" ]] && { sleep 20; continue; }

	done_now=$(echo "$state" | jq -r '.[] | select(.bucket != "pending") | "\(.name): \(.bucket)"' | sort)
	comm -13 <(echo "$seen") <(echo "$done_now") | sed 's/^/    /'
	seen="$done_now"

	if echo "$state" | jq -e 'length > 0 and all(.bucket != "pending")' >/dev/null 2>&1; then
		break
	fi
	sleep 20
done

if echo "$state" | jq -e 'all(.bucket == "pass")' >/dev/null 2>&1; then
	echo "==> PR #${PR}: all checks passed"
	if [[ "$MERGE" == true ]]; then
		echo "==> squash-merging"
		gh pr merge "$PR" --squash --admin --delete-branch
	fi
	exit 0
fi

echo "==> PR #${PR}: NOT all checks passed" >&2
echo "$state" | jq -r '.[] | select(.bucket != "pass") | "    \(.name): \(.bucket)"' >&2
exit 1
