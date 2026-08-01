package org.alexmond.kweblens.web.files;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Path handling is the first line of defence on this surface, so it is tested on its own:
 * traversal must be rejected loudly rather than normalised away, and shell metacharacters
 * must survive untouched (they are safe because paths travel as argv, never as shell text
 * — see {@link PodFileScripts}).
 */
class PodFilePathTest {

	@Test
	void collapsesRedundantSegments() {
		assertThat(PodFilePath.normalize("/etc/./nginx//conf.d/")).isEqualTo("/etc/nginx/conf.d");
		assertThat(PodFilePath.normalize("/var/log/../lib")).isEqualTo("/var/lib");
		assertThat(PodFilePath.normalize("/")).isEqualTo("/");
		assertThat(PodFilePath.normalize("")).isEqualTo("/");
		assertThat(PodFilePath.normalize(null)).isEqualTo("/");
	}

	@Test
	void rejectsTraversalAboveTheRoot() {
		assertThatThrownBy(() -> PodFilePath.normalize("/../etc/shadow")).isInstanceOf(PodFileException.class)
			.hasMessageContaining("escapes the filesystem root");
		assertThatThrownBy(() -> PodFilePath.normalize("/etc/../../root")).isInstanceOf(PodFileException.class)
			.hasMessageContaining("escapes the filesystem root");
	}

	@Test
	void rejectsRelativePathsAndNulBytes() {
		assertThatThrownBy(() -> PodFilePath.normalize("etc/passwd")).isInstanceOf(PodFileException.class)
			.hasMessageContaining("must be absolute");
		assertThatThrownBy(() -> PodFilePath.normalize("/etc/pass\0wd")).isInstanceOf(PodFileException.class)
			.hasMessageContaining("NUL");
	}

	@Test
	void keepsHostileLookingFilenamesIntact() {
		// These are legal POSIX filenames. They are passed to the container as argv, so
		// nothing re-parses them; rewriting or rejecting them would just make legitimate
		// files unreachable.
		// (the trailing slash is normalised away, as on any directory path; the trailing
		// space inside the name is NOT, because it is part of the filename)
		assertThat(PodFilePath.normalize("/tmp/a b;rm -rf /")).isEqualTo("/tmp/a b;rm -rf ");
		assertThat(PodFilePath.normalize("/tmp/ends with a space ")).isEqualTo("/tmp/ends with a space ");
		assertThat(PodFilePath.normalize("/tmp/$(whoami)")).isEqualTo("/tmp/$(whoami)");
		assertThat(PodFilePath.normalize("/tmp/we\nird")).isEqualTo("/tmp/we\nird");
		assertThat(PodFilePath.normalize("/tmp/'quoted\"")).isEqualTo("/tmp/'quoted\"");
	}

	@Test
	void confinesToConfiguredRoots() {
		List<String> roots = List.of("/data", "/tmp");
		assertThat(PodFilePath.isWithinRoots("/data", roots)).isTrue();
		assertThat(PodFilePath.isWithinRoots("/data/sub/file", roots)).isTrue();
		assertThat(PodFilePath.isWithinRoots("/tmp/x", roots)).isTrue();
		assertThat(PodFilePath.isWithinRoots("/database", roots)).isFalse();
		assertThat(PodFilePath.isWithinRoots("/var/run/secrets", roots)).isFalse();
		assertThat(PodFilePath.isWithinRoots("/anything", List.of())).isTrue();
	}

	@Test
	void derivesADownloadName() {
		assertThat(PodFilePath.fileName("/etc/nginx/nginx.conf")).isEqualTo("nginx.conf");
		assertThat(PodFilePath.fileName("/")).isEqualTo("root");
	}

}
