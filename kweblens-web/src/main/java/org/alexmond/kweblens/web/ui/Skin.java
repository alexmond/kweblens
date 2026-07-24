package org.alexmond.kweblens.web.ui;

import java.util.Locale;

/**
 * A selectable UI skin. Each skin maps to a Bootstrap colour mode ({@code data-bs-theme})
 * and a {@code data-skin} value that {@code app.css} keys its chrome tokens (navbar,
 * accent, panel styling) off. The active skin is stored in the {@code kw-skin} cookie and
 * applied on the {@code <html>} element, so switching is a pure client-side restyle with
 * no server state.
 */
public enum Skin {

	/**
	 * VMware vCenter / ESXi look: light, blue accent, dense boxed panels. The default.
	 */
	VCENTER("vCenter", "light"),

	/** Proxmox VE look: light with an amber/orange accent. */
	PROXMOX("Proxmox", "light"),

	/** Freelens look: dark with the blue tile rail. */
	FREELENS("Freelens", "dark"),

	/** A neutral dark skin with no vendor styling. */
	DARK("Dark", "dark");

	private final String label;

	private final String bsTheme;

	Skin(String label, String bsTheme) {
		this.label = label;
		this.bsTheme = bsTheme;
	}

	/**
	 * The stable lower-case id used in the cookie, URL and {@code data-skin} attribute.
	 */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Human-readable name shown in the skin picker. */
	public String label() {
		return this.label;
	}

	/** The Bootstrap colour mode ({@code light} or {@code dark}) this skin renders in. */
	public String bsTheme() {
		return this.bsTheme;
	}

	/** The skin for an id (from cookie/URL), falling back to the default when unknown. */
	public static Skin fromId(String id) {
		if (id != null) {
			for (Skin skin : values()) {
				if (skin.id().equals(id)) {
					return skin;
				}
			}
		}
		return VCENTER;
	}

}
