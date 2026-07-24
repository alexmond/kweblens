package org.alexmond.kweblens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.web.security.AuditService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The audit trail surfaces recorded actions at {@code /audit} (page) and
 * {@code /api/v1/audit}.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
class AuditEndpointsTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private AuditService audit;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
		audit.record("prod", "delete", "Pod/web/nginx");
	}

	@Test
	void pageRendersRecordedActions() throws Exception {
		mvc.perform(get("/audit"))
			.andExpect(status().isOk())
			.andExpect(view().name("audit"))
			.andExpect(model().attributeExists("entries"));
	}

	@Test
	void apiReturnsRecordedActions() throws Exception {
		mvc.perform(get("/api/v1/audit"))
			.andExpect(status().isOk())
			.andExpect(
					jsonPath("$[?(@.action=='delete')].target").value(org.hamcrest.Matchers.hasItem("Pod/web/nginx")));
	}

}
