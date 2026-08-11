package org.alexmond.kweblens.web.api;

import java.security.Principal;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Establishes an authenticated session for the SPA. The SPA signs in with HTTP Basic, but
 * the browser cannot attach Basic credentials to a WebSocket handshake — so it POSTs here
 * once (Basic-authenticated), which validates the credentials and creates the session
 * cookie the same-origin exec WebSocket then rides. Requires auth (it is a POST, so the
 * open-mode "GET is public" rule does not apply).
 *
 * <p>
 * Two neighbours of this method live in {@code web/security} rather than here, because
 * both happen in the filter chain before a controller is reached:
 * {@code PresentedCredentialsFilter} makes a POST that carries a Basic header verify that
 * header instead of riding the session, and the {@code DELETE} of this same path is
 * handled by Spring Security's {@code LogoutFilter} — it invalidates the session (#320).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {

	@PostMapping("/session")
	public Map<String, String> session(Principal principal) {
		return Map.of("user", (principal != null) ? principal.getName() : "");
	}

}
