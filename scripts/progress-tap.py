#!/usr/bin/env python3
"""Pipe filter: forward stdin unchanged, and report its progress to the live channel.

Ported from venice-vr's `scripts/progress-tap.py`, which is where the design and most of
the hard-won detail below come from. What changed for this repo is named at the bottom.

WHY A TAP AND NOT `progress run`. That wrapper times an opaque command and shows one
featureless bar. The only question anyone asks about an 8-minute reactor build is *which
module is it on, and how far through its tests* — and Maven already prints the answer:

    [INFO] Building kweblens-core 0.1.0-SNAPSHOT                            [2/7]
    [INFO] Running org.alexmond.kweblens.repo.TrackedSourcesStayGreppableTest

It was already going into the log; it just went somewhere nobody watches.

THE DEFAULT MODE, `reactor`: ONE bar for the build plus ONE native SUB-JOB per module
running tests — `--parent <reactor token>`, PLAIN name; the channel draws the indent:

    mvn: verify                    3/7 · building: kweblens-core
      ↳ core                     118/… · TrackedSourcesStayGreppableTest

  * The top bar counts modules FINISHED out of the reactor. Maven 3.9 prints a line when a
    module STARTS and nothing when it ends, so "finished" is derived, from four signals, in
    order of trust: the Reactor Summary (exact, at the end); single-threaded builds (the
    next module starting means the last one ended — exact); the reactor DEPENDENCY graph,
    read from the POMs (Maven starts a module only after every reactor module it depends
    on has finished — exact, and late for a leaf); and the module's TERMINAL mojo, learned
    in-run from a module the first two signals proved finished. A module no signal reaches
    stays "building" until the summary: the count is a lower bound, never an overcount.
  * A child bar per module. Its total comes from a `[progress] <module> done/total · <name>`
    line when something emits one — `kweblens-ui`'s vitest reporter does — and otherwise
    from COUNTING surefire's own `Running <class>` lines, which gives movement and the
    current test class but no denominator. The count is attributed to the right module by
    looking for the class under each module's `src/test/java`, so parallel modules do not
    pool into one bar.

WHY IT MUST BE TRANSPARENT. It sits in the gate's pipeline, and the gate's whole job is
reporting a trustworthy verdict. So stdin is copied to stdout BYTE FOR BYTE (binary, never
decoded on the way through), nothing is ever written to stdout that did not arrive on
stdin, and every progress call is best-effort: if the daemon is down, the plugin moved, or
a call hangs, the build still runs and still reports. A progress bar is never worth a
broken gate — and a WARNING on every build would be its own defect, so the degradation is
silent.

WHY A WORKER THREAD. Every call into `progress` forks a process (~50 ms). A reactor with
thousands of tests would fork it thousands of times, and making those calls INLINE stalls
Maven's own output behind a slow one. The reader only updates a model; one worker posts it,
at most once per --interval per bar (default 0.5 s), and ALWAYS posts the final state.
Every bar is finished — never left "running" — on EOF, a closed pipe, Ctrl-C or SIGTERM.

THE CALLER STILL OWNS THE EXIT CODE. A pipeline's `$?` is the LAST command's — this tap's,
which is always 0. Callers must read `${PIPESTATUS[0]}`; scripts/dev-verify.sh does, and
getting that wrong would make every failed gate report success, which is this repo's
most-repeated instrument defect in its worst possible place. A failed build shows its top
bar as failed (it saw BUILD FAILURE); that is display, not a verdict.

    scripts/dev-verify.sh                           # the tap is inside dev-verify.sh
    ./mvnw -B verify 2>&1 | scripts/progress-tap.py "build" > build.log
    scripts/progress-tap.py --self-test             # the controls; reads no build

Patterns:
    reactor (default)  top bar + one bar per module testing now (above)
    maven              Maven's reactor line only — absolute position, so the count is SET
    surefire           ONE bar for one module's tests: the listener line, else "Running"
    batch              Spring Batch step lines — counts steps, total unknown
    count:<regex>      count every line matching <regex>; --total declares the denominator

Options: --pattern <name> · --total <n> · --timeout <secs> · --interval <secs>
         --grace <secs> · --reactor-root <dir> · --quiet (pure cat) · --self-test
PROGRESS_TAP_TRACE=<file> records the calls as JSON lines instead of making them, posting
synchronously after every line — the control's view, and what --self-test asserts on.

`-q` DEFEATS THIS, and silently. Every line the tap reads is logged at INFO, which `-q`
suppresses. The build still runs, the bar just never moves off zero. Use `-B`.

WHAT CHANGED FROM THE VANTAGE ORIGINAL. The artifactId prefix the bar strips. And there is
no JUnit `TestProgressListener` here: vantage service-loads one from a shared test-jar that
every module carries, and this reactor has no test-jar and no module every other module
depends on (`kweblens-cli` deliberately depends on fabric8 alone). So Java modules get the
`Running`-counting fallback, which is honest about having no denominator, and the one
module that CAN name its own total does — `kweblens-ui` via a vitest reporter, because that
is self-contained in the module that owns it.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def _resolve_progress():
    """The argv prefix that invokes the channel, or None if it is not installed.

    PATH FIRST: `progress` on PATH is the stable, version-free interface every other
    session uses. A hardcoded plugin-cache path went four releases stale once, and the
    degradation is silent by design, so the tap became a plain `cat` and nobody noticed.
    The plugin cache stays as a fallback, resolved NEWEST-FIRST rather than pinned.
    """
    exe = shutil.which("progress")
    if exe:
        return [exe]
    cands = []
    env = os.environ.get("PROGRESS_PLUGIN")
    if env:
        cands.append(env)
    cache = os.path.expanduser("~/.claude/plugins/cache/alexmskills/progress-channel")
    if os.path.isdir(cache):

        def ver(d):
            return [int(p) if p.isdigit() else -1 for p in os.path.basename(d).split(".")]

        cands += sorted((os.path.join(cache, d) for d in os.listdir(cache)), key=ver, reverse=True)
    for c in cands:
        p = os.path.join(c, "scripts", "progress.py")
        if os.path.isfile(p):
            return [sys.executable, p]
    return None


# The listener's line, with or without the module the listener now names. The module
# never starts with a digit, so an old-format line cannot be read as having one.
LISTENER = (r"\[progress\]\s+(?:(?P<mod>[A-Za-z][\w.-]*)\s+)?"
            r"(?P<done>\d+)/(?P<total>\d+)\s+·\s+(?P<name>[^\r\n]*)")
RUNNING = r"Running\s+(?P<cls>[\w.$]+(?:Test|IT)\w*)\s*$"

PATTERNS = {
    # Absolute position: "Building <name> ... [3/15]". Captured as (done, total, name).
    "maven": r"Building\s+(?P<name>.*?)\s+\S+\s+\[(?P<done>\d+)/(?P<total>\d+)\]",
    # Spring Batch prints one of these per step; there is no denominator, so it counts.
    "batch": r"(?:Executing step|Step:\s*\[)\s*(?P<name>[^\]\s][^\]]*)",
    # ONE MODULE'S TESTS, best signal first: the listener's done/total (a real bar), else
    # surefire's own "Running <class>" (a count). Distinct group names because Python
    # refuses a duplicate one across alternatives.
    "surefire": "(?:" + LISTENER + ")|(?:" + RUNNING + ")",
}

ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
RE_HEADER = re.compile(r"^\[INFO\] -+< (?P<g>[\w.-]+):(?P<a>[\w.-]+) >-+\s*$")
RE_BUILDING = re.compile(PATTERNS["maven"])
RE_THREADS = re.compile(r"MultiThreadedBuilder implementation with a thread count of (?P<n>\d+)")
RE_MOJO = re.compile(r"^\[INFO\] --- (?P<plugin>[\w.-]+):[^:\s]+:(?P<goal>[\w.-]+)"
                     r"(?: \((?P<exec>[^)]*)\))? @ (?P<a>[\w.-]+) ---")
RE_LISTENER = re.compile(LISTENER)
RE_RUNNING = re.compile(RUNNING)
RE_SUMMARY = re.compile(r"^\[INFO\] Reactor Summary|^\[INFO\] BUILD (?:SUCCESS|FAILURE)")
RE_FAILURE = re.compile(r"^\[INFO\] BUILD FAILURE")
TEST_PLUGINS = {"surefire", "failsafe", "maven-surefire-plugin", "maven-failsafe-plugin"}
SHORT_PREFIX = "kweblens-"  # every artifactId here carries it; the bar is narrow


def short(aid):
    return aid[len(SHORT_PREFIX):] if aid.startswith(SHORT_PREFIX) and len(aid) > len(SHORT_PREFIX) else aid


# ────────────────────────────────────────────────────────────────────────── channel

class Bar:
    """One row on the channel. Mutated by the reader, posted by the worker."""

    def __init__(self, name, total=None, timeout=None, parent=None):
        self.name, self.total, self.timeout, self.parent = name, total, timeout, parent
        self.done, self.detail = 0, ""
        self.token, self.start_failed, self.starting = None, False, False
        self.dirty, self.last_post = True, 0.0
        self.closed, self.fail, self.cancel, self.finish_posted = False, None, False, False
        self.complete_since = None

    def update(self, done=None, total=None, detail=None):
        if done is not None and done != self.done:
            self.done, self.dirty = done, True
        if total is not None and total != self.total:
            self.total, self.dirty = total, True
        if detail is not None and detail != self.detail:
            self.detail, self.dirty = detail, True


class Channel:
    """Owns every call into `progress`. The reader mutates bars under `lock`; `flush`
    (the worker, or the reader itself in trace mode) posts them, outside the lock, so a
    slow call never stalls the pipe."""

    def __init__(self, runner, interval, pid):
        self.runner, self.interval, self.pid = runner, interval, pid
        self.bars = []
        self.lock = threading.RLock()

    def open(self, name, total=None, timeout=None, parent=None):
        """A bar; with `parent`, a native SUB-JOB of that bar (progress-channel 0.6.0).

        The name stays PLAIN — never an indent or an arrow — so a module's bar shares ETA
        history with a standalone run of the same module. Nesting is the channel's job.
        """
        bar = Bar(name, total, timeout, parent)
        with self.lock:
            self.bars.append(bar)
        return bar

    @staticmethod
    def close(bar, fail=None, cancel=False):
        if not bar.closed:
            bar.closed, bar.fail, bar.cancel = True, fail, cancel

    def flush(self, force=False):
        """Post every bar that has something to say.

        A sub-job's `--parent` token is resolved at POST time, not plan time: the reactor
        bar and its first module bar are usually planned in the same flush, and the parent
        is posted first. A child whose parent has no token and is not being started in
        this flush — its start in flight on the other thread — is DEFERRED, untouched, to a
        later flush. A child whose parent's start FAILED is started top-level (where an
        inherited $PROGRESS_PARENT, if any, still nests it).

        Finishes go out children-first: finishing a parent cascades a CANCEL to any child
        still open, which would make every module bar read cancelled.
        """
        now = time.monotonic()
        plan = []
        with self.lock:
            starting_now = set()
            for b in self.bars:
                if b.finish_posted or b.start_failed or b.starting:
                    continue
                p = b.parent
                if (b.token is None and p is not None and p.token is None
                        and not p.start_failed and id(p) not in starting_now):
                    continue  # the parent has no token yet: defer, consuming nothing
                start = None
                if b.token is None:
                    b.starting = True
                    starting_now.add(id(b))
                    start = ["start", "--name", b.name, "--pid", str(self.pid)]
                    if b.total:
                        start += ["--total", str(b.total)]
                    if b.timeout:
                        start += ["--timeout", str(b.timeout)]
                step = None
                if b.dirty and (force or b.closed or b.token is None
                                or now - b.last_post >= self.interval):
                    step = ["--done", str(b.done)]
                    if b.total:
                        step += ["--total", str(b.total)]
                    if b.detail:
                        step += ["--detail", b.detail[:80]]
                    b.dirty, b.last_post = False, now
                finish = None
                if b.closed:
                    finish = ["--fail", b.fail] if b.fail else (["--cancel"] if b.cancel else [])
                    b.finish_posted = True
                if start or step or finish is not None:
                    plan.append((b, start, step, finish))
        finishes = []
        for b, start, step, finish in plan:
            if start:
                if b.parent is not None and b.parent.token:
                    start = start + ["--parent", b.parent.token]
                token = (self.runner("start", b, start) or "").strip()
                with self.lock:
                    b.token, b.start_failed, b.starting = token or None, not token, False
                if not token:
                    continue
            if step:
                self.runner("step", b, ["step", b.token] + step)
            if finish is not None:
                finishes.append((b, finish))
        for b, finish in reversed(finishes):  # children were opened after their parent
            self.runner("finish", b, ["finish", b.token] + finish)


def real_runner(verb, bar, args):
    """Best-effort call into the channel; never raises, never blocks for long.

    The environment is INHERITED on purpose (no `env=`): `progress start` defaults its
    --parent to $PROGRESS_PARENT, so a gate or release that exports one nests this whole
    Maven run under itself. A module bar passes its own --parent, which wins.
    """
    try:
        r = subprocess.run([*PROGRESS, *args], timeout=10 if verb == "start" else 5,
                           capture_output=True, text=True)
        return r.stdout
    except Exception:
        return ""


def trace_runner(path):
    seq = [0]

    def run(verb, bar, args):
        if verb == "start":
            seq[0] += 1
            bar.trace_id = "t%d" % seq[0]
        rec = {"verb": verb, "name": bar.name, "bar": getattr(bar, "trace_id", "?"),
               "done": bar.done, "total": bar.total, "detail": bar.detail,
               "fail": bar.fail, "cancel": bar.cancel, "args": args[2:] if verb != "start" else args[1:]}
        if verb == "start":
            # What the CLI would read as its default --parent: the environment is passed
            # through untouched, so an exported parent nests the whole run.
            rec["env_parent"] = os.environ.get("PROGRESS_PARENT")
        try:
            with open(path, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        except Exception:
            pass
        return getattr(bar, "trace_id", "") if verb == "start" else ""

    return run


# ────────────────────────────────────────────────────────────────────── reactor graph

class ModuleInfo:
    def __init__(self, packaging, deps, directory):
        self.packaging, self.deps, self.dir = packaging, deps, directory


def load_graph(root):
    """artifactId -> (packaging, reactor deps, dir), walked from the root pom's <modules>.

    Only edges Maven certainly has: the <parent> and the project-level <dependencies> of
    any scope and type (test-jar included). Profiles and plugin dependencies are skipped,
    which can only make a module's finish be seen LATER, never earlier than it happened.
    Any failure yields an empty graph: the tap then falls back to its other signals.
    """
    graph = {}

    def local(tag):
        return tag.rsplit("}", 1)[-1]

    def kid(el, name):
        for c in el:
            if local(c.tag) == name:
                return c
        return None

    def text(el, name):
        c = kid(el, name) if el is not None else None
        return c.text.strip() if c is not None and c.text else None

    def walk(pom, depth=0):
        if depth > 6 or not pom.is_file():
            return
        p = ET.parse(pom).getroot()
        aid = text(p, "artifactId")
        if not aid or aid in graph:
            return
        deps = set()
        parent = kid(p, "parent")
        if parent is not None and text(parent, "artifactId"):
            deps.add(text(parent, "artifactId"))
        ds = kid(p, "dependencies")
        for d in (ds if ds is not None else []):
            if local(d.tag) == "dependency" and text(d, "artifactId"):
                deps.add(text(d, "artifactId"))
        graph[aid] = ModuleInfo(text(p, "packaging") or "jar", deps, pom.parent)
        mods = kid(p, "modules")
        for m in (mods if mods is not None else []):
            if local(m.tag) == "module" and m.text:
                walk(pom.parent / m.text.strip() / "pom.xml", depth + 1)

    try:
        walk(Path(root) / "pom.xml")
    except Exception:
        return {}
    for info in graph.values():
        info.deps &= set(graph)
    return graph


# ──────────────────────────────────────────────────────────────────────────── reactor

class Module:
    def __init__(self, aid, info):
        self.aid = aid
        self.packaging = info.packaging if info else "?"
        self.deps = info.deps if info else set()
        self.started = self.finished = False
        self.last_mojo, self.child, self.child_mode = None, None, None
        self.has_listener = self.finish_with_child = False


class Reactor:
    def __init__(self, channel, label, timeout, graph, grace):
        self.ch, self.graph, self.grace = channel, graph, grace
        self.top = channel.open(label, timeout=timeout)
        self.modules, self.order = {}, []
        self.threads, self.total = 1, None
        self.last_test_module = None
        self.terminal = {}
        self.failed = None
        self.located = {}

    # ── modules
    def mod(self, aid):
        m = self.modules.get(aid)
        if m is None:
            m = self.modules[aid] = Module(aid, self.graph.get(aid))
        return m

    def closure(self, aid):
        seen, todo = set(), list(self.mod(aid).deps)
        while todo:
            d = todo.pop()
            if d not in seen:
                seen.add(d)
                todo += list(self.mod(d).deps)
        return seen

    def start(self, aid):
        m = self.mod(aid)
        if m.started:
            return
        if self.threads <= 1:
            proven = [self.modules[a] for a in self.order]
        else:
            proven = [self.modules[d] for d in self.closure(aid) if d in self.modules]
        for p in proven:
            if p.started:
                self.finish(p, proven=True)
        m.started = True
        self.order.append(aid)

    def finish(self, m, proven):
        if m.finished:
            return
        m.finished = True
        if proven and m.last_mojo:
            self.terminal.setdefault(m.packaging, set()).add(m.last_mojo)
        self.close_child(m)

    # ── child bars
    def open_child(self, m, total, mode):
        # A native sub-job of the reactor bar, under the module's PLAIN short name.
        m.child = self.ch.open(short(m.aid), total=total, parent=self.top)
        m.child_mode = mode
        return m.child

    def close_child(self, m):
        if m.child is not None and not m.child.closed:
            self.ch.close(m.child)
            if m.finish_with_child:
                self.finish(m, proven=False)

    def on_mojo(self, aid, key, plugin):
        m = self.mod(aid)
        if not m.started:
            self.start(aid)
        # A later mojo of the SAME module means its test run is over; the child was opened
        # after the test mojo's own line, so that one cannot close it.
        self.close_child(m)
        m.last_mojo = key
        is_test = plugin in TEST_PLUGINS
        if is_test:
            self.last_test_module = aid
        if key in self.terminal.get(m.packaging, ()):
            if is_test:
                m.finish_with_child = True
            else:
                self.finish(m, proven=False)

    def guess_test_module(self):
        return self.last_test_module or (self.order[-1] if self.order else None)

    def on_listener(self, aid, done, total, detail, now):
        aid = aid or self.guess_test_module()
        if not aid:
            return
        m = self.mod(aid)
        m.has_listener = True
        bar = m.child
        if bar is None or bar.closed:
            bar = self.open_child(m, total, "listener")
        elif m.child_mode != "listener":
            m.child_mode = "listener"  # the listener arrived after a fallback count: it wins
        bar.update(done=done, total=total, detail=detail)
        bar.complete_since = now if total > 0 and done >= total else None

    def locate(self, cls):
        fqn = cls.split("$", 1)[0]
        if fqn in self.located:
            return self.located[fqn]
        rel = fqn.replace(".", "/")
        found = None
        for aid, info in self.graph.items():
            base = info.dir / "src" / "test" / "java"
            if (base / (rel + ".java")).is_file():
                found = aid
                break
        self.located[fqn] = found
        return found

    def on_running(self, cls):
        aid = self.locate(cls) or self.guess_test_module()
        if not aid:
            return
        m = self.mod(aid)
        if m.has_listener:
            return
        bar = m.child
        if bar is None or bar.closed:
            bar = self.open_child(m, None, "running")
        if m.child_mode != "running":
            return
        bar.update(done=bar.done + 1, detail=cls.rsplit(".", 1)[-1])

    def end_all(self):
        for aid in self.order:
            self.finish(self.modules[aid], proven=False)
        for m in self.modules.values():
            self.close_child(m)

    # ── the stream
    def feed(self, line, now):
        s = ANSI.sub("", line.rstrip("\r\n"))
        if "[progress]" in s:
            g = RE_LISTENER.search(s)
            if g:
                self.on_listener(g.group("mod"), int(g.group("done")), int(g.group("total")),
                                 g.group("name").strip(), now)
                return self.refresh()
        g = RE_HEADER.match(s)
        if g:
            self.start(g.group("a"))
            return self.refresh()
        g = RE_MOJO.match(s)
        if g:
            key = "%s:%s(%s)" % (g.group("plugin"), g.group("goal"), g.group("exec") or "")
            self.on_mojo(g.group("a"), key, g.group("plugin"))
            return self.refresh()
        g = RE_BUILDING.search(s)
        if g:
            self.total = int(g.group("total"))
            return self.refresh()
        g = RE_RUNNING.search(s)
        if g:
            self.on_running(g.group("cls"))
            return None
        g = RE_THREADS.search(s)
        if g:
            self.threads = int(g.group("n"))
            return None
        if RE_SUMMARY.match(s):
            if RE_FAILURE.match(s):
                self.failed = "BUILD FAILURE"
            self.end_all()
            return self.refresh()
        return None

    def tick(self, now):
        for m in self.modules.values():
            b = m.child
            if b is not None and not b.closed and b.complete_since is not None \
                    and now - b.complete_since >= self.grace:
                self.close_child(m)
        self.refresh()

    def refresh(self):
        done = sum(1 for a in self.order if self.modules[a].finished)
        total = max(self.total or 0, len(self.order)) or None
        running = [short(a) for a in self.order if not self.modules[a].finished]
        if running:
            detail = "building: " + ", ".join(running)
        else:
            detail = ("%d module(s) done" % done) if self.order else ""
        self.top.update(done=done, total=total, detail=detail)

    def close(self, fail=None, cancel_children=False):
        if cancel_children:
            for m in self.modules.values():
                if m.child is not None:
                    self.ch.close(m.child, cancel=True)
        self.end_all()
        self.refresh()
        self.ch.close(self.top, fail=fail or self.failed)


class Single:
    """The legacy one-bar modes: maven, surefire, batch, count:<regex>."""

    def __init__(self, channel, label, pattern, rx, total, timeout):
        self.pattern, self.rx = pattern, rx
        self.bar = channel.open(label, total=int(total) if total and total.isdigit() else None,
                                timeout=timeout)
        self.ch = channel

    def feed(self, line, now):
        m = self.rx.search(line)
        if not m:
            return
        g = m.groupdict()
        detail = (g.get("name") or g.get("cls") or line.strip()).strip()
        if self.pattern == "surefire" and "." in detail and not g.get("name"):
            # The class, not its package: every class here shares "org.alexmond.kweblens."
            detail = detail.rsplit(".", 1)[-1]
        if g.get("done") and g.get("total"):
            # Absolute position. SET rather than increment: a resumed or `-T` reactor does
            # not emit one line per module in order.
            self.bar.update(done=int(g["done"]), total=int(g["total"]), detail=detail[:60])
        else:
            self.bar.update(done=self.bar.done + 1, detail=detail[:60])

    def tick(self, now):
        pass

    def close(self, fail=None, cancel_children=False):
        self.ch.close(self.bar, fail=fail)


# ─────────────────────────────────────────────────────────────────────────── controls

# Ordered to make the ATTRIBUTION control sharp. `kweblens-ui` starts last and owns the
# most recent surefire mojo, so `guess_test_module()` — the fallback when a class cannot be
# located — would answer "ui". The class on the `Running` line lives in kweblens-core. A
# tap that attributes by anything other than where the source actually is lands on the
# wrong bar, and the earlier version of this control could not tell the two apart because
# the fallback happened to give the same answer.
SELF_TEST_LINES = [
    "[INFO] Scanning for projects...",
    "[INFO] ------------------< org.alexmond:kweblens-core >------------------",
    "[INFO] Building kweblens-core 0.1.0-SNAPSHOT                        [2/7]",
    "[INFO] ------------------< org.alexmond:kweblens-ui >--------------------",
    "[INFO] Building kweblens :: ui (Vue SPA) 0.1.0-SNAPSHOT             [4/7]",
    "[INFO] --- surefire:3.5.4:test (default-test) @ kweblens-ui ---",
    "[INFO] Running org.alexmond.kweblens.column.ColumnParityTest",
    "[INFO] Running org.alexmond.kweblens.repo.TrackedSourcesStayGreppableTest",
    "[progress] kweblens-ui 400/764 · diagnosis.test.ts",
    "[progress] kweblens-ui 764/764 · columns.test.ts",
    "[INFO] BUILD SUCCESS",
]


def self_test() -> int:
    """Drive the reactor model over canned Maven output and check what it would POST.

    A bar is drawn from lines nobody reads, in a process nobody watches, so "it ran" and
    "it reported the truth" are different facts and only this separates them. It uses the
    REAL reactor graph — the repo's own POMs — because module attribution is the part a
    change here is most likely to break, and a fake graph would not exercise it.

    Transparency is checked by actually running the script as a subprocess, because that
    is the only claim whose failure would corrupt a build rather than a bar.
    """
    calls = []

    class Rec:
        def __init__(self):
            self.n = 0

        def __call__(self, verb, bar, args):
            self.n += 1
            calls.append((verb, bar.name, dict(zip(args[::2], args[1::2])) if verb != "start" else None,
                          bar.done, bar.total, bar.detail, bar.parent))
            return "tok%d" % self.n if verb == "start" else ""

    root = Path(__file__).resolve().parent.parent
    graph = load_graph(root)
    if not graph:
        print("FAIL  no reactor graph under %s — the control cannot check attribution" % root)
        return 1

    ch = Channel(Rec(), 0.0, os.getpid())
    model = Reactor(ch, "self-test", None, graph, 0.0)
    for line in SELF_TEST_LINES:
        model.feed(line, time.monotonic())
    ch.flush(force=True)

    bars = {}
    for verb, name, _a, done, total, detail, parent in calls:
        bars[name] = (done, total, detail, parent, verb)

    bad = []

    def check(ok, label, got):
        print(("ok    " if ok else "FAIL  ") + label + "   " + got)
        if not ok:
            bad.append(label)

    top = bars.get("self-test")
    check(top is not None and top[1] == 7, "top bar total is the reactor size Maven printed",
          "total=%s" % (top[1] if top else None))
    # A LOWER BOUND, never an overcount: kweblens-core is proven finished because
    # kweblens-ui started after it in a single-threaded build; kweblens-ui itself is still
    # building when BUILD SUCCESS arrives, and the summary closes it.
    check(top is not None and 0 < top[0] <= 7, "modules finished is a count within the reactor",
          "done=%s" % (top[0] if top else None))

    ui = bars.get("ui")
    core = bars.get("core")
    check(core is not None, "a child bar exists for the module whose tests ran",
          "child bars=%s" % sorted(k for k in bars if k != "self-test"))
    # The attribution claim, and the reason the canned lines are ordered as they are: BOTH
    # classes live in kweblens-core/src/test/java while kweblens-ui is the module that
    # started last and owns the last surefire mojo. So the count landing on `core` can only
    # come from locating the source; the fallback would have put it on `ui`.
    check(core is not None and core[0] == 2 and core[3] is not None,
          "surefire 'Running' lines COUNT, under the module that OWNS THE CLASS, not the last one started",
          "core done=%s parent=%s" % (core[0] if core else None, bool(core and core[3])))
    check(ui is None or ui[0] != 2, "the counted lines did NOT land on the last-started module",
          "ui done=%s" % (ui[0] if ui else None))
    check(core is not None and core[1] is None, "a counted bar declares NO total rather than guessing one",
          "total=%s" % (core[1] if core else None))

    check(ui is not None and ui[0] == 764 and ui[1] == 764,
          "a [progress] line gives its module a REAL denominator", "%s/%s" % (ui[:2] if ui else (None, None)))
    check(ui is not None and ui[2] == "columns.test.ts", "the bar names what is running now",
          "detail=%r" % (ui[2] if ui else None))

    # Transparency, end to end, through BOTH copy paths — which is the correction that made
    # this control real. The tap copies bytes in two places: the reading loop, and the
    # `passthrough()` it degrades to when the channel is absent or `--quiet` is passed. The
    # first version of this check only ever exercised the reading loop, so breaking
    # `passthrough()` left it green. Each path is run and each must be byte-identical,
    # including a line that is not valid UTF-8 — the stream is binary and never decoded.
    payload = b"".join(l.encode() + b"\n" for l in SELF_TEST_LINES) + b"\xff\xfe not utf-8\n"
    for label, argv in (("reading loop", ["transparency"]), ("passthrough", ["transparency", "--quiet"])):
        proc = subprocess.run([sys.executable, __file__] + argv, input=payload,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        check(proc.stdout == payload, "stdout is stdin byte for byte — %s" % label,
              "%d bytes in, %d out" % (len(payload), len(proc.stdout)))

    print()
    print("All controls hold." if not bad else "%d control(s) FAILED: %s" % (len(bad), ", ".join(bad)))
    return 1 if bad else 0


# ───────────────────────────────────────────────────────────────────────────── main


def parse_args(argv):
    o = {"name": "build", "pattern": "reactor", "total": None, "timeout": None, "quiet": False,
         "interval": 0.5, "grace": 1.5, "root": None}
    if argv and not argv[0].startswith("--"):
        o["name"], argv = argv[0], argv[1:]
    i = 0
    while i < len(argv):
        a = argv[i]
        val = argv[i + 1] if i + 1 < len(argv) else None
        if a == "--quiet":
            o["quiet"], i = True, i + 1
            continue
        key = {"--pattern": "pattern", "--total": "total", "--name": "name", "--timeout": "timeout",
               "--interval": "interval", "--grace": "grace", "--reactor-root": "root"}.get(a)
        if key and val is not None:
            o[key], i = val, i + 2
        else:
            i += 1
    for k in ("interval", "grace"):
        try:
            o[k] = max(0.0, float(o[k]))
        except (TypeError, ValueError):
            o[k] = 0.5 if k == "interval" else 1.5
    return o


def passthrough():
    """Be a plain `cat`, byte for byte. The one job that must never fail."""
    out = sys.stdout.buffer
    for raw in sys.stdin.buffer:
        out.write(raw)
        out.flush()
    return 0


PROGRESS = _resolve_progress()


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        return self_test()
    o = parse_args(sys.argv[1:])
    trace = os.environ.get("PROGRESS_TAP_TRACE")
    if o["quiet"] or not (PROGRESS or trace):
        return passthrough()

    pattern = o["pattern"]
    try:
        if pattern == "reactor":
            rx = None
        else:
            rx = re.compile(PATTERNS.get(pattern) or (
                pattern[len("count:"):] if pattern.startswith("count:") else PATTERNS["maven"]))
    except re.error:
        return passthrough()

    channel = Channel(trace_runner(trace) if trace else real_runner, o["interval"], os.getppid())
    if pattern == "reactor":
        root = Path(o["root"]) if o["root"] else Path(__file__).resolve().parent.parent
        model = Reactor(channel, o["name"], o["timeout"], load_graph(root), o["grace"])
    else:
        model = Single(channel, o["name"], pattern, rx, o["total"], o["timeout"])

    stop = threading.Event()

    def worker():
        while not stop.wait(0.1):
            try:
                with channel.lock:
                    model.tick(time.monotonic())
                channel.flush()
            except Exception:
                pass

    thread = None
    if not trace:
        thread = threading.Thread(target=worker, name="progress-tap", daemon=True)
        thread.start()

    def on_term(signum, frame):
        raise KeyboardInterrupt

    try:
        signal.signal(signal.SIGTERM, on_term)
    except Exception:
        pass

    out = sys.stdout.buffer
    failed, cancel = None, False
    try:
        for raw in sys.stdin.buffer:
            out.write(raw)
            out.flush()
            try:
                line = raw.decode("utf-8", "replace")
                with channel.lock:
                    now = time.monotonic()
                    model.feed(line, now)
                    if trace:
                        model.tick(now)
                if trace:
                    channel.flush()
            except Exception:
                pass  # a parse bug must never cost the build its output
    except BrokenPipeError:
        # The reader went away. Still finish every row: a job left at "running" is exactly
        # the dead-bar-that-lies this whole ticket exists to remove.
        failed, cancel = "downstream closed the pipe", True
    except KeyboardInterrupt:
        failed, cancel = "interrupted", True
    finally:
        stop.set()
        if thread is not None:
            thread.join(timeout=15)
        try:
            with channel.lock:
                model.close(fail=failed, cancel_children=cancel)
            channel.flush(force=True)
        except Exception:
            pass
        try:
            out.flush()
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BrokenPipeError:
        os._exit(0)
