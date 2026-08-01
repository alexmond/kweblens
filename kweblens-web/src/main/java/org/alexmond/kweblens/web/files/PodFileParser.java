package org.alexmond.kweblens.web.files;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;

/**
 * Turns the NUL-delimited output of {@link PodFileScripts} into records.
 *
 * <p>
 * The wire format is a flat sequence of NUL-terminated fields, so a filename may contain
 * anything except NUL — which is exactly the set of legal POSIX filenames. Bytes are
 * decoded as UTF-8 with replacement: a filename that is not valid UTF-8 (legal, but
 * vanishingly rare) still lists, with the undecodable bytes shown as replacement
 * characters, rather than taking the whole directory down.
 */
final class PodFileParser {

	static final String TRUNCATED_MARKER = "__kwfb_truncated__";

	private static final int LISTING_FIELDS_PER_ENTRY = 5;

	private static final int STAT_FIELDS = 8;

	private PodFileParser() {
	}

	/** Split NUL-terminated fields; the trailing empty remainder is dropped. */
	static List<String> fields(byte[] output) {
		String text = new String(output, StandardCharsets.UTF_8);
		List<String> fields = new ArrayList<>(List.of(text.split("\0", -1)));
		if (!fields.isEmpty() && fields.get(fields.size() - 1).isEmpty()) {
			fields.remove(fields.size() - 1);
		}
		return fields;
	}

	static PodDirectoryListing listing(String path, String container, byte[] output) {
		List<String> fields = fields(output);
		if (fields.isEmpty()) {
			throw new PodFileException(HttpStatus.BAD_GATEWAY, "unreadable-listing",
					"the container produced no listing for " + path);
		}
		String resolved = fields.get(0);
		boolean truncated = false;
		List<PodFileEntry> entries = new ArrayList<>();
		int index = 1;
		while (index < fields.size()) {
			if (TRUNCATED_MARKER.equals(fields.get(index))) {
				truncated = true;
				break;
			}
			if (index + LISTING_FIELDS_PER_ENTRY > fields.size()) {
				break;
			}
			entries.add(entry(fields.subList(index, index + LISTING_FIELDS_PER_ENTRY)));
			index += LISTING_FIELDS_PER_ENTRY;
		}
		Comparator<PodFileEntry> order = Comparator.comparingInt(PodFileParser::directoryFirst)
			.thenComparing((entry) -> entry.name().toLowerCase(Locale.ROOT));
		entries.sort(order);
		return new PodDirectoryListing(path, resolved, container, List.copyOf(entries), truncated);
	}

	static PodFileStat stat(byte[] output) {
		List<String> fields = fields(output);
		if (fields.size() < STAT_FIELDS) {
			throw new PodFileException(HttpStatus.BAD_GATEWAY, "unreadable-stat",
					"the container did not report usable file metadata");
		}
		return new PodFileStat(type(fields.get(0)), number(fields.get(1)), blankToNull(fields.get(2)),
				number(fields.get(3)), blankToNull(fields.get(4)), blankToNull(fields.get(5)),
				blankToNull(fields.get(6)), blankToNull(fields.get(7)));
	}

	private static PodFileEntry entry(List<String> group) {
		String type = type(group.get(1));
		List<String> stat = List.of(group.get(2).split("\\|", -1));
		return new PodFileEntry(group.get(0), type, number(field(stat, 0)), blankToNull(field(stat, 1)),
				number(field(stat, 2)), blankToNull(field(stat, 3)), blankToNull(field(stat, 4)),
				blankToNull(group.get(3)), linkType(group.get(4)));
	}

	private static int directoryFirst(PodFileEntry entry) {
		boolean directory = "dir".equals(entry.type()) || "dir".equals(entry.linkType());
		return (directory) ? 0 : 1;
	}

	private static String field(List<String> values, int index) {
		return (index < values.size()) ? values.get(index) : "";
	}

	private static String type(String flag) {
		return switch (flag) {
			case "d" -> "dir";
			case "f" -> "file";
			case "l" -> "symlink";
			case "o" -> "other";
			default -> "missing";
		};
	}

	private static String linkType(String flag) {
		return switch (flag) {
			case "d" -> "dir";
			case "f" -> "file";
			default -> null;
		};
	}

	/**
	 * Parse a numeric metadata field, tolerating the padding some {@code wc}
	 * implementations emit. Anything that is not a plain number becomes {@code null} —
	 * unknown metadata, not a failed listing.
	 */
	private static Long number(String value) {
		String digits = value.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return null;
		}
		try {
			return Long.valueOf(digits);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String blankToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

}
