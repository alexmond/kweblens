package org.alexmond.kweblens.web.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Materialises the CSRF token at the start of each request, before the view is rendered.
 *
 * <p>
 * Spring Security defers loading the CSRF token until something first reads it — for
 * server-rendered forms that is Thymeleaf's {@code th:action} processor, which runs deep
 * inside view rendering. Our shell (cluster rail + full category nav) is large enough to
 * fill and flush Tomcat's response buffer before the write-action forms render, so by the
 * time the token is materialised the response is already committed and
 * {@code HttpSessionCsrfTokenRepository} can no longer create the session to store it —
 * throwing {@code IllegalStateException: Cannot create a session after the response has
 * been committed}. Reading the token here forces that session creation up front, while
 * the response is still uncommitted.
 */
public class CsrfTokenEagerFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		if (token != null) {
			// Force the deferred token (and thus its backing session) to load now.
			token.getToken();
		}
		chain.doFilter(request, response);
	}

}
