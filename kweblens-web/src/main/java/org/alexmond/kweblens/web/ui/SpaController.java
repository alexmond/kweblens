package org.alexmond.kweblens.web.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Vue SPA, which is now the only UI. The built app lives on the classpath
 * under {@code static/ui} (from the {@code kweblens-ui} module) with a Vite base of
 * {@code /ui/}, so its assets are served directly by the static-resource handler; this
 * controller forwards the bare {@code /ui} routes — and the site root, since the
 * Thymeleaf dashboard that used to own {@code /} is gone — to {@code index.html}.
 */
@Controller
public class SpaController {

	@GetMapping({ "/", "/ui", "/ui/" })
	public String spa() {
		return "forward:/ui/index.html";
	}

}
