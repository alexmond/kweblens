#!/usr/bin/env bash
#
# tui-drive.sh — drive kweblens-tui on a REAL terminal, send keys, read the frame back as text.
#
# WHY THIS EXISTS (#426)
# ---------------------
# Every claim ever made about `kweblens-tui` came from `FakeBackend`: the real `TuiRunner.run`,
# the real input reader, the real `pollEvent` priority rule — and no terminal. The last inch was
# uncovered: JLine's terminal detection, raw mode, the alternate screen, `TerminalOutputGuard`'s
# redirect, and whether a frame lands on a screen at all.
#
# An earlier attempt drove the exec jar under a Python `pty.fork()` pipe pair and reported the
# app emitting its startup mode query and then NOTHING — no frames, an empty log — with no way to
# tell whether the app or the rig was at fault. It was the rig, and reproducing it here named the
# single reason:
#
#     `pty.fork()` leaves the pty's window size at 0 rows x 0 columns, and nobody sets it.
#     JLine reports 0x0, the renderer has zero area, and the app correctly draws nothing.
#     One `TIOCSWINSZ` later, on the same bare pipe pair with still nobody answering a DECRQM,
#     the same jar emitted `kweblens-tui [R] ... 7 rows` and a full table. Measured 2026-08-17:
#     0 bytes of frame at 0x0, 1407 bytes at 44x132, one variable changed.
#
# So the rig is `tmux`, not a hand-rolled pty. tmux is a real terminal emulator: it owns a pty
# WITH a size, honours a mode set, tracks the alternate screen, carries a query's reply back to
# the program, and `capture-pane -p` hands the rendered cells back as text. `--self-check`
# MEASURES each of those rather than assuming it — and names the one debt tmux 3.5a does not pay
# either: it does not answer `ESC[?2027$p`. Nothing does, here or under a bare pty, and the app
# draws its table regardless, which is what retires the earlier attempt's DECRQM hypothesis.
#
# NOT A BUILD GATE. This is on-demand, like `perf-sweep.mjs`. A pty rig that is flaky in CI is
# worse than no rig (#423). Do not add it to `dev-verify.sh`.
#
# THE FRAME IS CLUSTER DATA. It carries namespaces, object names, node names and the context
# name. Captures land in `.tui/`, which is gitignored and must stay that way — same reason as
# `.playwright/`. Quote a capture in a PR by hand, after reading what is in it.
#
# Usage:
#   scripts/tui-drive.sh --self-check                        # positive control; no jar, no cluster
#   scripts/tui-drive.sh --context k3stest
#   scripts/tui-drive.sh --context k3stest --keys ':,svc,Enter' --hold 4
#   scripts/tui-drive.sh --context k3stest --keys '?' --until 'every binding'
#   scripts/tui-drive.sh --context k3stest --size 80x20 --quit    # resize class, then ctrl-c
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/.tui"
JAR="$ROOT/kweblens-tui/target/kweblens-tui-exec.jar"

CONTEXT=""
KIND=""
KEYS=""
SIZE="132x44"
WAIT=90
HOLD=3
UNTIL='kweblens-tui \[R'
SELF_CHECK=0
KEEP=0
QUIT=0
EXTRA=()

# A socket per checkout: several agents share this box, and a shared tmux server would let one
# run's session names and window sizes reach another's. The jar is resolved from this script's
# own location, so "am I measuring this checkout" is answered by construction rather than by a
# port's owner (the trap `cluster-switch-check.mjs` guards against).
SOCKET="kwtui-$(printf '%s' "$ROOT" | cksum | cut -d' ' -f1)"
SESSION="drive"

die() { printf '%s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

while [[ $# -gt 0 ]]; do
	case "$1" in
		--self-check) SELF_CHECK=1; shift ;;
		--context) CONTEXT="${2:?}"; shift 2 ;;
		--kind) KIND="${2:?}"; shift 2 ;;
		--keys) KEYS="${2:?}"; shift 2 ;;
		--size) SIZE="${2:?}"; shift 2 ;;
		--wait) WAIT="${2:?}"; shift 2 ;;
		--hold) HOLD="${2:?}"; shift 2 ;;
		--until) UNTIL="${2:?}"; shift 2 ;;
		--jar) JAR="${2:?}"; shift 2 ;;
		--keep) KEEP=1; shift ;;
		--quit) QUIT=1; shift ;;
		--) shift; EXTRA=("$@"); break ;;
		-h|--help) sed -n '2,45p' "${BASH_SOURCE[0]}"; exit 0 ;;
		*) die "unknown argument: $1 (try --help)" ;;
	esac
done

command -v tmux >/dev/null 2>&1 || die \
	"tmux is not installed, and it is what makes this a real terminal rather than a pipe pair.
  Fedora: sudo dnf install tmux    Debian/Ubuntu: sudo apt install tmux
  Nothing else is needed — no Python package, no npm install."
TMUX_VER="$(tmux -V | tr -dc '0-9.')"
awk -v v="$TMUX_VER" 'BEGIN { exit (v+0 >= 3.2) ? 0 : 1 }' \
	|| die "tmux $TMUX_VER is too old: this rig needs 3.2's \`new-session -e\` to set TERM per session."

COLS="${SIZE%%x*}"
ROWS="${SIZE##*x}"
[[ "$COLS" =~ ^[0-9]+$ && "$ROWS" =~ ^[0-9]+$ ]] || die "--size wants COLSxROWS, e.g. 132x44"

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"

tmux_() { tmux -L "$SOCKET" "$@"; }

kill_session() { tmux_ kill-session -t "$SESSION" 2>/dev/null; }

# Start `cmd` in a fresh session of the requested size. `remain-on-exit` keeps the pane readable
# after the process dies, which is the difference between "the app exited" and "the app exited
# with code 4 after printing why".
start_pane() {
	local cmd="$1"
	kill_session
	tmux_ new-session -d -s "$SESSION" -x "$COLS" -y "$ROWS" \
		-e TERM=xterm-256color \
		-e KWEBLENS_TUI_LOG_FILE="$OUT_DIR/app-$STAMP.log" \
		"$cmd" || die "tmux could not start a session"
	tmux_ set-option -t "$SESSION" remain-on-exit on >/dev/null
}

frame() { tmux_ capture-pane -p -t "$SESSION" 2>/dev/null; }

pane_dead() { tmux_ display-message -p -t "$SESSION" '#{pane_dead}' 2>/dev/null; }
pane_status() { tmux_ display-message -p -t "$SESSION" '#{pane_dead_status}' 2>/dev/null; }

# Every java under the pane, deepest first: tmux may wrap the command in a shell.
java_pids() {
	local pane
	pane="$(tmux_ display-message -p -t "$SESSION" '#{pane_pid}' 2>/dev/null)"
	[[ -n "$pane" ]] || return 0
	{
		printf '%s\n' "$pane"
		pgrep -P "$pane" 2>/dev/null
	} | while read -r pid; do
		[[ -r "/proc/$pid/comm" ]] || continue
		[[ "$(cat "/proc/$pid/comm")" == java ]] && printf '%s\n' "$pid"
	done
}

# Send one step. A bare word goes literally; a key NAME must not, or `Enter` types five letters.
send_step() {
	local step="$1"
	if [[ "$step" =~ ^(Enter|Escape|Tab|Space|BSpace|BTab|Up|Down|Left|Right|PageUp|PgUp|PageDown|PgDn|Home|End|Insert|Delete|F[0-9]+|C-.|M-.)$ ]]; then
		tmux_ send-keys -t "$SESSION" "$step"
	else
		tmux_ send-keys -t "$SESSION" -l "$step"
	fi
}

# ---------------------------------------------------------------------------------------------
# --self-check: the positive control. Five things a terminal owes, each driven through the very
# same rig with an answer known in advance. A rig that reports "no frame" is indistinguishable
# from a rig that cannot read frames, so none of these may be assumed.
# ---------------------------------------------------------------------------------------------
self_check() {
	local fails=0 checks=6 got want
	note "positive control — same socket, same session size, no jar and no cluster"
	note ""

	# 1. RENDER: cursor-addressed writes come back from the cells they were written to.
	start_pane "printf '\\033[2J\\033[3;5HALPHA\\033[5;5HBETA'; sleep 60"
	sleep 1
	got="$(frame | sed -n '3p')"
	want="    ALPHA"
	if [[ "$got" == "$want" ]]; then note "  ok   render      row 3 reads '$got' at the column it was addressed to"
	else note "  FAIL render      row 3 is '$got', wanted '$want'"; fails=$((fails + 1)); fi
	got="$(frame | sed -n '5p')"
	if [[ "$got" == "    BETA" ]]; then note "  ok   render      row 5 reads '$got'"
	else note "  FAIL render      row 5 is '$got', wanted '    BETA'"; fails=$((fails + 1)); fi

	# 2. ALTERNATE SCREEN: the app draws on it (`ESC[?1049h`), so a capture that only ever read
	#    the primary screen would report a blank frame for a perfectly drawn one.
	start_pane "printf '\\033[?1049h\\033[2J\\033[2;2HONALT'; sleep 60"
	sleep 1
	got="$(frame | sed -n '2p')"
	if [[ "$got" == " ONALT" ]]; then note "  ok   alt-screen  capture-pane reads the ALTERNATE screen ('$got')"
	else note "  FAIL alt-screen  row 2 of the alternate screen is '$got', wanted ' ONALT'"; fails=$((fails + 1)); fi

	# 3. SIZE: the debt the bare pty did not pay. 0x0 is a terminal with nowhere to draw.
	start_pane "stty size; sleep 60"
	sleep 1
	got="$(frame | sed -n '1p')"
	if [[ "$got" == "$ROWS $COLS" ]]; then note "  ok   size        the pane reports '$got' rows/cols, which is what was asked for"
	else note "  FAIL size        the pane reports '$got', wanted '$ROWS $COLS'"; fails=$((fails + 1)); fi

	# 4. QUERY/REPLY: a bare pty answers nothing a program asks it. Asserted with DA1
	#    (`ESC[c`), which every emulator answers, because the assertion has to be about the
	#    CHANNEL: if a reply cannot come back at all, the DECRQM line below measures nothing.
	start_pane "bash -c 'stty raw -echo; printf \"\\033[c\"; IFS= read -r -t 2 -d c reply; stty sane; printf \"DA1:%s|\\n\" \"\$(printf %s \"\$reply\" | cat -v)\"; sleep 60'"
	sleep 2
	got="$(frame | tr -d '\r' | grep -o 'DA1:[^|]*' | head -1)"
	if [[ -n "$got" && "$got" != "DA1:" ]]; then note "  ok   query/reply an ESC[c came back as '$got' — this terminal answers a program"
	else note "  FAIL query/reply nothing came back from ESC[c, so no reply can reach a program here"; fails=$((fails + 1)); fi

	# 5. KEYS: a keystroke sent by the rig arrives at the program's stdin.
	start_pane "bash -c 'read -r line; printf \"GOT:%s\\n\" \"\$line\"; sleep 60'"
	sleep 1
	send_step 'hello'
	send_step 'Enter'
	sleep 1
	got="$(frame | grep -o 'GOT:hello' | head -1)"
	if [[ "$got" == "GOT:hello" ]]; then note "  ok   keys        'hello' + Enter reached the program's stdin"
	else note "  FAIL keys        the program never read 'hello' (frame: '$(frame | sed -n '1,2p' | tr '\n' '/')')"; fails=$((fails + 1)); fi

	# 6. And one thing this rig does NOT do, measured rather than assumed. The app's very first
	#    bytes are `ESC[?2027$p` (DECRQM, grapheme clustering), and the earlier #426 attempt
	#    guessed the missing reply was why nothing was drawn. tmux 3.5a does not answer it
	#    either — and the app draws its table anyway, so the reply is not what was missing.
	#    Reported, never asserted: a rig must know which debts it leaves unpaid.
	start_pane "bash -c 'stty raw -echo; printf \"\\033[?2027\\\$p\"; IFS= read -r -t 2 -d y reply; stty sane; printf \"DECRQM:%s|\\n\" \"\$(printf %s \"\$reply\" | cat -v)\"; sleep 60'"
	sleep 3
	got="$(frame | tr -d '\r' | grep -o 'DECRQM:[^|]*' | head -1)"
	if [[ -n "$got" && "$got" != "DECRQM:" ]]; then
		note "  note DECRQM      ESC[?2027\$p answered: '$got'"
	else
		note "  note DECRQM      ESC[?2027\$p is NOT answered here (nor by a bare pty). The app sends"
		note "                   it first and renders regardless — silence after it is not a symptom."
	fi

	kill_session
	note ""
	if [[ $fails -eq 0 ]]; then
		note "positive control: $checks/$checks assertions passed. This rig can see a frame, reads the"
		note "alternate screen, reports a size, carries a reply back to a program, and delivers keys."
		return 0
	fi
	note "positive control: $fails assertion(s) FAILED. Fix the rig before believing anything it"
	note "says about the app — a silent rig and a silent app look identical."
	return 1
}

if [[ $SELF_CHECK -eq 1 ]]; then
	self_check
	exit $?
fi

# ---------------------------------------------------------------------------------------------
# The app
# ---------------------------------------------------------------------------------------------
[[ -f "$JAR" ]] || die "no exec jar at $JAR
  Build it:  ./mvnw -pl kweblens-tui -am package -DskipTests
  The SHIPPED jar is the point — a rig that drives TuiScreen in-process is FakeBackend again."

newest_src="$(find "$ROOT/kweblens-tui/src/main" "$ROOT/kweblens-core/src/main" -type f -newer "$JAR" -print -quit 2>/dev/null)"
if [[ -n "$newest_src" ]]; then
	note "WARNING: $newest_src is newer than the jar — you may be measuring code you did not write."
	note "         rebuild: ./mvnw -pl kweblens-tui -am package -DskipTests"
fi

cmd=(java -jar "$JAR")
[[ -n "$CONTEXT" ]] && cmd+=(--context "$CONTEXT")
[[ -n "$KIND" ]] && cmd+=(--kind "$KIND")
[[ ${#EXTRA[@]} -gt 0 ]] && cmd+=("${EXTRA[@]}")

note "rig      tmux $TMUX_VER, socket $SOCKET, pane ${COLS}x${ROWS}, TERM=xterm-256color"
note "jar      $JAR"
note "command  ${cmd[*]}"
note "log      $OUT_DIR/app-$STAMP.log  (root level is WARN, so EMPTY IS NORMAL on a healthy run)"
note ""

start_pane "${cmd[*]}"

trap '[[ $KEEP -eq 1 ]] || kill_session' EXIT

waited=0
matched=0
while [[ $waited -lt $WAIT ]]; do
	if frame | grep -qE "$UNTIL"; then matched=1; break; fi
	if [[ "$(pane_dead)" == "1" ]]; then break; fi
	sleep 1
	waited=$((waited + 1))
done

if [[ $matched -eq 1 ]]; then
	note "first frame after ${waited}s (matched /$UNTIL/)"
else
	note "NO FRAME after ${waited}s (nothing matched /$UNTIL/) — this is a FAILED measurement."
	note ""
	if [[ "$(pane_dead)" == "1" ]]; then
		note "The process EXITED, status $(pane_status). Its last screen:"
	else
		note "The process is still running. Where it is parked, from the outside:"
		for pid in $(java_pids); do
			dump="$OUT_DIR/threads-$STAMP-$pid.txt"
			if jcmd "$pid" Thread.print > "$dump" 2>&1; then
				note "  thread dump: $dump"
				note "  --- the threads that matter ---"
				grep -E '^"(main|TamboUI|tui-|kweblens|OkHttp|watch)' -A 4 "$dump" | head -60
			else
				note "  jcmd could not attach to $pid (see $dump)"
			fi
		done
	fi
fi

if [[ -n "$KEYS" && $matched -eq 1 ]]; then
	note ""
	note "keys     $KEYS"
	IFS=',' read -r -a steps <<< "$KEYS"
	for step in "${steps[@]}"; do
		send_step "$step"
		sleep 0.4
	done
	sleep "$HOLD"
fi

# Capture BEFORE any quit. A first version quit first and captured after, which read the pane
# the app had just handed back — it leaves the alternate screen on exit, so the "frame" was 44
# blank rows and a tmux epitaph. That is the app being correct and the rig reporting nothing.
FRAME_FILE="$OUT_DIR/frame-$STAMP.txt"
frame > "$FRAME_FILE"
tmux_ capture-pane -p -e -t "$SESSION" > "$OUT_DIR/frame-$STAMP.esc" 2>/dev/null

if [[ $QUIT -eq 1 && $matched -eq 1 ]]; then
	tmux_ send-keys -t "$SESSION" C-c
	# Poll for the exit rather than sleeping at it. A fixed 3s read `pane_dead_status` as empty
	# on one run and `0` on the next — the same app, the same key, a different moment.
	for _ in $(seq 1 20); do
		[[ "$(pane_dead)" == "1" ]] && break
		sleep 0.5
	done
	status="$(pane_status)"
	QUIT_NOTE="ctrl-c: pane_dead=$(pane_dead) exit status=${status:-<not reported by tmux>}; the pane"
	QUIT_NOTE="$QUIT_NOTE is back on the primary screen, i.e. the app left the alternate screen behind it."
fi

note ""
note "----- frame (${COLS}x${ROWS}) -----"
cat "$FRAME_FILE"
note "----- end frame -----"
note ""
note "saved    $FRAME_FILE (plain) and .esc (with attributes) — .tui/ is gitignored, and the"
note "         frame carries this cluster's context, namespaces and object names. Read before quoting."
log="$OUT_DIR/app-$STAMP.log"
note "log      $(wc -c < "$log" 2>/dev/null || echo 0) bytes at $log"
[[ -n "${QUIT_NOTE:-}" ]] && note "quit     $QUIT_NOTE"

[[ $matched -eq 1 ]] || exit 1
exit 0
