package org.alexmond.kweblens.web.helm;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValuesLibraryServiceTest {

	private ValuesLibraryService serviceIn(Path dir) {
		HelmProperties props = new HelmProperties();
		props.setValuesPath(dir.resolve("values").toString());
		return new ValuesLibraryService(props);
	}

	@Test
	void savesGetsListsAndDeletes(@TempDir Path dir) {
		ValuesLibraryService library = serviceIn(dir);
		assertThat(library.list()).isEmpty();
		assertThat(library.get("prod")).isNull();

		library.save("prod", "replicaCount: 3\n");
		library.save("dev", "replicaCount: 1\n");

		assertThat(library.get("prod")).isEqualTo("replicaCount: 3\n");
		assertThat(library.list()).containsExactly("dev", "prod");

		library.delete("prod");
		assertThat(library.get("prod")).isNull();
		assertThat(library.list()).containsExactly("dev");
	}

	@Test
	void overwritesExisting(@TempDir Path dir) {
		ValuesLibraryService library = serviceIn(dir);
		library.save("v", "a: 1\n");
		library.save("v", "a: 2\n");
		assertThat(library.get("v")).isEqualTo("a: 2\n");
	}

	@Test
	void rejectsPathTraversalAndBadNames(@TempDir Path dir) {
		ValuesLibraryService library = serviceIn(dir);
		assertThatThrownBy(() -> library.save("../evil", "x: 1")).isInstanceOf(HelmException.class);
		assertThatThrownBy(() -> library.get("../../etc/passwd")).isInstanceOf(HelmException.class);
		assertThatThrownBy(() -> library.save("has space", "x: 1")).isInstanceOf(HelmException.class);
	}

}
