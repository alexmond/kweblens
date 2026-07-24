package org.alexmond.kweblens.web;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.alexmond.kweblens.web.ui.Skin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The skin selector: persists the choice in the {@code kw-skin} cookie and returns to the
 * originating page, refusing to redirect off-site.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class SkinEndpointsTest {

	KubernetesClient client;

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	void selectingASkinSetsTheCookieAndReturnsToThePage() throws Exception {
		mvc.perform(get("/skin/proxmox").param("returnTo", "/clusters/test/r/pods"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/clusters/test/r/pods"))
			.andExpect(cookie().value("kw-skin", "proxmox"));
	}

	@Test
	void unknownSkinFallsBackToDefault() throws Exception {
		mvc.perform(get("/skin/bogus")).andExpect(cookie().value("kw-skin", "vcenter"));
	}

	@Test
	void offSiteReturnTargetIsRejected() throws Exception {
		mvc.perform(get("/skin/dark").param("returnTo", "//evil.example.com"))
			.andExpect(redirectedUrl("/"))
			.andExpect(cookie().value("kw-skin", "dark"));
	}

	@Test
	void skinEnumMapsIdsAndThemes() {
		assertThat(Skin.fromId(null)).isEqualTo(Skin.VCENTER);
		assertThat(Skin.fromId("freelens")).isEqualTo(Skin.FREELENS);
		assertThat(Skin.VCENTER.id()).isEqualTo("vcenter");
		assertThat(Skin.VCENTER.bsTheme()).isEqualTo("light");
		assertThat(Skin.DARK.bsTheme()).isEqualTo("dark");
		assertThat(Skin.PROXMOX.label()).isEqualTo("Proxmox");
	}

	@Test
	void cookieIsScopedAndLongLived() throws Exception {
		Cookie set = mvc.perform(get("/skin/vcenter")).andReturn().getResponse().getCookie("kw-skin");
		assertThat(set).isNotNull();
		assertThat(set.getPath()).isEqualTo("/");
		assertThat(set.getMaxAge()).isPositive();
	}

}
