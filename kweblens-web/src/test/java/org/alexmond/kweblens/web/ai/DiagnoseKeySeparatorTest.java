package org.alexmond.kweblens.web.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The composite group key, pinned across the two languages that build it.
 *
 * <p>
 * {@code DiagnoseService.promptInput} and the SPA's {@code groupFindings} group the same
 * findings on two surfaces, each keying on severity <em>and</em> title joined by a
 * separator. They agreed only because two authors happened to pick the same character; if
 * one moved, the model's evidence blocks and the panel's headings would disagree and
 * nothing would say so. This reads the SPA's source and pins the two together.
 *
 * <p>
 * It also pins the shape of the declaration, which is a separate defect (#408): the
 * separator used to be a <b>raw</b> NUL byte in both files, and a file containing one is
 * classified as binary by {@code grep}, which then prints nothing for every search over
 * it — no match, no warning, and no exit status you can tell apart from "no match". A
 * review of #407 grepped {@code diagnosis.ts} for a function the PR had just added, got
 * silence, and nearly reported the function did not exist. So the character must be
 * written as a source escape, and no raw control byte may return to either file by any
 * route.
 */
class DiagnoseKeySeparatorTest {

	private static final String SPA_SOURCE = "kweblens-ui/src/diagnosis.ts";

	private static final String SERVER_SOURCE = "kweblens-web/src/main/java/org/alexmond"
			+ "/kweblens/web/ai/DiagnoseService.java";

	/** Matches the escape form only — a literal control byte here would not match. */
	private static final Pattern SPA_DECLARATION = Pattern.compile("KEY_SEPARATOR = '\\\\u([0-9a-fA-F]{4})'");

	/**
	 * ASCII 0x1F, INFORMATION SEPARATOR ONE. Spelled out here rather than derived, so
	 * that changing the constant does not silently change what this test claims.
	 */
	private static final int UNIT_SEPARATOR = 0x1F;

	@Test
	void theServerJoinsOnTheAsciiUnitSeparator() {
		assertThat((int) DiagnoseService.KEY_SEPARATOR)
			.as("a printable separator can occur inside a title, so the key would be ambiguous")
			.isEqualTo(UNIT_SEPARATOR);
	}

	@Test
	void theSpaJoinsOnTheSameSeparatorAsTheServer() throws IOException {
		String spa = Files.readString(repoRoot().resolve(SPA_SOURCE), StandardCharsets.UTF_8);
		Matcher declaration = SPA_DECLARATION.matcher(spa);
		assertThat(declaration.find())
			.as("%s must declare KEY_SEPARATOR as a source escape, not as a literal control byte", SPA_SOURCE)
			.isTrue();
		char spaSeparator = (char) Integer.parseInt(declaration.group(1), 16);
		assertThat(spaSeparator).as("the two surfaces group the same findings; a divergence would be silent")
			.isEqualTo(DiagnoseService.KEY_SEPARATOR);
	}

	@Test
	void neitherSourceCarriesARawControlByte() throws IOException {
		for (String source : List.of(SPA_SOURCE, SERVER_SOURCE)) {
			byte[] bytes = Files.readAllBytes(repoRoot().resolve(source));
			for (int i = 0; i < bytes.length; i++) {
				int value = bytes[i] & 0xFF;
				if (value < 0x20 && value != '\t' && value != '\n' && value != '\r') {
					fail("%s byte %d is a raw control character (0x%02X); grep then reports this whole"
							+ " file as binary and finds nothing in it. Write it as a source escape (#408).", source, i,
							value);
				}
			}
		}
	}

	/**
	 * The directory holding both modules. Walked rather than assumed, so the test behaves
	 * the same under Surefire and in an IDE — and throws rather than skipping when it
	 * cannot find them, because a cross-language pin that quietly does not run is the
	 * failure mode it exists to prevent.
	 */
	private static Path repoRoot() {
		Path start = Path.of("").toAbsolutePath();
		for (Path dir = start; dir != null; dir = dir.getParent()) {
			if (Files.isRegularFile(dir.resolve(SPA_SOURCE)) && Files.isRegularFile(dir.resolve(SERVER_SOURCE))) {
				return dir;
			}
		}
		throw new IllegalStateException("neither " + SPA_SOURCE + " nor " + SERVER_SOURCE + " found above " + start);
	}

}
