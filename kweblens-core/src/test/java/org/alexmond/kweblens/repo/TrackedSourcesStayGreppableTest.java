package org.alexmond.kweblens.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Repository-wide gate: no tracked file that is meant to be read as text may carry a raw
 * control byte other than tab, LF or CR.
 *
 * <p>
 * The defect this stops is silent in both directions. {@code grep} classifies a file
 * containing a NUL as binary and prints <b>nothing</b> for every search over it — no
 * match, no warning, and no exit status you can tell apart from "no match". Reviewing
 * #407 a search of {@code diagnosis.ts} for a function that PR had just added returned
 * silence twice and nearly became a report that the function did not exist. A raw
 * {@code U+001F} is subtler: {@code grep} works, but {@code file} reports {@code data}.
 * Only writing the character as a <b>source escape</b> keeps a file both greppable and
 * text, which is why {@code DiagnosisSummaryCache} has always joined its fingerprint
 * fields with the ASCII unit and record separators written that way. That was already the
 * package's convention; the two files #408 fixed were the deviation, not the innovation.
 *
 * <p>
 * {@code DiagnoseKeySeparatorTest} pins those two files to the <em>same</em> separator
 * across Java and TypeScript, which no byte scanner can see. This is the other half: it
 * checks every tracked file rather than two named ones, so the class of defect cannot
 * come back somewhere new.
 *
 * <p>
 * <b>Why it lives in {@code kweblens-core}</b> and not in the module it happens to be
 * about: {@code kweblens-core} and {@code kweblens-cli} are the only modules in the root
 * POM's unconditional {@code <modules>}. Everything else — {@code kweblens-web},
 * {@code kweblens-ui}, {@code kweblens-it}, {@code kweblens-tui} — is added by the
 * {@code default} or {@code docker} profile, so a gate hosted there would not run under
 * {@code -Prelease}. A gate that can be switched off by a profile is not a gate. Nothing
 * here touches cluster access; it reads the checkout.
 *
 * <p>
 * <b>It cannot pass by not running.</b> There is no {@code @Disabled} and no
 * {@code Assumption}: every "cannot determine" path throws, so the build errors rather
 * than reporting green. {@link #theScanCoversTheWholeRepositoryNotJustThisModule()}
 * refuses a scan that is too small or that never left this module,
 * {@link #theDetectorItselfSeesARawControlByte()} is the positive control that proves the
 * detector can see the byte it claims is absent, and {@link #theGateIsItselfTracked()}
 * refuses a copy of this gate that {@code .gitignore} has hidden from CI.
 */
class TrackedSourcesStayGreppableTest {

	/**
	 * Extensions excluded from the scan, because the files are binary container formats
	 * by specification: their bytes are not source, nobody greps them, and a control byte
	 * in one is the format working correctly. Kept to formats that are actually stored in
	 * this repository — {@link #everyExclusionIsEarnedByARealFile()} fails if an entry
	 * stops matching anything, so the list cannot quietly grow into a blanket. There are
	 * deliberately <b>no path exclusions</b>: a test fixture that needs a raw control
	 * byte builds it in code from an escape, which is also how it stays readable.
	 */
	private static final Set<String> BINARY_FORMATS = Set.of("png");

	/**
	 * Directories that must appear in the scan for it to be believable. Each is tracked
	 * regardless of which Maven profile is active, because {@code git} does not know
	 * about profiles.
	 */
	private static final List<String> EXPECTED_TOP_LEVEL = List.of("kweblens-core", "kweblens-cli", "kweblens-web",
			"kweblens-ui", "docs", "scripts");

	/**
	 * Well under the ~700 tracked files, but far above anything one module contributes.
	 */
	private static final int MINIMUM_PLAUSIBLE_SCAN = 400;

	/** How many offending files to name before truncating the failure message. */
	private static final int MAX_REPORTED = 20;

	/** Where this class's own source sits, so it can check that git can see it. */
	private static final String OWN_MODULE_TEST_ROOT = "kweblens-core/src/test/java/";

	@Test
	void noTrackedSourceCarriesARawControlByte() throws IOException {
		Path root = repoRoot();
		List<String> offenders = new ArrayList<>();
		for (String path : scannedFiles()) {
			Path file = root.resolve(path);
			// A tracked file deleted in the working tree is still listed by `git
			// ls-files`. It carries no bytes, and failing on a half-finished refactor
			// would make this gate fire on a state that is fine.
			if (!Files.isRegularFile(file)) {
				continue;
			}
			byte[] bytes = Files.readAllBytes(file);
			int offset = firstControlByte(bytes);
			if (offset >= 0) {
				offenders.add(describe(path, bytes, offset));
			}
		}
		if (!offenders.isEmpty()) {
			fail("%d tracked file(s) carry a raw control byte, so grep reports the whole file as binary and"
					+ " finds nothing in it (#408, #421). Write the character as a source escape — that is what"
					+ " DiagnosisSummaryCache has always done. If a file below is a binary format rather than"
					+ " source, add its extension to BINARY_FORMATS with the reason.%n%s", offenders.size(),
					String.join(System.lineSeparator(), capped(offenders)));
		}
	}

	/**
	 * The scan is worthless if it quietly covered nothing, or covered only the module it
	 * is hosted in. Both are the shape of failure a gate is supposed to prevent, so both
	 * are assertions rather than trust.
	 */
	@Test
	void theScanCoversTheWholeRepositoryNotJustThisModule() {
		List<String> scanned = scannedFiles();
		assertThat(scanned).as("git listed implausibly few tracked files; the scan did not really run")
			.hasSizeGreaterThan(MINIMUM_PLAUSIBLE_SCAN);
		for (String directory : EXPECTED_TOP_LEVEL) {
			assertThat(scanned).as("the scan never reached %s, so it is not repository-wide", directory)
				.anyMatch((path) -> path.startsWith(directory + "/"));
		}
	}

	/**
	 * Positive control. A scanner that read files with the wrong encoding, or skipped
	 * them, would report a clean tree either way — so prove the detector can see the byte
	 * before believing it when it says there are none.
	 */
	@Test
	void theDetectorItselfSeesARawControlByte() {
		byte[] clean = "tab\tand\r\nnewline\n".getBytes(StandardCharsets.UTF_8);
		assertThat(firstControlByte(clean)).as("tab, CR and LF are the three control bytes text is made of")
			.isEqualTo(-1);

		byte[] withNul = new byte[] { 'a', 'b', 0x00, 'c' };
		assertThat(firstControlByte(withNul)).as("a NUL is what made two files invisible to grep").isEqualTo(2);
		assertThat(describe("some/file.ts", withNul, 2)).contains("some/file.ts").contains("offset 2").contains("0x00");

		byte[] withUnitSeparator = new byte[] { 'a', '\n', 'b', 0x1F };
		assertThat(firstControlByte(withUnitSeparator)).as("a raw U+001F greps, but `file` still calls it data")
			.isEqualTo(3);
		assertThat(describe("some/file.ts", withUnitSeparator, 3)).contains("line 2");
	}

	/**
	 * A gate that is not in the repository is not a gate anywhere but the machine it was
	 * written on. This was not hypothetical: the first draft lived in package
	 * {@code org.alexmond.kweblens.build}, and {@code .gitignore} line 5 is
	 * {@code build/}, so the whole directory was invisible to {@code git} — it ran green
	 * locally and would never have reached CI. Any package named after a build output
	 * ({@code build}, {@code target}, {@code dist}) has the same problem.
	 */
	@Test
	void theGateIsItselfTracked() {
		String path = OWN_MODULE_TEST_ROOT + getClass().getName().replace('.', '/') + ".java";
		assertThat(trackedFiles())
			.as("%s is not tracked — .gitignore swallowed it, so CI would never see this gate", path)
			.contains(path);
	}

	/**
	 * An exclusion nobody can point at a file for is how a gate stops being trusted, so a
	 * stale entry is itself a failure. The fix is one line either way: delete the entry,
	 * or say which file needs it.
	 */
	@Test
	void everyExclusionIsEarnedByARealFile() {
		List<String> tracked = trackedFiles();
		for (String extension : BINARY_FORMATS) {
			assertThat(tracked)
				.as("no tracked file has extension .%s any more, so excluding it claims nothing", extension)
				.anyMatch((path) -> extension.equals(extensionOf(path)));
		}
	}

	private static List<String> capped(List<String> offenders) {
		if (offenders.size() <= MAX_REPORTED) {
			return offenders;
		}
		List<String> shown = new ArrayList<>(offenders.subList(0, MAX_REPORTED));
		shown.add("… and " + (offenders.size() - MAX_REPORTED) + " more");
		return shown;
	}

	/** Names the file, the byte offset, the byte, and the line it landed on. */
	private static String describe(String path, byte[] bytes, int offset) {
		int line = 1;
		for (int i = 0; i < offset; i++) {
			if (bytes[i] == '\n') {
				line++;
			}
		}
		return String.format(Locale.ROOT, "%s: offset %d (line %d) is 0x%02X", path, offset, line, bytes[offset]);
	}

	/** The offset of the first byte below 0x20 that is not tab, LF or CR, or -1. */
	private static int firstControlByte(byte[] bytes) {
		for (int i = 0; i < bytes.length; i++) {
			int value = bytes[i] & 0xFF;
			if (value < 0x20 && value != '\t' && value != '\n' && value != '\r') {
				return i;
			}
		}
		return -1;
	}

	private static List<String> scannedFiles() {
		List<String> scanned = new ArrayList<>();
		for (String path : trackedFiles()) {
			if (!BINARY_FORMATS.contains(extensionOf(path))) {
				scanned.add(path);
			}
		}
		return scanned;
	}

	private static String extensionOf(String path) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		int dot = name.lastIndexOf('.');
		return (dot < 0) ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	/**
	 * "Tracked" is {@code git ls-files}, the honest definition: walking the filesystem
	 * instead would drag in {@code target/}, {@code node_modules/} and
	 * {@code .playwright/}, none of which anyone maintains. Deliberately not
	 * {@code git}'s own binary detection, which is itself "contains a NUL" — asking it
	 * which files to skip would skip exactly the files this looks for.
	 *
	 * <p>
	 * Only the <em>list</em> comes from the index; the bytes are read from the working
	 * tree, so an unstaged edit that adds a control byte to a tracked file is caught
	 * before it is committed. A file that has never been {@code git add}ed is not listed
	 * and so not scanned — it is also not yet part of the repository.
	 */
	private static List<String> trackedFiles() {
		String listing = git(repoRoot(), "ls-files", "-z");
		List<String> paths = new ArrayList<>();
		for (String path : listing.split("\0")) {
			if (!path.isEmpty()) {
				paths.add(path);
			}
		}
		return paths;
	}

	/**
	 * The checkout root. Asked of {@code git} rather than guessed, so this behaves the
	 * same under Surefire, in an IDE and inside a linked worktree (where {@code .git} is
	 * a file, not a directory).
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
