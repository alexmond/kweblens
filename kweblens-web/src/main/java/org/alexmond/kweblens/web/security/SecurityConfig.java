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
			auth.requestMatchers("/actuator/health/**", "/actuator/info", "/login", "/error", "/css/**", "/webjars/**",
					"/ui", "/ui/**")
				.permitAll();
			// Pod exec is privileged: the WebSocket handshake always requires auth, even
			// in open-mode.
			auth.requestMatchers("/ws/**").authenticated();
			if (properties.isOpenMode()) {
				auth.requestMatchers(HttpMethod.GET, "/**").permitAll();
			}
			auth.anyRequest().authenticated();
		})
			.securityContext((sc) -> sc.securityContextRepository(contextRepository))
			.formLogin(Customizer.withDefaults())
			.httpBasic((basic) -> basic.securityContextRepository(contextRepository))
			.csrf((csrf) -> csrf.ignoringRequestMatchers("/api/**"))
			// Materialise the CSRF token before view rendering commits the response.
			.addFilterAfter(new CsrfTokenEagerFilter(), CsrfFilter.class)
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
