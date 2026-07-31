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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pod file browser's security posture, with everything at its defaults.
 *
 * <p>
 * Two things are asserted, and both are load-bearing. First, its GETs are
 * <strong>not</strong> public in open-mode the way the rest of the read API is — a
 * container's disk holds mounted Secrets and the service-account token, so an
 * unauthenticated read here would be an unauthenticated secret download. Second, the
 * feature is <strong>off</strong> until an operator turns it on, which is what ADR-001's
 * sign-off requires while kweblens runs as a single shared identity.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.security.admin-password=secret" })
class PodFilesSecurityTest {

	private static final String BASE = "/api/v1/clusters/test/pods/default/mypod/files";

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test
	void fileReadsAreNeverPublicEvenInOpenMode() throws Exception {
		mvc.perform(get(BASE).param("path", "/")).andExpect(status().isUnauthorized());
		mvc.perform(get(BASE + "/content").param("path", "/etc/passwd")).andExpect(status().isUnauthorized());
		mvc.perform(get(BASE + "/download").param("path", "/var/run/secrets/kubernetes.io/serviceaccount/token"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void mutationsRequireAuthentication() throws Exception {
		mvc.perform(delete(BASE).param("path", "/etc/passwd")).andExpect(status().isUnauthorized());
	}

	@Test
	void theFeatureIsOffByDefaultEvenForTheAdmin() throws Exception {
		mvc.perform(get(BASE).with(httpBasic("admin", "secret")).param("path", "/"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("files-disabled"));
	}

}
