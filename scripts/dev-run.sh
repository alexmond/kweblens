#!/usr/bin/env bash
#
# Run kweblens locally for hands-on checking, with a known login.
#
# Exists because starting the jar by hand is easy to get subtly wrong: with no
# admin password set, SecurityConfig GENERATES one per run and only logs it, so
# admin/admin stops working and the reason is buried in the startup output. This
# script always sets the dev credentials, so the login is the same every time.
#
# Usage:
#   scripts/dev-run.sh                 # build if needed, then run on :8080
#   scripts/dev-run.sh --build         # force a rebuild first
#   scripts/dev-run.sh --port 8085     # run somewhere else (parallel instances)
#   scripts/dev-run.sh --sim           # cluster-free: the built-in simulator
#   scripts/dev-run.sh --ai            # LLM enrichment of /diagnose (needs a key, see below)
#   scripts/dev-run.sh --files         # pod file browser ON, read-write (see below)
#   scripts/dev-run.sh --files=ro      # pod file browser ON, browse-and-download only
#   scripts/dev-run.sh --files-roots /tmp   # ...and confine it to those roots
#   scripts/dev-run.sh --stop          # stop whatever is on the port
#   scripts/dev-run.sh --list          # every kweblens on this box: port, pid, age, HEALTH
#   scripts/dev-run.sh --stop-stale    # stop the ones built from a jar that is no longer HEAD
#   scripts/dev-run.sh --stop-all      # stop every one of them
#   scripts/dev-run.sh --self-check    # prove the instance detection still works (see below)
#
# --list ASKS each instance whether it serves; it does not infer it from the process
# table. A Spring Boot fat jar is read lazily, so a build that replaces the jar under a
# running JVM leaves it alive and listening but unable to load another class (#394). The
# process is still there and the port is still bound, so every process-shaped check calls
# it healthy. See `health_of` for what is actually asked and why /actuator/health alone is
# not enough. Probe timeout: KWEBLENS_PROBE_TIMEOUT seconds, default 4.
#
# An instance is started from its OWN COPY of the jar (kweblens-run-<port>.jar) so that a
# later build cannot reach into it. See the RUN_JAR comment for the trade that makes.
#
# Login is admin/admin. These are DEV credentials passed as environment at run
# time — do not add them to application.yml, which would bake a default password
# into the repo.
#
# --files turns on the pod file browser (kweblens.files.enabled), which is OFF by
# default because a container's disk holds mounted Secrets and its service-account
# token. --files is read-write; --files=ro leaves kweblens.files.writable=false, which
# is the combination to use when only the refusal paths are being checked. There is no
# way to reach a container's filesystem without this flag, so a Files tab that reports
# "switched off" means the app was started without it.
#
# --files-roots takes a comma-separated list of absolute paths and sets
# kweblens.files.allowed-roots, confining the browser to them. It implies --files.
# The confinement is checked twice: once on the requested path, and again on the path
# the container itself resolves (readlink -f), so a symlink INSIDE a root that points
# outside it is refused — which is the case worth exercising, and the reason this flag
# exists rather than a hand-assembled environment variable.
#
# --ai turns on LLM enrichment, which needs an Anthropic key. The key is read
# from the environment and NEVER stored here: set ANTHROPIC_API_KEY, or
# VANTAGE_ANTHROPIC_API_KEY which this box already has. Without one the flag
# refuses to start rather than booting a build whose AI silently does nothing.
# Only the prose summary over /diagnose findings depends on it — the findings
# themselves, the remediation proposals and the dry run are all deterministic.

set -euo pipefail
cd "$(dirname "$0")/.."

PORT=8080
BUILD=false
SIM=false
STOP=false
LIST=false
SELF_CHECK=false
STOP_ALL=false
STOP_STALE=false
AI=false
FILES=off
FILES_ROOTS=

while [[ $# -gt 0 ]]; do
	case "$1" in
		--build) BUILD=true; shift ;;
		--sim) SIM=true; shift ;;
		--ai) AI=true; shift ;;
		--files) FILES=rw; shift ;;
		--files=rw) FILES=rw; shift ;;
		--files=ro) FILES=ro; shift ;;
		--files-roots) FILES_ROOTS="$2"; shift 2 ;;
		--files-roots=*) FILES_ROOTS="${1#*=}"; shift ;;
		--stop) STOP=true; shift ;;
		--list) LIST=true; shift ;;
		--self-check) SELF_CHECK=true; shift ;;
		--stop-all) STOP_ALL=true; shift ;;
		--stop-stale) STOP_STALE=true; shift ;;
		--port) PORT="$2"; shift 2 ;;
		-h|--help) sed -n '2,44p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
		*) echo "unknown option: $1" >&2; exit 2 ;;
	esac
done

# The jar the BUILD writes. Every staleness decision below is made against this file,
# because it is the one that tracks the source tree.
JAR=kweblens-web/target/kweblens.jar

# The jar an instance is actually STARTED from: a per-port copy, made at launch.
#
# Why a copy. `mvn package` does not append to the jar, it TRUNCATES THE LIVE INODE and
# writes the 600 KB thin jar into it (maven-jar-plugin), and only afterwards renames that
# inode to kweblens.jar.original and puts a fresh fat jar at the path (spring-boot:repackage).
# Measured on a running instance in #394: its open fds moved from `…/kweblens.jar` to
# `…/kweblens.jar.original`, i.e. the JVM was left reading a jar with no BOOT-INF/lib at all.
# Because a fat jar is read lazily the JVM does not crash — it keeps its port, keeps
# answering /actuator/health with "UP", and dies on the first class it had not yet loaded.
# Starting from a private copy removes the hazard outright: the build never opens this path.
#
# THE TRADE. (1) One extra file per instance under target/. On btrfs/XFS `cp --reflink=auto`
# shares the extents so it costs ~nothing; elsewhere it is a real ~110 MB copy. Orphans are
# reaped at every start (reap_run_jars). (2) The running jar is no longer literally the file
# the build writes, so "am I running what I just built" can no longer be answered by looking
# at that file — which is why it must keep being answered by --list, and why the STALE
# (source is newer) column and the jar-replaced check below are load-bearing, not decoration.
RUN_JAR="kweblens-web/target/kweblens-run-${PORT}.jar"

LOG="${TMPDIR:-/tmp}/kweblens-dev-${PORT}.log"

# Terminate pids and CONFIRM they are gone, rather than assuming SIGTERM was obeyed.
#
# The earlier version signalled, slept 2s and printed success unconditionally. That is the
# "never call an unanswered check clear" failure this repo keeps re-learning: a run reported
# "stopping pid(s)" while the JVM stayed resident at over a gigabyte with the simulator's mock
# API-server port still bound, and only `--list` (afterwards, by chance) showed it. This app
# closes cluster watches and log streams on the way down, so a clean shutdown is not instant
# and 2 seconds is sometimes not enough — but "slow" and "ignored the signal" must not look
# the same from here.
#
# So: SIGTERM, wait, escalate to SIGKILL, wait again, and say which of those happened. Exits
# non-zero if anything is still alive at the end, so a caller cannot treat a failed stop as a
# stop.
terminate() {
	local pids=("$@") grace=20 pid alive=()
	[[ ${#pids[@]} -eq 0 ]] && return 0
	kill "${pids[@]}" 2>/dev/null || true
	for ((i = 0; i < grace; i++)); do
		alive=()
		for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null && alive+=("$pid"); done
		[[ ${#alive[@]} -eq 0 ]] && return 0
		sleep 1
	done
	echo "!!  pid(s) ${alive[*]} ignored SIGTERM after ${grace}s — sending SIGKILL" >&2
	kill -9 "${alive[@]}" 2>/dev/null || true
	sleep 2
	local left=()
	for pid in "${alive[@]}"; do kill -0 "$pid" 2>/dev/null && left+=("$pid"); done
	if [[ ${#left[@]} -gt 0 ]]; then
		echo "!!  STILL RUNNING after SIGKILL: ${left[*]} — investigate, do not assume stopped" >&2
		return 1
	fi
	return 0
}

stop_port() {
	local pids
	# Match on the port rather than a process-name pattern: pkill -f on something
	# like "kweblens" also matches this script's own argv and kills the shell.
	pids=$(lsof -ti "tcp:${PORT}" 2>/dev/null || true)
	if [[ -n "$pids" ]]; then
		echo "==> stopping pid(s) on :${PORT}: ${pids}"
		# shellcheck disable=SC2086
		terminate $pids || return 1
		echo "==> stopped :${PORT}"
	fi
}

# ---- Instance management -------------------------------------------------------------
#
# Instances are deliberately DETACHED (setsid), so they survive the shell — and the agent,
# and the editor — that started them. That is the behaviour you want: restarting your tools
# should not kill the app you are looking at. The cost is that nobody is left who remembers
# they exist, and two were once found here still holding cluster watches days after the run
# that started them had finished.
#
# So: enumerate them, and be able to reap them. The PROCESS TABLE is the source of truth,
# never a state file — a registry that goes stale points at a pid that has been recycled,
# and killing the wrong process is far worse than failing to kill the right one. Everything
# below is derived live from `pgrep` on the jar path.
instances() {
	# Two failure modes, opposite directions, and the obvious guard against the first
	# silently causes the second:
	#
	#   `pgrep -f PATTERN` alone matches ANY process whose argv contains the string —
	#   including the shell running a script that merely mentions it, which is how a
	#   --stop-all once SIGTERMed the very shell invoking it (exit 144). Verified still
	#   true: a bare `pgrep -f` here returns this script's own pid alongside the jar's.
	#
	#   `pgrep -x java -f PATTERN` was the fix for that, and it matched NOTHING — so
	#   --list reported "(none running)" against a server that was answering on :8080,
	#   and --stop-all/--stop-stale silently stopped nothing. With `-f`, pgrep matches
	#   the joined command line, which ends in a trailing separator, so `-x` (whole
	#   string, anchored both ends) cannot match however the pattern is written: the
	#   exact literal `java -jar kweblens-web/target/kweblens.jar` fails too. `-x` was
	#   not tightening the match, it was disabling it.
	#
	# So: match loosely on the jar path, then keep only processes whose EXECUTABLE is
	# java. That is the "guard each PID on comm == java" rule from CLAUDE.md, and unlike
	# `-x` it is testable — `--self-check` below asserts the jar is found and this very
	# shell is not.
	#
	# The pattern stops at `kweblens-web/target/kweblens` — no `.jar` — so it matches BOTH
	# `kweblens.jar` and the per-port `kweblens-run-<port>.jar` this script now starts from.
	# That is deliberate: other checkouts on this box are still running an older version of
	# this script, and an instance list that quietly stopped seeing them would be the same
	# "(none running)" lie the `-x` bug told.
	local pid
	for pid in $(pgrep -f "kweblens-web/target/kweblens" 2>/dev/null); do
		[[ "$(ps -o comm= -p "$pid" 2>/dev/null)" == "java" ]] && echo "$pid"
	done
	return 0
}

# A positive control for the above, because both of its historical failures were INVISIBLE:
# one killed the wrong thing, the other reported an empty list that looks exactly like a
# clean machine. Run it whenever this detection is touched.
self_check() {
	local found_jar=0 found_self=0 pid
	for pid in $(instances); do
		[[ "$pid" == "$$" ]] && found_self=1
		[[ -n "$(ps -o args= -p "$pid" 2>/dev/null | grep -F 'kweblens-web/target/kweblens')" ]] && found_jar=1
	done
	if [[ $found_self -eq 1 ]]; then
		echo "FAIL: instances() matched this shell (pid $$) — the exit-144 self-kill trap" >&2
		return 1
	fi
	if [[ $(instances | wc -l) -eq 0 ]]; then
		echo "self-check: no instance running — start one, then re-run to exercise the match" >&2
		return 0
	fi
	if [[ $found_jar -eq 0 ]]; then
		echo "FAIL: instances() returned a pid that is not the kweblens jar" >&2
		return 1
	fi
	echo "self-check OK: jar found, this shell (pid $$) correctly excluded"
	health_check_control || return 1
	return 0
}

# The positive control for health_of, and it has to be a control in BOTH directions.
#
# "A liveness check that says unhealthy for everything is the same defect wearing the other
# face" — so this asserts two things against known answers: a port that accepts a connection
# and then says nothing must NOT read as serving, and a real instance must. The first half is
# the exact shape the #394 wedges took, and it is also why a TCP-connect check would have been
# useless: the listener in this control is genuinely bound and genuinely accepting.
health_check_control() {
	local ctl_port=0 ctl_pid verdict ok=0
	if ! command -v python3 >/dev/null 2>&1; then
		echo "!!  control SKIPPED: no python3, so the accept-but-never-answer case was NOT tested" >&2
		echo "!!  that is an unmeasured half, not a pass" >&2
	else
		# Binds an ephemeral port, accepts, and never writes a byte.
		ctl_port=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
		python3 -c "
import socket, time
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('127.0.0.1', ${ctl_port}))
s.listen(8)
end = time.time() + 60
while time.time() < end:
    try:
        s.settimeout(1)
        c, _ = s.accept()
    except OSError:
        continue
" >/dev/null 2>&1 &
		ctl_pid=$!
		sleep 1
		verdict=$(health_of "$ctl_port")
		kill "$ctl_pid" 2>/dev/null || true
		wait "$ctl_pid" 2>/dev/null || true
		if [[ "$verdict" == "serving" ]]; then
			echo "FAIL: a socket that accepts and never answers read as '${verdict}' on :${ctl_port}" >&2
			return 1
		fi
		echo "self-check OK: accept-but-never-answer on :${ctl_port} reads as '${verdict}', not serving"
	fi

	# The other direction. Without a live instance this half cannot run, and saying so is the
	# point — a control that only ever proves the negative proves nothing about the positive.
	local pid port
	for pid in $(instances); do
		port=$(port_of "$pid")
		[[ -z "$port" ]] && continue
		verdict=$(health_of "$port")
		if [[ "$verdict" == "serving" ]]; then
			echo "self-check OK: a real instance on :${port} (pid ${pid}) reads as 'serving'"
			ok=1
			break
		fi
	done
	if [[ $ok -eq 0 ]]; then
		echo "!!  no instance on this box currently reads as 'serving', so the POSITIVE half of" >&2
		echo "!!  the health control did not run. Start one and re-run --self-check." >&2
	fi
	return 0
}

# The HTTP port an instance serves on — which is NOT simply its first listening socket.
#
# The old version took `lsof … | awk 'NR==2'`, i.e. whichever LISTEN row came back first.
# Measured against an instance started with `--port 8391`: that row was `127.0.0.1:37619`,
# the simulator's in-JVM API server, and --list printed 37619 as the app's port. Every
# number in that column was wrong whenever the simulator was on, and nothing said so.
#
# Three sources, most authoritative first:
#   1. PORT= in the process environment. This script sets it, so for anything it started
#      the answer is exact and needs no guessing about sockets.
#   2. A listener bound to the WILDCARD address. Tomcat binds *:PORT; the sim's mock API
#      server and the JVM's own helpers bind loopback only.
#   3. Failing both, the first LISTEN row — the old behaviour, kept as a last resort so a
#      hand-started `java -jar` still gets a number rather than a blank.
port_of() {
	local pid="$1" port
	port=$(tr '\0' '\n' < "/proc/${pid}/environ" 2>/dev/null | sed -n 's/^PORT=//p' | head -1)
	if [[ -n "$port" ]]; then
		echo "$port"
		return 0
	fi
	port=$(lsof -Pan -p "$pid" -iTCP -sTCP:LISTEN 2>/dev/null | awk '$9 ~ /^\*:/ {sub(/.*:/, "", $9); print $9; exit}')
	if [[ -n "$port" ]]; then
		echo "$port"
		return 0
	fi
	lsof -Pan -p "$pid" -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {sub(/.*:/, "", $9); print $9}'
}

# The absolute path of the jar a process was started from, resolved through ITS cwd.
#
# Never assume `$JAR` — several checkouts run on this box and each has its own target/.
# argv is NUL-separated; the jar is the argument after `-jar`.
jar_of() {
	local pid="$1" cwd arg
	cwd=$(readlink "/proc/${pid}/cwd" 2>/dev/null) || return 1
	arg=$(tr '\0' '\n' < "/proc/${pid}/cmdline" 2>/dev/null | awk 'prev == "-jar" { print; exit } { prev = $0 }')
	[[ -n "$arg" ]] || return 1
	if [[ "$arg" == /* ]]; then echo "$arg"; else echo "${cwd%/}/${arg}"; fi
}

# Has the file this JVM is reading been replaced since it started?
#
# This is the check that catches a sabotaged instance BEFORE it has any symptom — the case
# that matters most, because such an instance answers every request correctly until it
# happens to need a class it has not loaded, which may be minutes later and in the middle of
# a measurement. Two independent signals, either of which is conclusive:
#
#   (a) the open fd no longer resolves to the path it was started from. A rename leaves
#       `<jar> (deleted)`; a Maven build leaves `<jar>.original`, because maven-jar-plugin
#       truncates the live inode and repackage then renames it aside. Both measured in #394.
#   (b) the file at that path is NEWER than the process. Whatever the JVM is holding, it is
#       not what is on disk now. This catches an in-place overwrite that keeps the inode,
#       which (a) cannot see.
#
# Both are conservative: `ps -o lstart=` has one-second resolution and the comparison is
# strictly-newer, so a build that finishes in the same second as the launch reads as clean
# rather than raising a false alarm on a perfectly good instance.
jar_replaced() {
	local pid="$1" jar target fd started jar_epoch start_epoch
	jar=$(jar_of "$pid") || return 1
	for fd in "/proc/${pid}"/fd/*; do
		target=$(readlink "$fd" 2>/dev/null) || continue
		case "$target" in
			"$jar") ;;
			"$jar (deleted)") return 0 ;;
			"$jar".original*) return 0 ;;
		esac
	done
	[[ -f "$jar" ]] || return 0
	started=$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^ *//')
	[[ -n "$started" ]] || return 1
	start_epoch=$(date -d "$started" +%s 2>/dev/null) || return 1
	jar_epoch=$(stat -c %Y "$jar" 2>/dev/null) || return 1
	[[ "$jar_epoch" -gt "$start_epoch" ]]
}

# Does this instance SERVE? Asked, not inferred.
#
# `/actuator/health` alone is not the answer, and this is measured rather than assumed: on
# every wedged instance produced in #394 it kept returning 200 and `"status":"UP"` while the
# app served nothing at all. Its handler and every class it touches are loaded during startup,
# so it is precisely the endpoint that keeps working after the jar goes. A plain TCP connect
# is weaker still — the socket stays bound in all of these.
#
# So `/` is asked too. It is the endpoint the browser scripts actually measure against, it
# forwards into the SPA (a different, later-loaded code path), and in all four wedge shapes
# reproduced for #394 — hang, connection reset, non-200, and health-UP-but-nothing-else — it
# was the one that reported the truth. Both must pass before this says `serving`.
health_of() {
	local port="$1" timeout="${KWEBLENS_PROBE_TIMEOUT:-4}" out code body root
	if [[ -z "$port" || "$port" == "?" ]]; then
		echo "NOT LISTENING (no bound port)"
		return 0
	fi
	out=$(curl -s --connect-timeout 2 -m "$timeout" -w $'\n%{http_code}' \
		"http://localhost:${port}/actuator/health" 2>/dev/null || true)
	code=${out##*$'\n'}
	body=${out%$'\n'*}
	if [[ -z "$code" || "$code" == "000" ]]; then
		echo "NOT ANSWERING (health: no response)"
		return 0
	fi
	if [[ "$code" != "200" ]]; then
		echo "WEDGED (health HTTP ${code})"
		return 0
	fi
	if [[ "$body" != *'"status":"UP"'* ]]; then
		echo "DOWN (health said not UP)"
		return 0
	fi
	root=$(curl -s -o /dev/null --connect-timeout 2 -m "$timeout" -w '%{http_code}' \
		"http://localhost:${port}/" 2>/dev/null || true)
	if [[ -z "$root" || "$root" == "000" ]]; then
		echo "WEDGED (health UP, / no response)"
		return 0
	fi
	if [[ "$root" != "200" ]]; then
		echo "WEDGED (health UP, / HTTP ${root})"
		return 0
	fi
	echo "serving"
}

list_instances() {
	local pid port started rss found=0 health notes
	printf "%-8s %-7s %-8s %-25s %-34s %s\n" PID PORT RSS STARTED HEALTH NOTES
	for pid in $(instances); do
		found=1
		port=$(port_of "$pid"); port=${port:-?}
		started=$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^ *//')
		rss=$(ps -o rss= -p "$pid" 2>/dev/null | awk '{printf "%.0fM", $1/1024}')
		health=$(health_of "$port")
		notes=""
		# The jar was swapped under it. Worth saying even when it currently answers: such an
		# instance is not broken yet, it is broken from the next unloaded class onwards, and
		# anything measured against it in the meantime is worthless.
		if jar_replaced "$pid"; then
			notes="JAR REPLACED — a build swapped the file under it; restart before measuring"
		fi
		# Started before the newest source file => serving code that no longer exists here.
		# The literal string is load-bearing: other scripts and several agents look for it.
		if [[ -n "$started" && -n "$(find pom.xml kweblens-*/pom.xml kweblens-*/src -newermt "$started" -print -quit 2>/dev/null)" ]]; then
			notes="${notes:+${notes} · }STALE (source is newer)"
		fi
		printf "%-8s %-7s %-8s %-25s %-34s %s\n" "$pid" "$port" "$rss" "$started" "$health" "$notes"
	done
	[[ "$found" == 0 ]] && echo "(none running)"
	return 0
}

stop_pid() {
	local pid="$1" port="$2"
	echo "==> stopping :${port:-?} (pid $pid)"
	terminate "$pid" || return 1
}

if [[ "$SELF_CHECK" == true ]]; then
	self_check
	exit $?
fi

if [[ "$LIST" == true ]]; then
	list_instances
	exit 0
fi

if [[ "$STOP_ALL" == true ]]; then
	# Keep going when one refuses to die, but remember it: stopping three of four and
	# exiting 0 is the same lie `terminate` was written to stop telling.
	failed=0
	for pid in $(instances); do stop_pid "$pid" "$(port_of "$pid")" || failed=1; done
	list_instances
	exit $failed
fi

if [[ "$STOP_STALE" == true ]]; then
	# Only the ones whose source tree has moved on under them. A deliberately-kept instance
	# on current code survives, which is why this is not just --stop-all with extra steps.
	stopped=0
	failed=0
	for pid in $(instances); do
		started=$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^ *//')
		if [[ -n "$(find pom.xml kweblens-*/pom.xml kweblens-*/src -newermt "$started" -print -quit 2>/dev/null)" ]]; then
			if stop_pid "$pid" "$(port_of "$pid")"; then
				stopped=$((stopped + 1))
			else
				failed=1
			fi
		fi
	done
	echo "==> stopped $stopped stale instance(s)"
	list_instances
	exit "${failed:-0}"
fi

if [[ "$STOP" == true ]]; then
	stop_port || exit 1
	exit 0
fi

# Is the instance that just came up actually serving the front end in this tree?
#
# The rebuild below decides from mtimes and then says "rebuilding", and that line was taken as
# proof for a whole cycle of #327: the run printed it, the instance came up, and the CSS it
# served was the one from before the fix — so a fixed layout measured as unfixed and the fix
# looked wrong. The mtime check answers "should I build", which is not the same question as
# "is the running JVM serving what I just wrote", and only the second one protects a
# measurement. Vite content-hashes its bundles, so the answer is one string comparison:
# the asset names in the served index.html against the ones in kweblens-ui/dist.
#
# A warning, not a failure: the app IS up and everything that does not touch the SPA works.
# But it is printed loudly, because a stale bundle is invisible from the browser.
check_served_assets() {
	local built served
	[[ -f kweblens-ui/dist/index.html ]] || return 0
	built=$(grep -oE 'assets/index-[A-Za-z0-9_-]+\.(js|css)' kweblens-ui/dist/index.html | sort -u)
	served=$(curl -sf "localhost:${PORT}/" | grep -oE 'assets/index-[A-Za-z0-9_-]+\.(js|css)' | sort -u)
	[[ -n "$built" && -n "$served" ]] || return 0
	if [[ "$built" != "$served" ]]; then
		echo "!!  the SPA being served is NOT the one in kweblens-ui/dist" >&2
		echo "!!    served: $(echo "$served" | tr '\n' ' ')" >&2
		echo "!!    built:  $(echo "$built" | tr '\n' ' ')" >&2
		echo "!!  measure nothing about the UI until this matches — rerun with --build" >&2
	fi
}

# A jar older than the sources is the same class of trap as a generated admin
# password: everything starts cleanly and you spend the next hour measuring code
# you did not write. (This bit us on #228 — the merge landed at 03:42 and the run
# served a 02:10 jar, so a rewritten LLM prompt looked like it had not taken.)
# So rebuild when anything is NEWER than the jar, not only when the jar is absent.
newer_than_jar() {
	# -quit on the first hit: this runs on every start, and we only need to know
	# whether one such file exists.
	find pom.xml kweblens-*/pom.xml kweblens-*/src -newer "$JAR" -print -quit 2>/dev/null
}

STALE=""
if [[ -f "$JAR" ]]; then
	STALE=$(newer_than_jar)
fi

if [[ "$BUILD" == true || ! -f "$JAR" || -n "$STALE" ]]; then
	if [[ -n "$STALE" ]]; then
		echo "==> jar is older than ${STALE} — rebuilding"
	fi
	# -am is required: kweblens-web depends on sibling modules, and a -pl build
	# without it fails to resolve them.
	echo "==> building (-pl kweblens-web -am)"
	./mvnw -q -pl kweblens-web -am package -DskipTests
fi

stop_port

# Instances on OTHER ports, which are usually somebody's forgotten one.
#
# A second instance is a supported thing — that is what --port is for — so this warns
# rather than kills. But an instance nobody remembers is not free: since #283 and #288 we
# know each one holds API-server watches and log streams open against a real cluster, and
# two were found here still running days after the agent that started them had gone, both
# predating the fixes for the leaks they were demonstrating. Age is the tell, so it is
# printed: a few minutes is a colleague, a few days is litter.
warn_other_instances() {
	local pid port started
	# Match on the jar path, not on "kweblens": a looser pattern also matches this script's
	# own argv and any editor or grep that happens to mention it.
	for pid in $(instances); do
		[[ "$pid" == "$$" ]] && continue
		port=$(port_of "$pid")
		[[ -z "$port" || "$port" == "$PORT" ]] && continue
		started=$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^ *//')
		echo "!!  another kweblens is on :${port} (pid ${pid}, since ${started})" >&2
		echo "!!    it holds cluster watches and log streams; stop it with: $0 --port ${port} --stop" >&2
	done
}
warn_other_instances

ENV_VARS=(
	KWEBLENS_SECURITY_ADMIN_USERNAME=admin
	KWEBLENS_SECURITY_ADMIN_PASSWORD=admin
	"PORT=${PORT}"
)
if [[ "$SIM" == true ]]; then
	# The generated `sim` cluster instead of the ambient kubeconfig, so the app is
	# usable with no cluster reachable at all.
	ENV_VARS+=(KWEBLENS_SIMULATOR_ENABLED=true KWEBLENS_LOAD_KUBECONFIG=false)
fi

if [[ -n "$FILES_ROOTS" && "$FILES" == off ]]; then
	# Confining a feature that is switched off would be a silently useless run.
	FILES=rw
fi

if [[ "$FILES" != off ]]; then
	# Both gates are set explicitly, including the "off" one: kweblens.files.writable
	# defaults to true, so --files=ro has to say so rather than rely on a default.
	ENV_VARS+=(KWEBLENS_FILES_ENABLED=true "KWEBLENS_FILES_WRITABLE=$([[ "$FILES" == rw ]] && echo true || echo false)")
	echo "==> pod file browser ON ($([[ "$FILES" == rw ]] && echo read-write || echo browse-only))"
fi

if [[ -n "$FILES_ROOTS" ]]; then
	# Relaxed binding turns KWEBLENS_FILES_ALLOWED_ROOTS into the allowed-roots list;
	# a comma-separated value binds to List<String> without index gymnastics.
	ENV_VARS+=("KWEBLENS_FILES_ALLOWED_ROOTS=${FILES_ROOTS}")
	echo "==> pod file browser confined to: ${FILES_ROOTS}"
fi

if [[ "$AI" == true ]]; then
	KEY="${ANTHROPIC_API_KEY:-${VANTAGE_ANTHROPIC_API_KEY:-}}"
	if [[ -z "$KEY" ]]; then
		echo "--ai needs a key: set ANTHROPIC_API_KEY or VANTAGE_ANTHROPIC_API_KEY" >&2
		exit 2
	fi
	# Passed as environment only. Never echoed, never written to a file.
	ENV_VARS+=(KWEBLENS_AI_ENABLED=true "ANTHROPIC_API_KEY=${KEY}")
	echo "==> AI enrichment ON (key from ${ANTHROPIC_API_KEY:+ANTHROPIC_API_KEY}${ANTHROPIC_API_KEY:-VANTAGE_ANTHROPIC_API_KEY})"
fi

# Give this instance its own copy of the jar, so the next build cannot reach into it.
#
# Written to a temp name and RENAMED into place — never copied over the destination. Copying
# onto an existing file truncates that inode, which is exactly how the build breaks a running
# instance; doing it here would break the instance we are about to replace on this same port.
#
# --reflink=auto: a copy-on-write clone where the filesystem supports it (btrfs, XFS), a real
# copy where it does not. Nothing downstream depends on which happened.
prepare_run_jar() {
	cp --reflink=auto "$JAR" "${RUN_JAR}.tmp"
	mv -f "${RUN_JAR}.tmp" "$RUN_JAR"
}

# Delete per-port copies no live instance is holding. Without this every port ever used
# leaves a jar behind, and on a filesystem without reflink that is ~110 MB each.
reap_run_jars() {
	local f held pid
	held=""
	for pid in $(instances); do
		held="${held} $(jar_of "$pid" 2>/dev/null)"
	done
	for f in kweblens-web/target/kweblens-run-*.jar; do
		[[ -e "$f" ]] || continue
		case "$held" in
			*"$(readlink -f "$f")"*) continue ;;
		esac
		rm -f "$f"
	done
}

reap_run_jars
prepare_run_jar

echo "==> starting on :${PORT}  (log: ${LOG})"
# `setsid`, not just `nohup`. nohup only blocks SIGHUP — it does nothing about a SIGTERM
# sent to the whole process GROUP, which is how an editor, an agent runner or a terminal
# multiplexer usually tears down what it started. An instance launched with nohup alone
# therefore dies when the tool that ran this script exits, which is exactly the behaviour
# that is unwanted: the app you are looking at in a browser should not vanish because you
# restarted your editor. setsid puts it in a new session and a new process group, so it has
# no parent to be collected with. Verified: an instance started this way survives the shell
# that launched it being killed.
#
# The trade is that it can no longer be stopped with Ctrl-C or by closing the terminal —
# which is why `--stop`, `--stop-stale` and `--stop-all` exist, and why `--list` does.
setsid nohup env "${ENV_VARS[@]}" java -jar "$RUN_JAR" > "$LOG" 2>&1 &

for _ in $(seq 1 90); do
	if curl -sf "localhost:${PORT}/actuator/health" >/dev/null 2>&1; then
		echo "==> up: http://localhost:${PORT}/   login admin/admin"
		# If this ever prints, the credentials above did not take effect.
		if grep -q "generated one for this run" "$LOG"; then
			echo "!!  WARNING: a password was generated — admin/admin will NOT work" >&2
			grep "generated one for this run" "$LOG" >&2
			exit 1
		fi
		check_served_assets
		exit 0
	fi
	sleep 2
done

echo "==> did not come up within 180s; last log lines:" >&2
tail -20 "$LOG" >&2
exit 1
