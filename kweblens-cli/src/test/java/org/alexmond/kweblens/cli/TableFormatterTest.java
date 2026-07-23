package org.alexmond.kweblens.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableFormatterTest {

	@Test
	void padsColumnsToTheWidestCell() {
		String out = TableFormatter.table(List.of("NAME", "AGE"),
				List.of(List.of("nginx", "3d"), List.of("a-very-long-pod-name", "1h")));

		String[] lines = out.split(System.lineSeparator());
		assertThat(lines[0]).startsWith("NAME");
		// Header 'NAME' is padded to the width of the longest name in the column.
		assertThat(lines[0].indexOf("AGE")).isEqualTo(lines[1].indexOf("3d"));
	}

	@Test
	void toleratesShortAndNullCells() {
		String out = TableFormatter.table(List.of("A", "B", "C"),
				List.of(List.of("x"), java.util.Arrays.asList("y", null, "z")));

		assertThat(out).contains("x").contains("y").contains("z");
	}

}
