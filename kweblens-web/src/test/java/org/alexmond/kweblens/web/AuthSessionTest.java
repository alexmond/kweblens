package org.alexmond.kweblens.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sign-in / sign-out lifecycle of the SPA's session (#320).
 *
 * <p>
 * The session is not decorative — the exec WebSocket authenticates from it, because a
 * browser cannot attach Basic credentials to a WebSocket handshake. That makes two things
 * load-bearing and neither used to hold: signing out has to end the session on the
 * SERVER, and signing in has to check the password that was typed rather than the cookie
 * the request happened to carry.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.security.admin-password=secret" })
class AuthSessionTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	/** Sign in, and hand back the session the browser would now be holding. */
	private MockHttpSession signIn() throws Exception {
		MockHttpSession session = (MockHttpSession) mvc
			.perform(post("/api/v1/auth/session").with(httpBasic("admin", "secret")))
			.andExpect(status().isOk())
			.andReturn()
			.getRequest()
			.getSession(false);
		assertThat(session).isNotNull();
		return session;
	}

	@Test
	void signInEstablishesASession() throws Exception {
		signIn();
	}

	@Test
	void signOutInvalidatesTheServerSession() throws Exception {
		MockHttpSession session = signIn();
		mvc.perform(delete("/api/v1/auth/session").session(session)).andExpect(status().isNoContent());
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	void signOutNeedsNoCredentialsOfItsOwn() throws Exception {
		// Sign-out runs in the LogoutFilter, ahead of authorization, so a client whose
		// credentials have already gone (an expired login, a reloaded tab) can still
		// end the session, rather than be told 401 by the endpoint that would fix it.
		mvc.perform(delete("/api/v1/auth/session")).andExpect(status().isNoContent());
	}

	@Test
	void aWrongPasswordIsRejectedWhileTheSessionCookieIsStillValid() throws Exception {
		// The defect: BasicAuthenticationFilter skips validating credentials whose
		// username matches an already-authenticated context, so a session cookie made
		// every password correct. The SPA decides sign-in success from this response,
		// so "Sign out" plus any password at all signed you straight back in.
		MockHttpSession session = signIn();
		mvc.perform(post("/api/v1/auth/session").session(session).with(httpBasic("admin", "wrong")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void aCorrectPasswordStillPassesWhileTheSessionCookieIsValid() throws Exception {
		MockHttpSession session = signIn();
		mvc.perform(post("/api/v1/auth/session").session(session).with(httpBasic("admin", "secret")))
			.andExpect(status().isOk());
	}

	@Test
	void anExistingSessionAloneStillRestoresTheSignedInUser() throws Exception {
		// No credentials on the request: this is the SPA's startup restore, and the
		// cookie is a credential in its own right. Only a request that PRESENTS a
		// password gets that password checked.
		MockHttpSession session = signIn();
		mvc.perform(post("/api/v1/auth/session").session(session)).andExpect(status().isOk());
	}

}
