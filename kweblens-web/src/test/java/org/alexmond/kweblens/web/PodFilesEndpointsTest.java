package org.alexmond.kweblens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The file browser turned on, exercised through HTTP. Security is covered by
 * {@link PodFilesSecurityTest}; this MockMvc omits the filter chain so the request
 * validation itself is what is being asserted — every case here is rejected before any
 * command is sent to a container, which is why no cluster is registered.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.files.enabled=true",
		"kweblens.files.max-write-bytes=16" })
class PodFilesEndpointsTest {

	private static final String BASE = "/api/v1/clusters/test/pods/default/mypod/files";

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	void rejectsPathTraversal() throws Exception {
		mvc.perform(get(BASE).param("path", "/../../etc/shadow"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid-file-path"));
	}

	@Test
	void rejectsARelativePath() throws Exception {
		mvc.perform(get(BASE + "/content").param("path", "etc/passwd"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid-file-path"));
	}

	@Test
	void rejectsAWriteThatDoesNotSayHowItIsEncoded() throws Exception {
		mvc.perform(put(BASE + "/content").param("path", "/tmp/x")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"hi\",\"base64\":\"aGk=\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid-file-content"));
	}

	@Test
	void refusesAWriteOverTheConfiguredCap() throws Exception {
		String oversized = "x".repeat(64);
		mvc.perform(put(BASE + "/content").param("path", "/tmp/x")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"" + oversized + "\"}"))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("file-too-large"));
	}

}
