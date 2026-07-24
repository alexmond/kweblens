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
		// Authenticated -> gets past the security filter; the GET-only endpoint then
		// answers 405.
		mvc.perform(post("/api/v1/clusters").with(httpBasic("admin", "secret")))
			.andExpect(status().isMethodNotAllowed());
	}

}
