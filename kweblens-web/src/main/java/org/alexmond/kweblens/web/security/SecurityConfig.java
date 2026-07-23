package org.alexmond.kweblens.web.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline security for the scaffold: everything is readable without auth so a
 * freshly-started server is immediately usable, and CSRF is disabled on the JSON API so
 * htmx/tooling can call it.
 *
 * <p>
 * This is the deliberate starting point, not the destination — kweblens exposes cluster
 * data and (later) mutating actions, so real deployments must layer authentication in
 * front. Tighten this as write endpoints land.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests((auth) -> auth.anyRequest().permitAll())
			.csrf((csrf) -> csrf.ignoringRequestMatchers("/api/**"))
			.headers((headers) -> headers.frameOptions((frame) -> frame.sameOrigin()));
		return http.build();
	}

}
