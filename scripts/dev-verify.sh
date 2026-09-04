#!/usr/bin/env bash
#
# Local pre-commit build: apply the spring-javaformat style, then run the full
# Maven verify (compile, Checkstyle, PMD, tests, JaCoCo). This mirrors what CI
# runs, so a green run here means a green run on the PR.
#
# Usage:
#   scripts/dev-verify.sh            # format + verify the whole reactor -- THIS is the gate
#   scripts/dev-verify.sh --fast     # only the modules changed since origin/main (inner loop)
#   scripts/dev-verify.sh -pl kweblens-web -am   # pass extra args through to Maven
#   scripts/dev-verify.sh --force    # build anyway, with a running instance exposed
#
# Any arguments other than --force and --fast are forwarded to the `verify` invocation.
#
# --fast turns on gitflow-incremental-builder (.mvn/extensions.xml), which is otherwise inert
# because the parent POM sets gib.disable=true. It is a LOOP, NOT A GATE: a green --fast run is
# not evidence a PR is green, because the modules it skipped carry gates of their own. Run the
# plain command before you push. Three measured facts shape what this does:
#
#   * A changed path is attributed to its nearest enclosing module, so anything OUTSIDE a module
#     (README.md, CLAUDE.md, docs/, scripts/, column-parity/, the root POM) belongs to the parent
#     and rebuilds all seven. That is the safe direction, and it is why the gates that read those
#     root files -- kweblens-web's McpToolSurfaceTest, kweblens-core's ColumnParityTest -- cannot
#     be skipped by editing their input.
#   * One gate is repo-wide from INSIDE a module and is therefore the real hole: kweblens-core's
#     TrackedSourcesStayGreppableTest scans every path `git ls-files` prints. Measured: a raw
#     0x1F committed into a kweblens-tui source made the full reactor RED and an unforced
#     incremental run GREEN, because the change selected kweblens-tui alone. REPO_WIDE_GATES
#     below is the answer -- that gate is run by name on every --fast run, whatever changed.
#     Reproduce it with a NON-Java file: the spring-javaformat:apply step above strips a raw
#     control byte out of Java source, so a Java control heals before the gate reads it and the
#     green means nothing. A 0x1F in kweblens-ui/src/labelForward.ts is the honest control, and
#     is also where the exposure is -- a .ts change selects kweblens-ui and kweblens-web, never
#     the kweblens-core that holds the gate.
#   * GIB DISABLES ITSELF INSIDE A GIT WORKTREE ("JGit unsupported separate worktree checkout"),
#     so --fast is a no-op for any agent dispatched with worktree isolation. It says so rather
#     than letting a full build be mistaken for a fast one.
#
# This build REPLACES kweblens-web/target/kweblens.jar, and doing so under a running
# instance destroys it (#394): maven-jar-plugin truncates the live inode and writes the
# 600 KB thin jar into it, so the JVM is left holding a jar with no BOOT-INF/lib. Because
# a fat jar is read lazily, nothing announces this — the process stays up, keeps its port,
# keeps answering /actuator/health with "UP", and fails on the first class it had not yet
# loaded. Anything measured against it after that point is measuring a corpse.
#
# So the build refuses to start while an EXPOSED instance is up. Instances started by the
# current scripts/dev-run.sh run from their own per-port copy of the jar and are not
# exposed, so in normal use this check is silent and costs nothing; it fires only for one
# started by an older dev-run.sh or by a hand-rolled `java -jar`, which is exactly when
# there is something real to lose.

set -euo pipefail
cd "$(dirname "$0")/.."

FORCE="${KWEBLENS_VERIFY_FORCE:-0}"
FAST=0
ARGS=()
for arg in "$@"; do
	case "$arg" in
		--force) FORCE=1 ;;
		--fast) FAST=1 ;;
		*) ARGS+=("$arg") ;;
	esac
done

# Gates that live in ONE module but assert about paths OUTSIDE it. An incremental reactor can
# skip their module while the defect sits in a module it did build, so --fast runs them by name
# instead of trusting the selection. Today there is exactly one: kweblens-core's
# TrackedSourcesStayGreppableTest walks every path `git ls-files` prints.
#
# By name, and not GIB's own -Dgib.forceBuildModules, because the cheaper instrument covers the
# same hole: measured on this reactor, force-building all of kweblens-core costs 2m15s of the
# 8m43s full gate, while running just this test costs 21s. Add a "module:Test" pair here the
# moment a gate grows the same reach -- a fast path that can go green over a defect the real gate
# fails on is worse than no fast path.
REPO_WIDE_GATES=("kweblens-core:TrackedSourcesStayGreppableTest")

JAR=kweblens-web/target/kweblens.jar

# Instances this build would write through: a java process whose `-jar` argument resolves,
# via ITS OWN cwd, to the very file this build replaces. Another checkout's instance has a
# different target/ and is none of our business; naming it would be noise that trains the
# reader to ignore the warning.
exposed_instances() {
	local pid cwd arg jar want
	[[ -e "$JAR" ]] || return 0
	want=$(readlink -f "$JAR" 2>/dev/null) || return 0
	for pid in $(pgrep -f "kweblens-web/target/kweblens" 2>/dev/null); do
		[[ "$(ps -o comm= -p "$pid" 2>/dev/null)" == "java" ]] || continue
		cwd=$(readlink "/proc/${pid}/cwd" 2>/dev/null) || continue
		arg=$(tr '\0' '\n' < "/proc/${pid}/cmdline" 2>/dev/null |
			awk 'prev == "-jar" { print; exit } { prev = $0 }')
		[[ -n "$arg" ]] || continue
		if [[ "$arg" == /* ]]; then jar="$arg"; else jar="${cwd%/}/${arg}"; fi
		[[ "$(readlink -f "$jar" 2>/dev/null)" == "$want" ]] && echo "$pid"
	done
	return 0
}

EXPOSED=$(exposed_instances)
if [[ -n "$EXPOSED" && "$FORCE" == 1 ]]; then
	# Forced. Still say it, and still name them — the point of --force is to proceed
	# knowingly, not quietly. These instances are about to become unreliable.
	{
		echo "!!"
		echo "!!  --force: building anyway. These instances read ${JAR} and will be"
		echo "!!  left alive, listening and unreliable — restart them before measuring anything:"
		for pid in $EXPOSED; do
			port=$(tr '\0' '\n' < "/proc/${pid}/environ" 2>/dev/null | sed -n 's/^PORT=//p' | head -1)
			echo "!!    pid ${pid}  :${port:-?}"
		done
		echo "!!"
	} >&2
elif [[ -n "$EXPOSED" ]]; then
	{
		echo "!!"
		echo "!!  REFUSING TO BUILD: this build replaces ${JAR}, and these running"
		echo "!!  instances are reading that exact file. Replacing it will break them in a way"
		echo "!!  that keeps them listening and keeps them looking healthy (#394):"
		echo "!!"
		for pid in $EXPOSED; do
			port=$(tr '\0' '\n' < "/proc/${pid}/environ" 2>/dev/null | sed -n 's/^PORT=//p' | head -1)
			started=$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^ *//')
			echo "!!    pid ${pid}  :${port:-?}  since ${started:-?}"
		done
		echo "!!"
		echo "!!  Pick one:"
		for pid in $EXPOSED; do
			port=$(tr '\0' '\n' < "/proc/${pid}/environ" 2>/dev/null | sed -n 's/^PORT=//p' | head -1)
			[[ -n "$port" ]] && echo "!!    scripts/dev-run.sh --port ${port} --stop     # stop it"
		done
		echo "!!    scripts/dev-run.sh --port <port>              # restart it; the current script"
		echo "!!                                                  # insulates it from future builds"
		echo "!!    scripts/dev-verify.sh --force                 # build anyway and lose them"
		echo "!!"
	} >&2
	exit 3
fi

echo "==> spring-javaformat:apply"
./mvnw -q spring-javaformat:apply

GIB=()
if [[ "$FAST" == 1 ]]; then
	# A worktree is the one place --fast cannot work, and the failure is silent: GIB logs one
	# INFO line and stands down, leaving a full build that looks like a fast one. Say it here,
	# where the reader is, rather than in a line of Maven output they will not read.
	if [[ -f .git ]]; then
		echo "==> --fast IGNORED: this is a git worktree, and gitflow-incremental-builder" >&2
		echo "    disables itself in one (JGit cannot read a separate worktree checkout)." >&2
		echo "    Running the full reactor instead — which is the gate, so this is safe." >&2
	else
		mapfile -t CHANGED < <({
			git diff --name-only origin/main...HEAD
			git diff --name-only
			git ls-files --others --exclude-standard
		} 2>/dev/null | sort -u)

		# Why a full build happens is worth printing: "it built everything again" with no reason
		# is what teaches someone to stop trusting the flag.
		ROOT_CHANGES=()
		for f in ${CHANGED[@]+"${CHANGED[@]}"}; do
			[[ "$f" == kweblens-*/* ]] || ROOT_CHANGES+=("$f")
		done

		echo "==> --fast: ${#CHANGED[@]} changed path(s) vs origin/main"
		if [[ ${#ROOT_CHANGES[@]} -gt 0 ]]; then
			echo "    ${#ROOT_CHANGES[@]} of them sit outside every module, so they belong to the"
			echo "    parent POM and the whole reactor is in scope:"
			printf '      %s\n' "${ROOT_CHANGES[@]}" | head -10
			[[ ${#ROOT_CHANGES[@]} -gt 10 ]] && echo "      ... and $(( ${#ROOT_CHANGES[@]} - 10 )) more"
		fi
		GIB=(-Dgib.disable=false)
	fi
fi

# Cheap and first: a control byte in a module the incremental reactor DOES build is still a
# defect the incremental reactor cannot see, and there is no point spending the verify to find
# out. Skipped on a full build only because the reactor runs these gates itself.
if [[ ${#GIB[@]} -gt 0 ]]; then
	for gate in "${REPO_WIDE_GATES[@]}"; do
		echo "==> repo-wide gate: ${gate#*:} (in ${gate%%:*})"
		./mvnw -B -q -pl "${gate%%:*}" -Dtest="${gate#*:}" -Dsurefire.failIfNoSpecifiedTests=false test
	done
fi

echo "==> verify ${ARGS[*]:-(full reactor)}"
./mvnw -B verify ${GIB[@]+"${GIB[@]}"} ${ARGS[@]+"${ARGS[@]}"}

if [[ ${#GIB[@]} -gt 0 ]]; then
	echo "==> OK — formatted, repo-wide gates run, and verified FOR THE SELECTED MODULES ONLY."
	echo "    This is not the gate. Run scripts/dev-verify.sh with no --fast before pushing."
else
	echo "==> OK — formatted and verified"
fi
