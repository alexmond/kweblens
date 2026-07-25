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
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {

	@PostMapping("/session")
	public Map<String, String> session(Principal principal) {
		return Map.of("user", (principal != null) ? principal.getName() : "");
	}

}
