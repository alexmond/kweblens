package org.alexmond.kweblens.web.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import org.alexmond.kweblens.web.ai.DeterministicDiagnosis;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A tool call must never be able to buy an inference.</b>
 *
 * <p>
 * {@code DiagnoseService.analyse} is the only method in kweblens that calls an LLM. It is
 * a non-GET, authenticated in both security modes and audited, because it costs money and
 * ships cluster state to a third party — and every one of those protections is about a
 * <i>person</i> choosing to spend. The MCP surface is the one place where the caller is a
 * model, so "no tool calls it today" is not a property worth having; the property worth
 * having is that no tool <i>can</i>.
 *
 * <p>
 * The mechanism is {@link DeterministicDiagnosis}: a port with no {@code analyse} method,
 * which is what {@code HealthTools} injects. That is the same enforcement
 * {@code kweblens-tui}'s read-only {@code ClusterDataSource} uses — a type in which the
 * call does not compile beats a comment asking people not to make it.
 *
 * <p>
 * This test is the part an interface cannot do by itself, because an interface only
 * constrains the field somebody declared. It fails the build if a second field appears:
 * no shipped class in {@code web/mcp} may so much as mention {@code DiagnoseService} or a
 * chat client, so a downcast, a direct injection or an autowired {@code ChatClient} shows
 * up here rather than in a review.
 */
class McpToolsNeverCallAModelTest {

	private static final String TOOL_PACKAGE = "org/alexmond/kweblens/web/mcp/";

	/** The class that owns {@code analyse}, and therefore the only route to a model. */
	private static final String DIAGNOSE_SERVICE = "org/alexmond/kweblens/web/ai/DiagnoseService";

	/** Spring AI's conversation API. {@code ai/tool/} is the annotation and is fine. */
	private static final String CHAT_CLIENT = "org/springframework/ai/chat/";

	@Test
	void noToolClassCanEvenNameTheClassThatCallsAModel() throws IOException {
		assertThat(referrers(TOOL_PACKAGE, DIAGNOSE_SERVICE)).as("""
				An MCP tool class references DiagnoseService, which owns analyse() — the \
				one method in kweblens that calls an LLM, gated behind a non-GET, \
				authentication and an audit entry because a person has to choose to spend \
				the money. Inject DeterministicDiagnosis instead; it has no analyse to \
				call.""").isEmpty();
	}

	@Test
	void noToolClassHoldsAChatClient() throws IOException {
		assertThat(referrers(TOOL_PACKAGE, CHAT_CLIENT))
			.as("An MCP tool class references Spring AI's chat client. A tool is driven by a model; "
					+ "it may not call one.")
			.isEmpty();
	}

	/**
	 * The port cannot grow the method quietly. One method, and it is the read: an
	 * {@code analyse} added here would be reachable from every tool at once, and would
	 * look like a small change to an interface.
	 */
	@Test
	void theReadPortDeclaresNothingButTheRead() {
		List<String> methods = new ArrayList<>();
		for (Method method : DeterministicDiagnosis.class.getDeclaredMethods()) {
			methods.add(method.getName());
		}

		assertThat(methods).as("DeterministicDiagnosis is the shape of the guarantee, not a convenience interface")
			.containsExactly("diagnose");
	}

	/**
	 * Positive control. A scanner that read no classes, or matched no bytes, would report
	 * both packages clean and pass this test for the wrong reason — so prove it can see a
	 * reference in the two classes that certainly have one.
	 */
	@Test
	void theScanCanSeeAReferenceWhereOneIsKnownToExist() throws IOException {
		assertThat(referrers("org/alexmond/kweblens/web/api/", DIAGNOSE_SERVICE))
			.as("the auth-gated controller is the sanctioned caller and must still be visible to this scan")
			.contains("org/alexmond/kweblens/web/api/DiagnoseApiController.class");
		assertThat(referrers("org/alexmond/kweblens/web/ai/", CHAT_CLIENT))
			.contains("org/alexmond/kweblens/web/ai/DiagnoseService.class");
	}

	/** Shipped classes under {@code inPackage} whose bytes mention {@code marker}. */
	private static List<String> referrers(String inPackage, String marker) throws IOException {
		List<String> found = new ArrayList<>();
		Resource[] classes = new PathMatchingResourcePatternResolver()
			.getResources("classpath*:" + inPackage + "*.class");
		assertThat(classes).as("no compiled classes found under %s; the scan did not run", inPackage).isNotEmpty();
		for (Resource resource : classes) {
			String location = resource.getURL().toString();
			// Test classes are not shipped, and this file itself names both markers.
			if (location.contains("test-classes")) {
				continue;
			}
			try (InputStream in = resource.getInputStream()) {
				String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
				if (bytes.contains(marker)) {
					found.add(location.substring(location.indexOf(inPackage)));
				}
			}
		}
		return found;
	}

}
