package org.alexmond.kweblens.tui;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import org.jline.builtins.ScreenTerminal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * There is exactly one JLine on this classpath, and it is 3.30.16.
 *
 * <p>
 * <b>Why a test rather than a comment on the pom.</b>
 * {@code tamboui-jline3-backend:0.4.0} depends on the <em>uber</em> artifact
 * {@code org.jline:jline}, which bundles {@code org/jline/terminal/**},
 * {@code org/jline/reader/**} and {@code org/jline/builtins/**} under exactly the package
 * names the split artifacts also use. Managing the split artifacts forward — which
 * {@code docs/research/k9s-functionality.md} §11 originally instructed — therefore adds a
 * second copy of every class beside the stale uber instead of replacing it, and jar order
 * decides which wins. Measured by the GH#361 spike on that classpath:
 * {@code ScreenTerminal} still loaded from {@code jline-3.25.1.jar}, i.e. the exact
 * opposite of the pin's intent, silently, because duplicate classes are not an error.
 *
 * <p>
 * The pin this build takes is to manage the <em>uber</em> artifact. That is an intention;
 * this file is the measurement. It asserts the outcome — one classpath URL per class, all
 * of them the same jar, and that jar being the pinned version — because "it works today"
 * is precisely what a duplicated classpath looks like right up until it does not.
 *
 * <p>
 * {@link ScreenTerminal} is singled out because it is the exec pane's entire VT emulator
 * (#370): the class the recommendation was built around was the class the wrong pin left
 * stale.
 */
class JLineSingleProviderTest {

	/** The version pinned in the root pom's {@code jline.version}. */
	private static final String PINNED = "3.30.16";

	private static final char ESC = 0x1B;

	/**
	 * One class from each JLine package the TUI depends on: the emulator behind the exec
	 * pane, the terminal TamboUI's backend drives, and the line reader.
	 */
	private static final List<String> CLASSES = List.of("org/jline/builtins/ScreenTerminal.class",
			"org/jline/terminal/Terminal.class", "org/jline/reader/LineReader.class");

	@Test
	void everyJlinePackageHasExactlyOneProvider() throws IOException {
		for (String resource : CLASSES) {
			assertThat(providersOf(resource))
				.as("%s must be on the classpath exactly once — two copies means the "
						+ "uber jar and a split artifact are both present and jar order picks the winner", resource)
				.hasSize(1);
		}
	}

	@Test
	void allThreeComeFromTheSamePinnedJar() {
		List<String> jars = CLASSES.stream().map(JLineSingleProviderTest::jarOf).distinct().toList();
		assertThat(jars).as("terminal, reader and builtins must come from one JLine artifact, not a mixture")
			.hasSize(1);
		assertThat(jars.get(0)).as("the resolved JLine must be the pinned version").contains(PINNED);
	}

	/**
	 * The pinned {@code ScreenTerminal} is not merely present, it works: this feeds it
	 * the escape sequences an exec pane's byte stream is made of and reads the cell grid
	 * back. A pin proved only by a file name would still pass if the jar were unusable.
	 */
	@Test
	void thePinnedScreenTerminalStillEmulates() {
		int width = 20;
		int height = 3;
		ScreenTerminal screen = new ScreenTerminal(width, height);
		// Clear, home the cursor, print — the shape of the bytes an exec pane receives.
		screen.write(ESC + "[2J" + ESC + "[Hkweblens");

		long[] cells = new long[width * height];
		screen.dump(cells, 0, 0, height, width, null);

		StringBuilder firstLine = new StringBuilder();
		for (int column = 0; column < width; column++) {
			firstLine.appendCodePoint((int) (cells[column] & 0xFFFF_FFFFL));
		}
		assertThat(firstLine.toString().trim()).isEqualTo("kweblens");
	}

	private static List<URL> providersOf(String resource) throws IOException {
		return Collections.list(JLineSingleProviderTest.class.getClassLoader().getResources(resource));
	}

	/**
	 * The artifact a class comes from — the part of the URL before the {@code !}, since
	 * the part after it is the class's own path and would make three lookups of three
	 * different classes always look like three different sources.
	 */
	private static String jarOf(String resource) {
		try {
			List<URL> urls = providersOf(resource);
			if (urls.isEmpty()) {
				return "<absent>";
			}
			String url = urls.get(0).toString();
			int separator = url.indexOf('!');
			return (separator >= 0) ? url.substring(0, separator) : url;
		}
		catch (IOException ex) {
			throw new AssertionError("could not enumerate " + resource, ex);
		}
	}

}
