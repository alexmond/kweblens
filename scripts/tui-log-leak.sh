#!/usr/bin/env bash
#
# tui-log-leak.sh — does the TUI's log pane actually let go of the API server? (#369)
#
# WHY THIS EXISTS
# ---------------
# `LogWatch.close()` does not stop the `watchLog()` flavour kweblens uses; only closing the stream
# does, which is what `LogService.release` is for. A pane that ends a follow any other way leaves
# the HTTP request open and the reader parked — and **only a QUIET pod exposes it**, because a
# chatty one releases by accident when the failed downstream write throws out of the read loop.
# `ScreenLogPaneTest` gates the shape hermetically; this measures the real thing on a real cluster,
# which is the only place the trap is real.
#
# Read-only. It opens a log pane and closes it, N times, and reads two counters off the JVM.
#
# WHAT IT COUNTS, AND ONE INSTRUMENT THAT LIED
# --------------------------------------------
# Two numbers, because either alone can be fooled:
#
#   * **reader threads** — `kweblens-tui-log-<pod>`, one per live follow. A released follow's
#     reader ends when the stream closes; a leaked one's stays parked forever.
#   * **established sockets** — the JVM's TCP connections.
#
# Measured on k3stest, 2026-08-18, 20 open/close cycles against `coredns` (which logs nothing):
#
#     release through LogStream.close()   ->   0 reader threads,  2 sockets  (3 before)
#     close() that does nothing at all    ->  20 reader threads, 22 sockets  (3 before)
#
# **The trap is picking a break that is not one.** An earlier run replaced the release with
# `reader.interrupt()` and reported 0 threads and 2 sockets — identical to the correct build — and
# the first reading of that was "sockets cannot see this, fabric8 must be multiplexing over
# HTTP/2". It was not: interrupting the reader tears the connection down by itself, so the
# "leaking" build was not leaking. A control has to be a control. Before believing this script
# says nothing is wrong, break the release into a genuine no-op and check it says 20.
#
# Usage:
#   scripts/tui-log-leak.sh <context> [cycles]
#   scripts/tui-log-leak.sh k3stest 20
#
# Needs: tmux, jcmd, ss, and a built kweblens-tui/target/kweblens-tui-exec.jar.
set -uo pipefail

CTX="${1:?usage: tui-log-leak.sh <kube-context> [cycles]}"
CYCLES="${2:-20}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/kweblens-tui/target/kweblens-tui-exec.jar"

if [[ ! -f "$JAR" ]]; then
	echo "no jar at $JAR — run: ./mvnw -pl kweblens-tui -am package -DskipTests" >&2
	exit 1
fi

SOCK="kwleak-$$"
SESS="leak"
cleanup() { tmux -L "$SOCK" kill-server 2>/dev/null; }
trap cleanup EXIT

tmux -L "$SOCK" new-session -d -s "$SESS" -x 132 -y 44 "java -jar '$JAR' --context '$CTX'; sleep 60"
sleep 12

PID="$(pgrep -f "kweblens-tui-exec.jar --context $CTX" | head -1)"
if [[ -z "$PID" ]]; then
	echo "the app did not start; see .tui/ and the log" >&2
	exit 1
fi

readers() { jcmd "$PID" Thread.print 2>/dev/null | grep -c '"kweblens-tui-log-'; }
sockets() { ss -tnp 2>/dev/null | grep -c "pid=$PID,"; }

echo "context $CTX  pid $PID  cycles $CYCLES"
echo "before:  $(readers) reader threads, $(sockets) sockets"

for _ in $(seq 1 "$CYCLES"); do
	tmux -L "$SOCK" send-keys -t "$SESS" l
	sleep 0.45
	tmux -L "$SOCK" send-keys -t "$SESS" Escape
	sleep 0.35
done
sleep 3

LEAKED="$(readers)"
echo "after:   $LEAKED reader threads, $(sockets) sockets"

# The keyboard is the other half: a pane that leaks and a pane that has wedged the event loop
# both stop being usable, and only one of them is this script's subject.
tmux -L "$SOCK" send-keys -t "$SESS" '?'
sleep 1
tmux -L "$SOCK" capture-pane -p -t "$SESS" | sed -n '1,3p'

if [[ "$LEAKED" -gt 0 ]]; then
	echo "FAIL: $LEAKED follows are still holding the API server after being closed" >&2
	exit 1
fi
echo "ok: every follow was released"
