package org.alexmond.kweblens.column;

import java.util.function.Function;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * One kind-specific column: a stable key, a header, and the string it puts in a cell.
 *
 * <p>
 * <b>A value, not a renderer.</b> The whole point of computing these server-side is that
 * a consumer receives {@code "2/3"} rather than the recipe for it, so a terminal, a
 * browser and an assistant cannot each arrive at a different {@code "2/3"}. What a
 * surface still owns is presentation — width, order, whether the cell is a bar or a word
 * — and none of that is here, deliberately: a terminal's widths come from its own
 * geometry and the SPA's rich cells are keyed off {@link #key()}, which is why the key is
 * the same string on both sides.
 *
 * @param key the SPA's column key, e.g. {@code ready}; the join between a value computed
 * here and a cell rendered anywhere
 * @param header the column heading in title case, e.g. {@code Up-to-date}
 * @param value the cell text for one object; never null, {@link ColumnText#MISSING} when
 * the object carries nothing
 */
public record Column(String key, String header, Function<GenericKubernetesResource, String> value) {

	/**
	 * A column that is one dotted path and nothing else — the 35 of the SPA's 90 entries
	 * that have no logic in them at all.
	 * @param key the column key
	 * @param header the column heading
	 * @param path the dotted path, e.g. {@code status.nodeInfo.kubeletVersion}
	 * @return the column
	 */
	public static Column path(String key, String header, String path) {
		return new Column(key, header, (object) -> ColumnText.text(ObjectPath.read(object, path)));
	}

	/** The cell text for {@code object}. */
	public String render(GenericKubernetesResource object) {
		return this.value.apply(object);
	}

}
