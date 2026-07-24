package org.alexmond.kweblens.web.security;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication settings for kweblens.
 *
 * <p>
 * {@code open-mode} keeps read (GET) endpoints public so a freshly-started server and CI
 * are usable, while writes (apply, exec, Helm actions) always require authentication.
 * Real deployments should set {@code kweblens.security.open-mode=false} to require auth
 * for everything.
 */
@Data
@ConfigurationProperties(prefix = "kweblens.security")
public class SecurityProperties {

	/** When true (default), GET endpoints are public; writes still require auth. */
	private boolean openMode = true;

	/** The single built-in admin username. */
	private String adminUsername = "admin";

	/**
	 * The admin password. When blank, a random one is generated and logged at startup.
	 */
	private String adminPassword;

}
