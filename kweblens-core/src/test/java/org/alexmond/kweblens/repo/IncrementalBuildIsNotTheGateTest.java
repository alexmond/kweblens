package org.alexmond.kweblens.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-wide gate: the reactor stays whole unless a human asks for less, per
 * invocation.
 *
 * <p>
 * {@code .mvn/extensions.xml} carries gitflow-incremental-builder, which builds only the
 * modules changed since {@code origin/main}. That is a good inner loop and a bad gate,
 * and the difference is one property in the root POM. If {@code gib.disable} ever becomes
 * {@code false} there, then CI's {@code ./mvnw -B verify}, {@code dev-verify.sh} and
 * {@code maven_release.yml} all quietly stop being whole-reactor builds — and the way
 * that shows up is not a red build but a green one, on a PR whose skipped modules were
 * never compiled. The repo's oldest rule is that a green {@code dev-verify.sh} means a
 * green PR; this test is what keeps the sentence true after the extension was added.
 *
 * <p>
 * The hole is not hypothetical and it was measured before this file existed. A raw
 * {@code U+001F} appended to a {@code kweblens-tui} source makes the full reactor RED,
 * because {@code TrackedSourcesStayGreppableTest} — which lives in <b>this</b> module —
 * walks every path {@code git ls-files} prints. An incremental build of the same tree
 * selects {@code kweblens-tui} alone, never runs that test, and reports BUILD SUCCESS.
 * {@code dev-verify.sh}'s {@code REPO_WIDE_GATES} covers exactly that case for the opt-in
 * path, by running the gate by name rather than by widening the reactor — measured,
 * force-building all of {@code kweblens-core} costs 2m15s of the 8m43s full gate, and
 * running just that test costs 21s. Nothing but this test covers the default one.
 *
 * <p>
 * Reproduce that hole with a <b>non-Java</b> file. {@code dev-verify.sh} runs
 * {@code spring-javaformat:apply} first, and that strips a raw control byte out of Java
 * source — measured, the character is gone before the gate reads the file, so a Java
 * control is a green run that proves nothing. A {@code 0x1F} in
 * {@code kweblens-ui/src/labelForward.ts} is the honest one, and is also where the live
 * exposure is: a {@code .ts} change selects {@code kweblens-ui} and {@code kweblens-web},
 * never the {@code kweblens-core} that holds the gate.
 *
 * <p>
 * The second assertion is about a different failure. GIB's own default reference branch
 * is {@code refs/remotes/origin/develop}, which this repository has never had, and a
 * missing reference branch is a hard {@code ERROR} that ends the build before a single
 * module is built — not a fallback to building everything. Measured: with the extension
 * installed and the property deleted, every build in the checkout goes red. So the pin is
 * load-bearing even though the extension is disabled, because {@code -Dgib.disable=false}
 * on the command line must land on a configuration that works.
 */
class IncrementalBuildIsNotTheGateTest {

	/**
	 * Matches the property in the root POM. Deliberately tolerant of whitespace and
	 * deliberately NOT an XML parse: the value is a single literal, and a parser would
	 * add a dependency to say the same thing.
	 */
	private static Pattern property(String name) {
		return Pattern.compile("<" + Pattern.quote(name) + ">\\s*([^<]*?)\\s*</" + Pattern.quote(name) + ">");
	}

	@Test
	void theDefaultReactorIsWholeBecauseTheIncrementalBuilderIsDisabled() {
		assertThat(rootPomProperty("gib.disable")).as("""
				<gib.disable> in the root POM must stay 'true'. It is what makes `./mvnw verify` \
				— CI's gate, dev-verify.sh and the release workflow — build every module. Setting \
				it to false does not make the gate faster, it makes the gate smaller, and the \
				symptom is a PASSING build over a module that was never compiled. Ask for less \
				per invocation instead: `scripts/dev-verify.sh --fast`, or -Dgib.disable=false.""").isEqualTo("true");
	}

	/**
	 * Asserts the pinned literal and deliberately <b>not</b> that the ref resolves here.
	 * Whether {@code refs/remotes/origin/main} exists is a fact about a checkout, not
	 * about this repository: {@code actions/checkout} fetches the PR merge ref into a
	 * detached HEAD with no local {@code main} and no remote-tracking branch, and it does
	 * not need one, because {@code gib.disable} means CI never asks GIB to resolve
	 * anything. The first version of this test checked {@code git branch --list main} and
	 * was green on every developer machine and red on CI — a gate that fails on things
	 * that are fine, which is the instrument defect this repo ranks above feature work.
	 * It also asked the wrong question: the property names a <em>remote-tracking</em>
	 * ref, and GIB's own error text warns against confusing the two.
	 */
	@Test
	void theReferenceBranchIsPinned() {
		String reference = rootPomProperty("gib.referenceBranch");
		assertThat(reference).as("""
				<gib.referenceBranch> must be pinned. gitflow-incremental-builder defaults to \
				refs/remotes/origin/develop, which this repository does not have, and it treats a \
				missing reference branch as a build-ending ERROR rather than as a reason to build \
				everything. Unpinned, every incremental invocation in this checkout is red before \
				it starts.""").isEqualTo("refs/remotes/origin/main");
	}

	/**
	 * The gate list in {@code dev-verify.sh} is the opt-in path's only protection against
	 * a module-local gate with repo-wide reach, so it may not quietly empty out. This
	 * module is named because it holds {@code TrackedSourcesStayGreppableTest}.
	 */
	@Test
	void theFastPathStillRunsTheRepoWideGate() throws IOException {
		Path script = repoRoot().resolve("scripts/dev-verify.sh");
		assertThat(script).as("scripts/dev-verify.sh must exist — it is the documented fast path").exists();
		assertThat(Files.readString(script, StandardCharsets.UTF_8)).as("""
				dev-verify.sh must keep running kweblens-core's TrackedSourcesStayGreppableTest on \
				--fast. It scans every tracked path in the repository, so a change confined to \
				another module selects a reactor that never runs it, and --fast then goes green over \
				a defect the real gate fails on.""")
			.contains("REPO_WIDE_GATES=(\"kweblens-core:TrackedSourcesStayGreppableTest\")");
	}

	private static String rootPomProperty(String name) {
		Path pom = repoRoot().resolve("pom.xml");
		String text;
		try {
			text = Files.readString(pom, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not read " + pom, ex);
		}
		Matcher matcher = property(name).matcher(text);
		assertThat(matcher.find()).as("<%s> must be declared in the root POM (%s)", name, pom).isTrue();
		return matcher.group(1);
	}

	/**
	 * The checkout root. Asked of {@code git} rather than guessed, so this behaves the
	 * same under Surefire, in an IDE and inside a linked worktree.
	 */
	private static Path repoRoot() {
		Path here = Path.of("").toAbsolutePath();
		return Path.of(git(here, "rev-parse", "--show-toplevel").trim());
	}

	/**
	 * Throws rather than skipping on every failure: a gate that opts out is not a gate.
	 */
	private static String git(Path directory, String... arguments) {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(arguments));
		try {
			Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(2, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new IllegalStateException("git " + String.join(" ", arguments) + " did not finish");
			}
			if (process.exitValue() != 0) {
				throw new IllegalStateException(
						"git " + String.join(" ", arguments) + " failed in " + directory + ": " + err.trim());
			}
			return out;
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not run git in " + directory, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted running git in " + directory, ex);
		}
	}

}
