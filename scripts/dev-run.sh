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
#   scripts/dev-run.sh --stop          # stop whatever is on the port
#
# Login is admin/admin. These are DEV credentials passed as environment at run
# time — do not add them to application.yml, which would bake a default password
# into the repo.

set -euo pipefail
cd "$(dirname "$0")/.."

PORT=8080
BUILD=false
SIM=false
STOP=false

while [[ $# -gt 0 ]]; do
	case "$1" in
		--build) BUILD=true; shift ;;
		--sim) SIM=true; shift ;;
		--stop) STOP=true; shift ;;
		--port) PORT="$2"; shift 2 ;;
		-h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
