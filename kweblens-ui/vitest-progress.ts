/**
 * Emits a MEASURED test-progress line, so this module has a real bar during the ~1m30s it
 * spends in the reactor rather than a count with no denominator.
 *
 * `scripts/progress-tap.py` draws one bar per module while `./mvnw verify` runs. For a Java
 * module it has only surefire's `Running <class>` lines to count, which gives movement and
 * the current class but no total. This module can do better, because vitest knows how much
 * work there is before any of it runs — and it is the module worth doing it for: the second
 * largest block of wall clock in the build.
 *
 * WHY FILES AND NOT TESTS. Tests look like the better unit and are not. `onTestModuleCollected`
 * fires as each file is LOADED, which happens progressively across workers, so a total summed
 * from it starts near zero and grows all run — the percentage would go backwards, and a bar
 * that moves backwards is worse than one that only crawls. The file count arrives complete in
 * `onTestRunStart` before anything executes, it cannot disagree with what runs, and it is the
 * same unit as the reactor bar above it (things finished / things to do).
 *
 * WHY IT PRINTS INSTEAD OF CALLING THE PROGRESS CHANNEL. A subprocess per file is ~50ms times
 * 58. A print is free, needs no dependency inside the test process, and flows down the same
 * pipe to the same tap, which already knows how to read a `done/total` line. With the tap off
 * it is a few extra log lines and nothing else changes.
 *
 * WHY STDERR. `scripts/dev-verify.sh` merges stderr into the tapped stream, so the tap sees it
 * either way and nothing reading stdout is disturbed.
 *
 * THE SHAPE IS A CONTRACT. `[progress] <module> <done>/<total> · <detail>`, on ONE line, with
 * that separator — it is what `LISTENER` in the tap matches, and the module name is what puts
 * the count on this module's bar instead of pooling it with another's. The tap's `--self-test`
 * feeds this exact shape, so changing it on one side without the other is caught.
 *
 * Set `KWEBLENS_TEST_PROGRESS=false` to silence it.
 */

/** The artifactId, because the tap keys its bars on what Maven calls this module. */
const MODULE = 'kweblens-ui';

/** The one shape the tap reads. Keep it on one line and keep the ` · `. */
export function progressLine(module: string, done: number, total: number, detail: string): string {
	return `[progress] ${module} ${done}/${total} · ${detail}`;
}

/** The file's own name — the path is longer than the bar is wide. */
export function fileLabel(moduleId: string): string {
	const name = moduleId.slice(moduleId.lastIndexOf('/') + 1);
	return name || 'tests';
}

export default class ProgressReporter {
	private done = 0;

	private total = 0;

	onTestRunStart(specifications: ReadonlyArray<unknown>): void {
		this.done = 0;
		this.total = specifications.length;
	}

	onTestModuleEnd(testModule: { moduleId?: string }): void {
		// `total <= 0` means the run never reported its plan; the tap's own counting fallback
		// is the honest answer there, so emit nothing rather than invent a denominator.
		if (this.total <= 0 || String(process.env.KWEBLENS_TEST_PROGRESS).toLowerCase() === 'false') {
			return;
		}
		this.done += 1;
		// A numerator that can exceed its denominator is a bar reading past 100% while it is
		// still working, which looks exactly like a run that finished and hung.
		if (this.done > this.total) {
			this.total = this.done;
		}
		process.stderr.write(progressLine(MODULE, this.done, this.total, fileLabel(testModule?.moduleId ?? '')) + '\n');
	}
}
