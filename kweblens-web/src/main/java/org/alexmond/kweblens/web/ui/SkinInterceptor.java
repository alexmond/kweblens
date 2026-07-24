package org.alexmond.kweblens.web.ui;

import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Exposes the active skin (and the full skin list) to every server-rendered view, so the
 * layout can set {@code data-skin}/{@code data-bs-theme} on {@code <html>} and render the
 * skin picker. Runs only when a view is being rendered — JSON/SSE handlers produce no
 * {@link ModelAndView}, so they are skipped. The choice comes from the {@code kw-skin}
 * cookie, defaulting to {@link Skin#VCENTER}.
 */
public class SkinInterceptor implements HandlerInterceptor {

	static final String COOKIE = "kw-skin";

	@Override
	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
			ModelAndView modelAndView) {
		if (modelAndView == null) {
			return;
		}
		String view = modelAndView.getViewName();
		if (view == null || view.startsWith("redirect:")) {
			return;
		}
		Skin skin = Skin.fromId(readSkinCookie(request));
		var model = modelAndView.getModel();
		model.put("skin", skin.id());
		model.put("skinTheme", skin.bsTheme());
		model.put("skinLabel", skin.label());
		model.put("skins", List.of(Skin.values()));
		model.put("currentUri", currentUri(request));
	}

	private String readSkinCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if (COOKIE.equals(cookie.getName())) {
					return cookie.getValue();
				}
			}
		}
		return null;
	}

	private String currentUri(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String query = request.getQueryString();
		return (query != null) ? uri + "?" + query : uri;
	}

}
