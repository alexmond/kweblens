#!/usr/bin/env bash
#
# Local pre-commit build: apply the spring-javaformat style, then run the full
# Maven verify (compile, Checkstyle, PMD, tests, JaCoCo). This mirrors what CI
# runs, so a green run here means a green run on the PR.
#
# Usage:
#   scripts/dev-verify.sh            # format + verify the whole reactor
#   scripts/dev-verify.sh -pl kweblens-web -am   # pass extra args through to Maven
#   scripts/dev-verify.sh --force    # build anyway, with a running instance exposed
#
# Any arguments other than --force are forwarded to the `verify` invocation.
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
ARGS=()
for arg in "$@"; do
	if [[ "$arg" == "--force" ]]; then FORCE=1; else ARGS+=("$arg"); fi
done

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

echo "==> verify ${ARGS[*]:-(full reactor)}"
./mvnw -B verify ${ARGS[@]+"${ARGS[@]}"}

echo "==> OK — formatted and verified"
