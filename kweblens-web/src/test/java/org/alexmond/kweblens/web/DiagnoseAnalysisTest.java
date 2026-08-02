package org.alexmond.kweblens.web;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.web.ai.DiagnosisSummaryCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The manual-trigger contract for AI analysis (#251), asserted against a counting chat
 * model so "did it call the LLM" is a number rather than an opinion.
 *
 * <p>
 * AI is switched ON for this whole class — that is the point. The regression it guards is
 * a fully configured instance where opening the panel, or flipping the namespace filter,
 * used to buy an inference call each. Every assertion here is about a server that COULD
 * call the model and must not until asked.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.ai.enabled=true",
		"kweblens.security.admin-password=secret" })
@EnableKubernetesMockClient(crud = true)
@Import(DiagnoseAnalysisTest.StubChatConfig.class)
class DiagnoseAnalysisTest {

	KubernetesClient client;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private DiagnosisSummaryCache summaries;

	@Autowired
	private CountingChatModel model;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
		// Both are process-wide singletons shared by every method in the class, so a test
		// that did not reset them would be asserting the previous test's leftovers.
		this.summaries.clear();
		this.model.calls().set(0);
		this.registry.register("test", "Test cluster", this.client);
		this.client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName("web").endMetadata().build())
			.create();
		brokenPod("bad");
	}

	private void brokenPod(String name) {
		this.client.pods()
			.resource(new PodBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("web")
				.endMetadata()
				.withNewStatus()
				.withPhase("Pending")
				.addNewContainerStatus()
				.withName("c1")
				.withNewState()
				.withNewWaiting()
				.withReason("CrashLoopBackOff")
				.withMessage("back-off restarting failed container")
				.endWaiting()
				.endState()
				.endContainerStatus()
				.endStatus()
				.build())
			.create();
	}

	@Test
	void readingTheDiagnosisNeverCallsTheModel() throws Exception {
		for (int i = 0; i < 3; i++) {
			this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.findings[?(@.severity=='critical')]").exists())
				.andExpect(jsonPath("$.summary").doesNotExist())
				.andExpect(jsonPath("$.aiEnriched").value(false))
				// The trigger is offered — this instance CAN analyse, it just has not.
				.andExpect(jsonPath("$.aiAvailable").value(true))
				.andExpect(jsonPath("$.summaryOutdated").value(false));
		}
		assertThat(this.model.calls()).hasValue(0);
	}

	@Test
	void analysingRequiresAuthEvenInOpenMode() throws Exception {
		this.mvc.perform(post("/api/v1/clusters/test/diagnose/summary").param("namespace", "web"))
			.andExpect(status().isUnauthorized());
		assertThat(this.model.calls()).hasValue(0);
	}

	@Test
	void analysingProducesASummaryAndLaterReadsServeItFromCache() throws Exception {
		this.mvc
			.perform(post("/api/v1/clusters/test/diagnose/summary").param("namespace", "web")
				.with(httpBasic("admin", "secret")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aiEnriched").value(true))
			.andExpect(jsonPath("$.summary").value(CountingChatModel.ANSWER))
			.andExpect(jsonPath("$.analysedAt").exists());
		assertThat(this.model.calls()).hasValue(1);

		// Two more reads: same summary, still one inference call in total.
		for (int i = 0; i < 2; i++) {
			this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.aiEnriched").value(true))
				.andExpect(jsonPath("$.summary").value(CountingChatModel.ANSWER))
				.andExpect(jsonPath("$.analysedAt").exists());
		}
		assertThat(this.model.calls()).hasValue(1);
	}

	@Test
	void aChangedClusterInvalidatesTheCachedSummary() throws Exception {
		this.mvc
			.perform(post("/api/v1/clusters/test/diagnose/summary").param("namespace", "web")
				.with(httpBasic("admin", "secret")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aiEnriched").value(true));

		// A second broken pod is a new finding, so the summary was written about a
		// cluster
		// that no longer exists. It must be withheld rather than shown as current — and
		// withheld WITH a reason, so the panel can say why it is asking again.
		brokenPod("worse");
		this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "web"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary").doesNotExist())
			.andExpect(jsonPath("$.aiEnriched").value(false))
			.andExpect(jsonPath("$.summaryOutdated").value(true));
		assertThat(this.model.calls()).hasValue(1);
	}

	@Test
	void namespaceIsPartOfTheKeySoOneScopeCannotAnswerForAnother() throws Exception {
		this.mvc
			.perform(post("/api/v1/clusters/test/diagnose/summary").param("namespace", "web")
				.with(httpBasic("admin", "secret")))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/clusters/test/diagnose").param("namespace", "other"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summary").doesNotExist())
			.andExpect(jsonPath("$.summaryOutdated").value(false));
		assertThat(this.model.calls()).hasValue(1);
	}

	/**
	 * A chat model that answers instantly and, crucially, counts how often it was asked.
	 */
	static final class CountingChatModel implements ChatModel {

		static final String ANSWER = "Fix Pod/bad first; its container is crash-looping.";

		private final AtomicInteger calls = new AtomicInteger();

		AtomicInteger calls() {
			return this.calls;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.calls.incrementAndGet();
			return new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER))));
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class StubChatConfig {

		@Bean
		CountingChatModel countingChatModel() {
			return new CountingChatModel();
		}

		@Bean
		ChatClient.Builder chatClientBuilder(CountingChatModel model) {
			return ChatClient.builder(model);
		}

	}

}
