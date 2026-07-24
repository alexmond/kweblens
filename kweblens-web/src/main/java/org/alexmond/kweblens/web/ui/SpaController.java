package org.alexmond.kweblens.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React SPA entry point. The built app lives on the classpath under
 * {@code static/ui} (from the {@code kweblens-ui} module) with a Vite base of
 * {@code /ui/}, so its assets are served directly by the static-resource handler; this
 * controller just forwards the bare {@code /ui} routes to {@code index.html}. The
 * Thymeleaf dashboard continues to serve {@code /} unchanged.
 */
@Controller
public class SpaController {

	@GetMapping({ "/ui", "/ui/" })
	public String spa() {
		return "forward:/ui/index.html";
	}

}
