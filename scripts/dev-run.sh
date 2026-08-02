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
		--port) PORT="$2"; shift 2 ;;
		-h|--help) sed -n '2,37p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

if [[ "$STOP" == true ]]; then
	stop_port
	exit 0
fi

if [[ "$BUILD" == true || ! -f "$JAR" ]]; then
	# -am is required: kweblens-web depends on sibling modules, and a -pl build
	# without it fails to resolve them.
	echo "==> building (-pl kweblens-web -am)"
	./mvnw -q -pl kweblens-web -am package -DskipTests
fi

stop_port

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
nohup env "${ENV_VARS[@]}" java -jar "$JAR" > "$LOG" 2>&1 &

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
