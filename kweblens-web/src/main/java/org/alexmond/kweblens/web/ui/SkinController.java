package org.alexmond.kweblens.web.ui;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Persists the chosen UI skin in the {@code kw-skin} cookie and returns to the page the
 * user switched from. The skin is purely presentational, so a plain GET link (no CSRF) is
 * fine; the {@code returnTo} target is constrained to a local path to avoid an open
 * redirect.
 */
@Controller
public class SkinController {

	private static final int ONE_YEAR = 60 * 60 * 24 * 365;

	@GetMapping("/skin/{id}")
	public String select(@PathVariable String id, @RequestParam(defaultValue = "/") String returnTo,
			HttpServletResponse response) {
		Cookie cookie = new Cookie(SkinInterceptor.COOKIE, Skin.fromId(id).id());
		cookie.setPath("/");
		cookie.setMaxAge(ONE_YEAR);
		cookie.setHttpOnly(true);
		response.addCookie(cookie);
		return "redirect:" + localPath(returnTo);
	}

	/** Only allow same-site absolute paths ({@code /...}) as the return target. */
	private String localPath(String returnTo) {
		if (returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//")) {
			return returnTo;
		}
		return "/";
	}

}
