package org.alexmond.kweblens.tui.filter;

/**
 * The fields a {@code name:} / {@code ns:} / {@code namespace:} / {@code kind:} term can
 * address.
 */
enum FilterField {

	NAME, NAMESPACE, KIND;

	String of(FilterRow row) {
		return switch (this) {
			case NAME -> row.name();
			case NAMESPACE -> row.namespace();
			case KIND -> row.kind();
		};
	}

}
