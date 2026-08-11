package org.alexmond.kweblens.web.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the SPA's sign-in check the password that was typed (#320).
 *
 * <p>
 * {@code BasicAuthenticationFilter} skips authentication entirely when the context
 * already loaded from the session names the same user ({@code authenticationIsRequired}
 * returns false), so a request carrying both a session cookie and a WRONG Basic header
 * was answered {@code 200} without the password ever being verified. The SPA decides
 * sign-in success from that response, which made every password correct for the life of a
 * session.
 *
 * <p>
 * So: when a request to the sign-in endpoint PRESENTS credentials, drop the context the
 * session supplied, leaving {@code BasicAuthenticationFilter} no choice but to verify
 * them. A request that presents none is untouched — the cookie is a credential in its own
 * right, and the SPA's startup restore relies on exactly that.
 *
 * <p>
 * Scoped to the sign-in endpoint on purpose. It is the only request whose answer is read
 * as "are these credentials good?"; everywhere else a session-authenticated request is
 * doing work, and re-running bcrypt on each of them would buy nothing.
 */
final class PresentedCredentialsFilter extends OncePerRequestFilter {

	/** The SPA's session endpoint: {@code POST} signs in, {@code DELETE} signs out. */
	static final String SESSION_PATH = "/api/v1/auth/session";

	private static final RequestMatcher SIGN_IN = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, SESSION_PATH);

	private static final String BASIC = "Basic ";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (SIGN_IN.matches(request) && presentsCredentials(request)) {
			SecurityContextHolder.clearContext();
		}
		chain.doFilter(request, response);
	}

	private boolean presentsCredentials(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		return StringUtils.startsWithIgnoreCase(header, BASIC);
	}

}
