package org.alexmond.kweblens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Open-mode (the default): reads are public, writes require authentication.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.security.admin-password=secret" })
class SecurityGateTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test
	void readsArePublic() throws Exception {
		mvc.perform(get("/api/v1/clusters")).andExpect(status().isOk());
	}

	@Test
	void writesRequireAuth() throws Exception {
		mvc.perform(post("/api/v1/clusters")).andExpect(status().isUnauthorized());
	}

	@Test
	void writesPassSecurityWithValidCredentials() throws Exception {
		// Authenticated -> gets past the security filter; POST /api/v1/clusters (add a
		// cluster) then rejects the empty body as a bad request, which is what proves the
		// request reached the controller rather than being stopped at the filter chain.
		mvc.perform(post("/api/v1/clusters").with(httpBasic("admin", "secret"))).andExpect(status().isBadRequest());
	}

	@Test
	void podExecRequiresAuthEvenInOpenMode() throws Exception {
		// The /ws exec handshake is a GET, but exec is privileged — it must not be
		// public.
		mvc.perform(get("/ws/exec")).andExpect(status().isUnauthorized());
	}

	@Test
	void helmValuesRequireAuthEvenInOpenMode() throws Exception {
		// Helm values commonly carry plaintext secrets, so these GETs must not be public
		// even though open-mode permits reads elsewhere.
		mvc.perform(get("/api/v1/helm/values")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/helm/values/anything")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/clusters/default/helm/releases/ns/name/values")).andExpect(status().isUnauthorized());
	}

	@Test
	void diagnosisReadsArePublicButAnalysingIsNot() throws Exception {
		// Reading the diagnosis is deterministic and free, so it stays on the public read
		// path. Asking for the LLM summary spends money and ships cluster state to an
		// inference provider, so it is a non-GET and lands behind the admin login (#251).
		mvc.perform(get("/api/v1/clusters/default/diagnose")).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/clusters/default/diagnose/summary")).andExpect(status().isUnauthorized());
	}

	@Test
	void helmReposRemainPublicInOpenMode() throws Exception {
		// Repo names/URLs aren't secret — the repos list stays a public read in
		// open-mode.
		mvc.perform(get("/api/v1/helm/repos")).andExpect(status().isOk());
	}

}
