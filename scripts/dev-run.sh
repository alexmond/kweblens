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
#   scripts/dev-run.sh --list          # every kweblens on this box: port, pid, age, jar SHA
#   scripts/dev-run.sh --stop-stale    # stop the ones built from a jar that is no longer HEAD
#   scripts/dev-run.sh --stop-all      # stop every one of them
#   scripts/dev-run.sh --self-check    # prove the instance detection still works (see below)
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
		-h|--help) sed -n '2,38p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
		*) echo "unknown option: $1" >&2; exit 2 ;;
	esac
done

JAR=kweblens-web/target/kweblens.jar
LOG="${TMPDIR:-/tmp}/kweblens-dev-${PORT}.log"

stop_port() {
	local pids
	# Match on the port rather than a process-name pattern: pkill -f on something
	# like "kweblens" also matches this script's own argv and kills the shell.
	pids=$(lsof -ti "tcp:${PORT}" 2>/dev/null || true)
	if [[ -n "$pids" ]]; then
		echo "==> stopping pid(s) on :${PORT}: ${pids}"
		# shellcheck disable=SC2086
		kill $pids 2>/dev/null || true
		sleep 2
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
	local pid
	for pid in $(pgrep -f "kweblens-web/target/kweblens.jar" 2>/dev/null); do
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
		[[ -n "$(ps -o args= -p "$pid" 2>/dev/null | grep -F 'kweblens-web/target/kweblens.jar')" ]] && found_jar=1
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
	return 0
}

port_of() {
	lsof -Pan -p "$1" -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {sub(/.*:/, "", $9); print $9}'
}

# The jar a running instance was started from, and whether it still matches the tree. An
# instance whose jar predates HEAD is serving code nobody is looking at any more, which is
# the same trap the staleness rebuild above exists to prevent — just discovered later.
jar_age_of() {
	local pid="$1" jar
	jar=$(ls -l "/proc/$pid/cwd" 2>/dev/null >/dev/null && echo "$JAR")
	[[ -f "$JAR" ]] || { echo "?"; return; }
	if [[ "/proc/$pid/exe" -nt "$JAR" ]] 2>/dev/null; then echo "?"; else echo ""; fi
}

list_instances() {
	local pid port started rss found=0
	printf "%-8s %-7s %-9s %-22s %s\n" PID PORT RSS STARTED STATE
	for pid in $(instances); do
		found=1
		port=$(port_of "$pid"); port=${port:-?}
		started=$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^ *//')
		rss=$(ps -o rss= -p "$pid" 2>/dev/null | awk '{printf "%.0fM", $1/1024}')
		local state="running"
		# Started before the newest source file => serving code that no longer exists here.
		if [[ -n "$(find pom.xml kweblens-*/pom.xml kweblens-*/src -newermt "$started" -print -quit 2>/dev/null)" ]]; then
			state="STALE (source is newer)"
		fi
		printf "%-8s %-7s %-9s %-22s %s\n" "$pid" "$port" "$rss" "$started" "$state"
	done
	[[ "$found" == 0 ]] && echo "(none running)"
	return 0
}

stop_pid() {
	local pid="$1" port="$2"
	echo "==> stopping :${port:-?} (pid $pid)"
	kill "$pid" 2>/dev/null || true
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
	for pid in $(instances); do stop_pid "$pid" "$(port_of "$pid")"; done
	sleep 2
	list_instances
	exit 0
fi

if [[ "$STOP_STALE" == true ]]; then
	# Only the ones whose source tree has moved on under them. A deliberately-kept instance
	# on current code survives, which is why this is not just --stop-all with extra steps.
	stopped=0
	for pid in $(instances); do
		started=$(ps -o lstart= -p "$pid" 2>/dev/null | sed 's/^ *//')
		if [[ -n "$(find pom.xml kweblens-*/pom.xml kweblens-*/src -newermt "$started" -print -quit 2>/dev/null)" ]]; then
			stop_pid "$pid" "$(port_of "$pid")"; stopped=$((stopped + 1))
		fi
	done
	echo "==> stopped $stopped stale instance(s)"
	sleep 1
	list_instances
	exit 0
fi

if [[ "$STOP" == true ]]; then
	stop_port
	exit 0
fi

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
		port=$(lsof -Pan -p "$pid" -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {sub(/.*:/, "", $9); print $9}')
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
setsid nohup env "${ENV_VARS[@]}" java -jar "$JAR" > "$LOG" 2>&1 &

for _ in $(seq 1 90); do
	if curl -sf "localhost:${PORT}/actuator/health" >/dev/null 2>&1; then
		echo "==> up: http://localhost:${PORT}/   login admin/admin"
		# If this ever prints, the credentials above did not take effect.
		if grep -q "generated one for this run" "$LOG"; then
			echo "!!  WARNING: a password was generated — admin/admin will NOT work" >&2
			grep "generated one for this run" "$LOG" >&2
			exit 1
		fi
		exit 0
	fi
	sleep 2
done

echo "==> did not come up within 180s; last log lines:" >&2
tail -20 "$LOG" >&2
exit 1
