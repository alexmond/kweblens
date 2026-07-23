package org.alexmond.kweblens.cli;

import java.util.List;

/**
 * Minimal fixed-width table renderer for CLI output — columns padded to the widest cell
 * so the result reads like {@code kubectl get} without pulling in a table library.
 */
public final class TableFormatter {

	private TableFormatter() {
	}

	public static String table(List<String> headers, List<List<String>> rows) {
		int columns = headers.size();
		int[] widths = new int[columns];
		for (int c = 0; c < columns; c++) {
			widths[c] = headers.get(c).length();
		}
		for (List<String> row : rows) {
			for (int c = 0; c < columns; c++) {
				widths[c] = Math.max(widths[c], cell(row, c).length());
			}
		}

		StringBuilder sb = new StringBuilder();
		appendRow(sb, headers, widths);
		for (List<String> row : rows) {
			appendRow(sb, row, widths);
		}
		return sb.toString();
	}

	private static void appendRow(StringBuilder sb, List<String> row, int[] widths) {
		for (int c = 0; c < widths.length; c++) {
			if (c > 0) {
				sb.append("  ");
			}
			String value = cell(row, c);
			sb.append(value);
			if (c < widths.length - 1) {
				sb.append(" ".repeat(widths[c] - value.length()));
			}
		}
		sb.append(System.lineSeparator());
	}

	private static String cell(List<String> row, int column) {
		if (column >= row.size() || row.get(column) == null) {
			return "";
		}
		return row.get(column);
	}

}
