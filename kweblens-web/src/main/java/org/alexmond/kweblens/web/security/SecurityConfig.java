package org.alexmond.kweblens.web.security;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.util.StringUtils;

/**
 * Authentication for kweblens. In {@code open-mode} (the default) read (GET) endpoints
 * are public so the dashboard and CI work out of the box, while every write — YAML apply,
 * pod exec, Helm actions, anything non-GET — requires the admin login. With
 * {@code open-mode=false} all endpoints (except health and the login page) require
 * authentication.
 *
 * <p>
 * kweblens exposes cluster data and mutating actions, so production MUST set
 * {@code kweblens.security.open-mode=false} and put a real identity provider in front.
 */
@Slf4j
@Configuration
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public UserDetailsService userDetailsService(SecurityProperties properties, PasswordEncoder encoder) {
		String password = StringUtils.hasText(properties.getAdminPassword()) ? properties.getAdminPassword()
				: generatePassword();
		UserDetails admin = User.withUsername(properties.getAdminUsername())
			.password(encoder.encode(password))
			.roles("ADMIN")
			.build();
		return new InMemoryUserDetailsManager(admin);
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, SecurityProperties properties) throws Exception {
		// Persist the authenticated context to the HttpSession as well as the request so
		// the
		// SPA's one-shot Basic login (POST /api/v1/auth/session) establishes a JSESSIONID
		// the
		// same-origin exec WebSocket can ride — the browser cannot attach Basic to a WS
		// handshake. Stateless Basic on every other API call keeps working unchanged.
		SecurityContextRepository contextRepository = new DelegatingSecurityContextRepository(
				new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
		http.authorizeHttpRequests((auth) -> {
			auth.requestMatchers("/actuator/health/**", "/actuator/info", "/login", "/error", "/", "/ui", "/ui/**")
				.permitAll();
			// Pod exec is privileged: the WebSocket handshake always requires auth, even
			// in open-mode.
			auth.requestMatchers("/ws/**").authenticated();
			// The pod file browser reads arbitrary files out of a container, including
			// projected Secret volumes and the service-account token. Letting those GETs
			// ride open-mode's public read path would make secret exfiltration an
			// UNAUTHENTICATED operation, so the whole family is authenticated — reads
			// included. (The feature is also off unless kweblens.files.enabled=true.)
			auth.requestMatchers("/api/v1/clusters/*/pods/*/*/files", "/api/v1/clusters/*/pods/*/*/files/**")
				.authenticated();
			// Helm values — a release's stored config and the saved values-file library —
			// commonly carry plaintext secrets and are returned raw (unlike the masked
			// Secret drawer), so they always require auth, even in open-mode.
			auth.requestMatchers(HttpMethod.GET, "/api/v1/helm/values", "/api/v1/helm/values/**",
					"/api/v1/clusters/*/helm/releases/*/*/values")
				.authenticated();
			if (properties.isOpenMode()) {
				auth.requestMatchers(HttpMethod.GET, "/**").permitAll();
			}
			auth.anyRequest().authenticated();
		})
			.securityContext((sc) -> sc.securityContextRepository(contextRepository))
			.formLogin(Customizer.withDefaults())
			// Answer failed Basic auth with a bare 401 (no WWW-Authenticate) so the
			// browser
			// does not hijack the SPA's sign-in fetch with its native Basic prompt; the
			// SPA
			// then shows its own "Invalid credentials" instead of freezing.
			.httpBasic((basic) -> basic.securityContextRepository(contextRepository)
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			// CSRF guards cookie-authenticated BROWSER form posts. The JSON API and the
			// MCP
			// endpoint are neither: they are called by non-browser clients that
			// authenticate
			// per request, and they cannot obtain a token. Without /mcp/** here every
			// tool
			// call is rejected 403 — the MCP server accepts the SSE handshake and then
			// refuses every JSON-RPC message sent back to it, so the tool surface exists
			// but
			// is unreachable.
			.csrf((csrf) -> csrf.ignoringRequestMatchers("/api/**", "/mcp/**"))
			// Sign out has to be a SERVER event (#320). Dropping the SPA's in-memory
			// credentials leaves the JSESSIONID above valid, and that cookie still
			// authorises every write and still opens the exec WebSocket — a tab that
			// says "signed out" while holding a shell is a false assurance. The
			// LogoutFilter runs ahead of authorization, so this ends the session in
			// both modes and needs no credentials; /api/** is already CSRF-exempt.
			.logout((logout) -> logout
				.logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE,
						PresentedCredentialsFilter.SESSION_PATH))
				.invalidateHttpSession(true)
				.clearAuthentication(true)
				.deleteCookies("JSESSIONID")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			// Materialise the CSRF token before view rendering commits the response.
			.addFilterAfter(new CsrfTokenEagerFilter(), CsrfFilter.class)
			// ...and sign IN has to check the password presented, not the cookie the
			// request happened to carry. See PresentedCredentialsFilter.
			.addFilterBefore(new PresentedCredentialsFilter(), BasicAuthenticationFilter.class)
			// The JSON API and WebSocket answer unauthenticated calls with 401, not a
			// redirect.
			.exceptionHandling(
					(ex) -> ex.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
							new OrRequestMatcher(PathPatternRequestMatcher.pathPattern("/api/**"),
									PathPatternRequestMatcher.pathPattern("/ws/**"))))
			.headers((headers) -> headers.frameOptions((frame) -> frame.sameOrigin()));
		return http.build();
	}

	private String generatePassword() {
		String password = UUID.randomUUID().toString();
		log.warn("No kweblens.security.admin-password set — generated one for this run: {}", password);
		return password;
	}

}
