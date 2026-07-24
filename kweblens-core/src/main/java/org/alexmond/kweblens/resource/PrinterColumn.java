package org.alexmond.kweblens.resource;

/**
 * A CRD-declared additional printer column (what {@code kubectl get <cr>} shows). The
 * {@code jsonPath} is a simple dotted path into the object (e.g. {@code .status.phase});
 * the UI evaluates it per object to fill the column.
 *
 * @param name column header
 * @param jsonPath dotted path into the object, leading dot included
 * @param type value type hint ({@code string}, {@code integer}, {@code date},
 * {@code boolean})
 */
public record PrinterColumn(String name, String jsonPath, String type) {
}
