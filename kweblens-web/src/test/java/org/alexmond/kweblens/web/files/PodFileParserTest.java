package org.alexmond.kweblens.web.files;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The NUL-delimited wire format the in-container scripts emit. The point of the format is
 * that a filename can contain anything except NUL, so the awkward names are the
 * interesting cases.
 */
class PodFileParserTest {

	private static byte[] nulTerminated(String... fields) {
		StringBuilder text = new StringBuilder();
		for (String field : fields) {
			text.append(field).append('\0');
		}
		return text.toString().getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void parsesEntriesAndOrdersDirectoriesFirst() {
		byte[] output = nulTerminated("/etc", "zebra.conf", "f", "120|644|1700000000|root|root", "", "", "sub", "d",
				"4096|755|1700000001|root|root", "", "", "link", "l", "7|777|1700000002|root|root", "sub", "d");

		PodDirectoryListing listing = PodFileParser.listing("/etc", "app", output);

		assertThat(listing.resolvedPath()).isEqualTo("/etc");
		assertThat(listing.truncated()).isFalse();
		assertThat(listing.entries()).extracting(PodFileEntry::name).containsExactly("link", "sub", "zebra.conf");
		PodFileEntry file = listing.entries().get(2);
		assertThat(file.type()).isEqualTo("file");
		assertThat(file.size()).isEqualTo(120L);
		assertThat(file.mode()).isEqualTo("644");
		assertThat(file.modified()).isEqualTo(1_700_000_000L);
		assertThat(file.owner()).isEqualTo("root");
		PodFileEntry link = listing.entries().get(0);
		assertThat(link.type()).isEqualTo("symlink");
		assertThat(link.linkTarget()).isEqualTo("sub");
		assertThat(link.linkType()).isEqualTo("dir");
	}

	@Test
	void survivesNamesWithSpacesQuotesAndNewlines() {
		String awkward = "two words\nsecond 'line'\"";
		byte[] output = nulTerminated("/tmp", awkward, "f", "1|600|1|nobody|nobody", "", "");

		PodDirectoryListing listing = PodFileParser.listing("/tmp", null, output);

		assertThat(listing.entries()).hasSize(1);
		assertThat(listing.entries().get(0).name()).isEqualTo(awkward);
	}

	@Test
	void reportsTruncation() {
		byte[] output = nulTerminated("/big", "a", "f", "1|644|1|root|root", "", "", PodFileParser.TRUNCATED_MARKER);

		PodDirectoryListing listing = PodFileParser.listing("/big", null, output);

		assertThat(listing.entries()).hasSize(1);
		assertThat(listing.truncated()).isTrue();
	}

	@Test
	void toleratesMissingStatMetadata() {
		// A container without `stat` still lists its files; the columns are simply
		// unknown, which is better than failing the whole directory.
		byte[] output = nulTerminated("/x", "file", "f", "", "", "");

		PodFileEntry entry = PodFileParser.listing("/x", null, output).entries().get(0);

		assertThat(entry.size()).isNull();
		assertThat(entry.mode()).isNull();
		assertThat(entry.owner()).isNull();
	}

	@Test
	void parsesStatOutput() {
		byte[] output = nulTerminated("f", "42", "600", "1700000000", "app", "app", "", "/real/file");

		PodFileStat stat = PodFileParser.stat(output);

		assertThat(stat.type()).isEqualTo("file");
		assertThat(stat.exists()).isTrue();
		assertThat(stat.isDirectory()).isFalse();
		assertThat(stat.size()).isEqualTo(42L);
		assertThat(stat.realPath()).isEqualTo("/real/file");
	}

	@Test
	void reportsAMissingPathRatherThanFailing() {
		byte[] output = nulTerminated("x", "", "", "", "", "", "", "/nope");

		PodFileStat stat = PodFileParser.stat(output);

		assertThat(stat.exists()).isFalse();
	}

	@Test
	void rejectsUnusableOutput() {
		assertThatThrownBy(() -> PodFileParser.stat(new byte[0])).isInstanceOf(PodFileException.class)
			.hasMessageContaining("metadata");
		assertThatThrownBy(() -> PodFileParser.listing("/x", null, new byte[0])).isInstanceOf(PodFileException.class)
			.hasMessageContaining("no listing");
	}

}
