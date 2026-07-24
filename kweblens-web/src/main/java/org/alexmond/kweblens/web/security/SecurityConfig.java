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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
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
		http.authorizeHttpRequests((auth) -> {
			auth.requestMatchers("/actuator/health/**", "/actuator/info", "/login", "/error", "/css/**", "/webjars/**")
				.permitAll();
			if (properties.isOpenMode()) {
				auth.requestMatchers(HttpMethod.GET, "/**").permitAll();
			}
			auth.anyRequest().authenticated();
		})
			.formLogin(Customizer.withDefaults())
			.httpBasic(Customizer.withDefaults())
			.csrf((csrf) -> csrf.ignoringRequestMatchers("/api/**"))
			// The JSON API answers unauthenticated calls with 401 instead of a login
			// redirect.
			.exceptionHandling(
					(ex) -> ex.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
							PathPatternRequestMatcher.pathPattern("/api/**")))
			.headers((headers) -> headers.frameOptions((frame) -> frame.sameOrigin()));
		return http.build();
	}

	private String generatePassword() {
		String password = UUID.randomUUID().toString();
		log.warn("No kweblens.security.admin-password set — generated one for this run: {}", password);
		return password;
	}

}
