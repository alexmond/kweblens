#!/usr/bin/env bash
#
# WHICH CODE allocates a list request's heap? (the companion to heap-probe.sh, which says
# HOW MUCH)
#
# Exists because #292 ended on a question its instrument could not answer, and #293 proposed
# the same instrument again: is the spike the output `String` (cheap — stream it) or the
# deserialised model graph (expensive — stop materialising it)? `jcmd GC.class_histogram`
# CANNOT tell you. On a live 151-Secret list it reports:
#
#     24.8 MB  66%  [B          9.5 MB  25%  [C          0.03 MB  0.05%  GenericKubernetesResource
#
# ...which looks like an answer and is not one: since JDK 9 compact strings **every String is a
# byte[]**, so `[B` is the output String AND Jackson's buffers AND the response body AND every
# field value in the model graph, added together. A histogram has no call site.
#
# JFR allocation sampling does. Each jdk.ObjectAllocationSample carries a stack and a thread, so
# the same run separates them — and the answer was 1.4% output String, 94% response-and-parse.
#
# Two traps, both of which produced a wrong answer here first:
#
#   * `jfr print` truncates stacks to FIVE frames by default. Five frames from an allocation
#     lands inside netty or Jackson and attributes the heap to "an event loop". Always
#     --stack-depth 48. (This is what made a single 90 MB String sample look like the response
#     being turned into a String on the event loop; it was the parser, six frames down.)
#   * settings=profile throttles ObjectAllocationSample to 150/s, which over a 1-second request
#     is a handful of samples carrying enormous extrapolated weights. throttle=3000/s makes the
#     percentages stable run to run.
#
# The thread breakdown is the cross-check worth reading first, because it needs no bucketing
# rules to believe: `vert.x-eventloop-*` is the fabric8 client (read, decrypt, parse) and
# `tomcat-handler-*` is the entire controller — ListProjection and Serialization.asJson both.
#
# Usage:
#   scripts/alloc-probe.sh                            # :8080, first cluster, pods
#   PORT=8142 CLUSTER=default scripts/alloc-probe.sh secrets pods
#   REPS=20 scripts/alloc-probe.sh secrets
#
# Env: PORT (8080) | CLUSTER (first registered) | REPS (10) | OUT (a temp dir)
# Needs `jcmd`/`jfr` (any JDK) and the app running locally. Run it against a LIVE cluster: the
# simulator's API server is in the same JVM, so its serialisation lands in the same profile and
# is indistinguishable from the client's.

set -uo pipefail

PORT="${PORT:-8080}"
REPS="${REPS:-10}"
OUT="${OUT:-${TMPDIR:-/tmp}/kweblens-alloc}"
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

mkdir -p "${OUT}"

for kind in "${KINDS[@]}"; do
	url="${BASE_URL}/api/v1/clusters/${CLUSTER}/resources/${kind}/objects"
	jfr_file="${OUT}/${kind}.jfr"
	rm -f "${jfr_file}"
	jcmd "${PID}" JFR.start name=alloc settings=profile \
		jdk.ObjectAllocationSample#throttle=3000/s >/dev/null || exit 2
	for _ in $(seq 1 "${REPS}"); do curl -sf -o /dev/null "${url}"; done
	jcmd "${PID}" JFR.dump name=alloc filename="${jfr_file}" >/dev/null
	jcmd "${PID}" JFR.stop name=alloc >/dev/null

	printf '\n=== %s — %s requests, :%s cluster=%s\n\n' "${kind}" "${REPS}" "${PORT}" "${CLUSTER}"
	jfr print --stack-depth 48 --events ObjectAllocationSample "${jfr_file}" \
		| python3 -c '
import re, sys
from collections import defaultdict

reps = float(sys.argv[1])
raw = sys.stdin.read()

# Buckets are ordered: the first pattern that matches anywhere in the stack wins, so the
# specific stages (projection, serialise, deserialise) are tested before the generic
# network one, which would otherwise swallow everything running on an event loop.
STAGES = [
    ("projection   ", re.compile(r"ListProjection")),
    ("out-json     ", re.compile(r"writeValueAsString|SegmentedStringWriter|ObjectApiController"
                                 r"|KubernetesSerialization\.asJson|UTF8JsonGenerator"
                                 r"|_writeValueAndClose|ObjectWriter|SerializerProvider"
                                 r"|BeanSerializer|MapSerializer")),
    ("model-graph  ", re.compile(r"contentsAsString|setCurrentAndReturn|_finishAndReturnString"
                                 r"|Deserializer|_readMapAndClose|ObjectMapper\.readValue"
                                 r"|KubernetesSerialization\.unmarshal|UntypedObjectDeserializer"
                                 r"|TextBuffer|JsonParser|JsonFactory")),
    ("response-bytes", re.compile(r"netty|vertx|BufferUtil|BodyHandler|SSLCipher"
                                  r"|GaloisCounter|sun\.security\.ssl|HttpClient|jctools")),
]
UNIT = {"bytes": 1, "B": 1, "kB": 1e3, "KB": 1e3, "MB": 1e6, "GB": 1e9}

stage, thread, site = defaultdict(float), defaultdict(float), defaultdict(float)
for ev in raw.split("jdk.ObjectAllocationSample {")[1:]:
    w = re.search(r"weight = ([\d.]+) (\w+)", ev)
    if not w:
        continue
    weight = float(w.group(1)) * UNIT[w.group(2)]
    th = re.search(r"eventThread = \"([^\"]+)\"", ev)
    frames = re.findall(r"^\s+(\S+\.\S+)\(", ev, re.M)
    stack = "\n".join(frames)
    stage[next((n for n, p in STAGES if p.search(stack)), "other        ")] += weight
    thread[re.sub(r"[-#]\d+$", "-N", th.group(1) if th else "?")] += weight
    if frames:
        site[" <- ".join(f.split("(")[0] for f in frames[:2])] += weight

total = sum(stage.values()) or 1
print("%-15s %11s %8s" % ("stage", "MB/request", "share"))
for name, _ in STAGES + [("other        ", None)]:
    print("%-15s %11.2f %7.1f%%" % (name, stage[name] / 1e6 / reps, 100 * stage[name] / total))
print("%-15s %11.2f" % ("TOTAL", total / 1e6 / reps))

print("\n%-34s %11s   %s" % ("thread", "MB/request", "(the cross-check: tomcat-handler IS the controller)"))
for t, w in sorted(thread.items(), key=lambda x: -x[1])[:5]:
    print("%-34s %11.2f" % (t, w / 1e6 / reps))

print("\n%11s   %s" % ("MB/request", "top allocation sites"))
for s, w in sorted(site.items(), key=lambda x: -x[1])[:8]:
    print("%11.2f   %s" % (w / 1e6 / reps, s))
' "${REPS}"
done

printf '\nJFR measures ALLOCATION over the window, not peak occupancy: it says which code\n'
printf 'allocated. For how much is resident at once, use scripts/heap-probe.sh.\n'
printf 'Sample weights are extrapolations — read the shares as ratios, not readings.\n\n'
