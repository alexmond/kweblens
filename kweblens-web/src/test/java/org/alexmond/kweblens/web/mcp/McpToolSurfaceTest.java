package org.alexmond.kweblens.web.mcp;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the MCP surface actually offers, and what the documentation says it offers.
 *
 * <p>
 * <b>The count on its own would be the weak version of this test</b> — it passes the day
 * someone adds a tool and forgets to wire the bean, because the number and the wiring are
 * different facts. So the first assertion is structural: collect every {@code @Tool}
 * method on every shipped class in {@code web/mcp}, ask the registered
 * {@link ToolCallbackProvider} what it will actually serve, and require the two sets to
 * be <em>equal</em>. A tool added to a bean {@code McpConfig} does not name is then a
 * build failure that says which one, rather than a method nobody can call.
 *
 * <p>
 * The count still matters, and it is checked where it is claimed rather than in a
 * constant. That number is on the README's feature table, in the deployment guide and on
 * three documentation pages; it is the first thing a reader checks and the last thing
 * anybody remembers to update — #383 is the ticket where it went stale. So the docs are
 * read from the checkout and required to agree with the surface that was just enumerated.
 *
 * <p>
 * <b>Only live documents are listed.</b> {@code docs/design/roadmap.md}'s history,
 * {@code CHANGELOG.adoc} and the audit and research notes also state a count, and theirs
 * is deliberately the number that was true when they were written. Rewriting those would
 * turn a dated record into a lie about the past.
 * {@link #everyListedDocumentStatesTheCount} is the other half of that judgement: a
 * listed file that stops stating the count is a failure, so the list cannot quietly claim
 * coverage it does not have.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
class McpToolSurfaceTest {

	/** Where the tool beans live; nothing outside it contributes to the surface. */
	private static final String TOOL_PACKAGE = "org/alexmond/kweblens/web/mcp/";

	/**
	 * Documents whose stated tool count is a claim about the current build. Dated
	 * snapshots are deliberately absent — see this class's javadoc.
	 */
	private static final List<String> LIVE_DOCUMENTS = List.of("README.md", "CLAUDE.md", "docs/deployment.md",
			"docs/modules/ROOT/pages/mcp.adoc", "docs/modules/ROOT/pages/index.adoc",
			"docs/modules/ROOT/pages/attach-an-agent.adoc");

	/**
	 * "16 read-only tools", "All 16 tools", "the 16 tools". Matched over the whole file
	 * rather than line by line, because {@code docs/deployment.md} wraps the phrase
	 * across a newline — a line-based search reports that file as stating nothing at all,
	 * which is how it was missed while this ticket was being scoped.
	 */
	private static final Pattern STATED_COUNT = Pattern.compile("(\\d+)\\s+(?:read-only\\s+)?tools?\\b");

	@Autowired
	private ToolCallbackProvider provider;

	@Test
	void everyToolMethodInThePackageIsActuallyRegistered() {
		Set<String> declared = declaredToolNames();
		Set<String> registered = registeredToolNames();

		assertThat(registered).as("""
				The registered MCP surface is not the set of @Tool methods in web/mcp. \
				A tool that is declared but not registered is unreachable — its bean is \
				probably missing from McpConfig's MethodToolCallbackProvider, which \
				registers the surface in one place on purpose.""").isEqualTo(declared);
	}

	/**
	 * The positive control. A scan that quietly enumerated nothing would make the
	 * equality above pass on two empty sets, which is the one way this test could report
	 * green on a surface that does not exist.
	 */
	@Test
	void theScanFindsTheToolsThatAreKnownToBeThere() {
		Set<String> declared = declaredToolNames();

		assertThat(declared).hasSizeGreaterThan(10)
			// One from each of the three beans, so a scan that missed a whole class shows
			// up here rather than as a plausible-looking smaller number.
			.contains("listResourceKinds", "getPodLogs", "checkSecurity", "diagnose");
		assertThat(registeredToolNames()).contains("diagnose");
	}

	@Test
	void everyLiveDocumentStatesTheRegisteredCount() throws IOException {
		String expected = String.valueOf(registeredToolNames().size());
		Path root = repoRoot();
		List<String> wrong = new ArrayList<>();
		for (String document : LIVE_DOCUMENTS) {
			Matcher matcher = STATED_COUNT.matcher(read(root, document));
			while (matcher.find()) {
				if (!expected.equals(matcher.group(1))) {
					wrong.add(document + ": \"" + matcher.group().replace('\n', ' ') + "\"");
				}
			}
		}
		assertThat(wrong).as("""
				The MCP tool count is wrong where it is stated. It is registered as %s. \
				That number is what a reader checks before trusting anything else on the \
				page, so a stale one is worse than none — update every occurrence.""", expected).isEmpty();
	}

	/**
	 * A listed document that no longer states the count is not "fine, one fewer place to
	 * update": it means this list is describing a repository that has moved, and the next
	 * file to state a count will not be in it either.
	 */
	@Test
	void everyListedDocumentStatesTheCount() throws IOException {
		Path root = repoRoot();
		for (String document : LIVE_DOCUMENTS) {
			assertThat(STATED_COUNT.matcher(read(root, document)).find())
				.as("%s no longer states the MCP tool count, so listing it here claims a check that is not happening",
						document)
				.isTrue();
		}
	}

	/** Tool names as the running server will advertise them. */
	private Set<String> registeredToolNames() {
		Set<String> names = new TreeSet<>();
		for (ToolCallback callback : this.provider.getToolCallbacks()) {
			names.add(callback.getToolDefinition().name());
		}
		return names;
	}

	/**
	 * Every {@code @Tool} method on a shipped class in the package, named the way Spring
	 * AI names it: the annotation's {@code name} when set, otherwise the method's.
	 */
	private static Set<String> declaredToolNames() {
		Set<String> names = new TreeSet<>();
		for (Class<?> type : shippedToolClasses()) {
			for (Method method : type.getMethods()) {
				Tool tool = method.getAnnotation(Tool.class);
				if (tool != null) {
					names.add(tool.name().isBlank() ? method.getName() : tool.name());
				}
			}
		}
		return names;
	}

	/**
	 * Classes read from the compiled output rather than from a hard-coded list, because a
	 * hard-coded list is the thing that goes stale — a new {@code SomethingTools} would
	 * join the surface without this test noticing. Test classes are excluded: they are
	 * not shipped, and this package holds several.
	 */
	private static Set<Class<?>> shippedToolClasses() {
		Set<Class<?>> types = new LinkedHashSet<>();
		try {
			Resource[] classes = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:" + TOOL_PACKAGE + "*.class");
			for (Resource resource : classes) {
				String location = resource.getURL().toString();
				if (location.contains("test-classes")) {
					continue;
				}
				String name = location.substring(location.indexOf(TOOL_PACKAGE))
					.replace(".class", "")
					.replace('/', '.');
				types.add(Class.forName(name));
			}
		}
		catch (IOException | ClassNotFoundException ex) {
			throw new IllegalStateException("could not enumerate the tool package", ex);
		}
		return types;
	}

	private static String read(Path root, String document) throws IOException {
		Path file = root.resolve(document);
		assertThat(file).as("%s is listed here but is not in the checkout", document).exists();
		return Files.readString(file, StandardCharsets.UTF_8);
	}

	/**
	 * The checkout root, asked of git rather than guessed, so this behaves the same under
	 * Surefire, in an IDE and inside a linked worktree — where {@code .git} is a file.
	 * Throws rather than skipping: a gate that opts out is not a gate.
	 */
	private static Path repoRoot() {
		try {
			Process process = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
				.directory(Path.of("").toAbsolutePath().toFile())
				.start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
				process.destroyForcibly();
				throw new IllegalStateException("git rev-parse --show-toplevel failed");
			}
			return Path.of(out.trim());
		}
		catch (IOException ex) {
			throw new IllegalStateException("could not run git", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted running git", ex);
		}
	}

}
