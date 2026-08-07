#!/usr/bin/env bash
#
# What does ONE list request cost the JVM heap?
#
# Exists because this was the last unmeasured axis of the scale question, and it turned out to
# be the binding one. `docs/design/scale-measurements.md` records that the 2026-08-01 pass
# collected heap readings and threw them away as GC-timing noise (683 MB at 200 objects, 149 MB
# at 3 000 — the smaller cluster "using" more). That was the right call about those readings and
# the wrong conclusion to leave standing: heap is measurable, the earlier numbers were just
# samples taken at arbitrary points in a GC cycle.
#
# The method that works is to bracket the request with FORCED collections and sample in
# between, all from OUTSIDE the JVM so the measurement does not allocate inside the thing it is
# measuring:
#
#   base       heap used after `jcmd GC.run`, before the request
#   peak       max heap used during the request (`jstat` every 50 ms)
#   retained   heap used after `jcmd GC.run`, after the request
#   transient  peak - base : what the request needed ON TOP of steady state.
#   ygc        young collections inside the window. If 0, transient is exact. If >0, peak was
#              reset mid-flight and transient is a LOWER BOUND — the run says so rather than
#              quietly under-reporting.
#
# WHAT `transient` IS NOT (#293, 2026-08-07): it is not the live set, and it must not be used
# to decide whether a change BOUNDS the heap. It measures how much eden the request dirtied
# before a lazy collector got round to it — allocation CHURN, not retention. Chunking the
# list fetch scored WORSE on it (80-96 MB against 67-69 MB unchunked, 2 150 Secrets) while
# being strictly better at the thing that actually matters, because five bounded pages
# allocate more total garbage than one big graph even though far less of it is live at once.
# Reading that table alone would have got #293 reverted.
#
# For "does this bound the heap", ask the question an OOM-kill asks: THE SMALLEST -Xmx IN
# WHICH THE REQUEST STILL COMPLETES. That is immune to collector timing — a squeezed heap
# collects whatever it can, so only genuinely-live bytes can push it over. Measured on
# 8 150 live Secrets: unchunked OOMs at 224m and needs 256m; chunked completes at 224m and
# below, with identical wall-clock (~2.6 s). Use both instruments; they answer different
# questions, and this one answers the cheaper-but-wrong one.
#
# Do not use `jvm.gc.memory.allocated` for this on its own: it is incremented at GC boundaries,
# so across a single fast request it reads as a multiple of the eden increment (17 MB, then
# 1 MB, for identical work) and looks like noise. It is reported here only as a cross-check.
#
# What it found, so you know what a normal answer looks like (live cluster, real objects):
# ~94 KB of transient heap per pod, ~247 KB per Secret, ~84 KB per ConfigMap — 5-12x each
# object's own JSON, and 500x the projected bytes those Secrets put on the wire. The list path
# deserialises the whole collection before ListProjection strips it, so #279's payload win did
# not reach the heap. Against the chart's `limits.memory: 1Gi` this is an OOM-kill, not a
# slowdown. See scale-measurements.md, "Is server-side paging still worth building?".
#
# NOTE ON RIGS: run this against a LIVE cluster when the number will be quoted. The simulator's
# API server is in the same JVM, so its serialisation is inside every reading — measured at
# roughly half the simulator's spike. The simulator is fine for comparing sizes to each other,
# not for stating an absolute cost.
#
# Usage:
#   scripts/heap-probe.sh                          # :8080, cluster auto-detected, pods
#   PORT=8085 scripts/heap-probe.sh pods secrets configmaps
#   CLUSTER=sim REPS=5 scripts/heap-probe.sh secrets
#
# Env: PORT (8080) | CLUSTER (first registered) | REPS (3)
# Needs `jstat`/`jcmd` (any JDK) and the app running locally — it attaches to the PID holding
# the port, so it cannot probe a remote instance.

set -uo pipefail

PORT="${PORT:-8080}"
REPS="${REPS:-3}"
KINDS=("$@")
if [[ ${#KINDS[@]} -eq 0 ]]; then KINDS=(pods); fi

PID=$(lsof -ti "tcp:${PORT}" 2>/dev/null | head -1)
if [[ -z "${PID}" ]]; then
	echo "nothing listening on :${PORT} — start the app first (scripts/dev-run.sh)" >&2
	exit 2
fi

BASE_URL="http://localhost:${PORT}"
CLUSTER="${CLUSTER:-}"
if [[ -z "${CLUSTER}" ]]; then
	CLUSTER=$(curl -sf "${BASE_URL}/api/v1/clusters" | python3 -c \
		'import sys,json;l=json.load(sys.stdin);print(l[0]["id"] if l else "")' 2>/dev/null)
fi
if [[ -z "${CLUSTER}" ]]; then
	echo "no cluster registered on :${PORT}" >&2
	exit 2
fi

# jstat -gc columns: 3=S0U 4=S1U 6=EU 8=OU 13=YGC. Heap used is the four survivor/eden/old
# columns ONLY — an earlier version added MU and CCSU, which are metaspace and compressed class
# space, i.e. NOT heap, and inflated every baseline by ~124 MB.
heap_kb() { jstat -gc "${PID}" | awk 'NR==2 {printf "%d", $3+$4+$6+$8}'; }
ygc_count() { jstat -gc "${PID}" | awk 'NR==2 {print $13}'; }
forced_gc() { jcmd "${PID}" GC.run >/dev/null 2>&1; sleep 1; }

TMP=$(mktemp)
trap 'rm -f "${TMP}"' EXIT

printf '\nheap per list request — :%s cluster=%s pid=%s reps=%s\n\n' \
	"${PORT}" "${CLUSTER}" "${PID}" "${REPS}"
printf '%-14s %8s %9s %9s %11s %10s %6s %8s\n' \
	kind objs base peak transient retained ygc wire
printf -- '------------------------------------------------------------------------------------\n'

for kind in "${KINDS[@]}"; do
	url="${BASE_URL}/api/v1/clusters/${CLUSTER}/resources/${kind}/objects"
	objs=$(curl -sf "${url}" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)
	for rep in $(seq 1 "${REPS}"); do
		forced_gc
		base=$(heap_kb)
		y0=$(ygc_count)
		jstat -gc "${PID}" 50 600 > "${TMP}" 2>/dev/null &
		sampler=$!
		bytes=$(curl -sf -o /dev/null -w '%{size_download}' "${url}")
		sleep 0.5
		kill "${sampler}" 2>/dev/null
		wait "${sampler}" 2>/dev/null
		peak=$(awk 'NR>1 {u=$3+$4+$6+$8; if (u>m) m=u} END {printf "%d", m}' "${TMP}")
		y1=$(ygc_count)
		forced_gc
		retained=$(heap_kb)
		ygc=$((y1 - y0))
		# A young GC inside the window resets the peak, so transient becomes a lower bound.
		mark=$([[ ${ygc} -gt 0 ]] && echo '>=' || echo '  ')
		printf '%-14s %8s %8sM %8sM %s%8sM %9sM %6s %8s\n' \
			"${kind}" "${objs}" "$((base / 1024))" "$((peak / 1024))" "${mark}" \
			"$(((peak - base) / 1024))" "$((retained / 1024))" "${ygc}" "${bytes}"
	done
done

printf '\ntransient = peak - base, the heap this request needed on top of steady state.\n'
printf '">=" means a young GC ran inside the window, so that row is a lower bound.\n'
printf 'retained should return to base; if it climbs across reps, that is a leak, not a spike.\n\n'
