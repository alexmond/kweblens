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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With {@code open-mode=false} every endpoint (bar health/login) requires authentication
 * — even reads.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.security.open-mode=false",
		"kweblens.security.admin-password=secret" })
class SecurityClosedModeTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test
	void readsRequireAuthWhenClosed() throws Exception {
		mvc.perform(get("/api/v1/clusters")).andExpect(status().isUnauthorized());
	}

	@Test
	void readsAllowedWithValidCredentials() throws Exception {
		mvc.perform(get("/api/v1/clusters").with(httpBasic("admin", "secret"))).andExpect(status().isOk());
	}

	@Test
	void healthIsPublicEvenWhenClosed() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

}
