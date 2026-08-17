package org.alexmond.kweblens.column;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * A timestamp as the short age a table shows: {@code 45s}, {@code 5m}, {@code 2h},
 * {@code 18d}.
 *
 * <p>
 * A port of {@code columns.ts}'s {@code age}, including the part that is easy to get
 * wrong: <b>an absent or unparseable timestamp is {@link ColumnText#MISSING}</b>, not
 * {@code 0s}. "The object does not say when it was created" and "the object was created
 * just now" are different claims, and only one of them is a reason to look at the row.
 *
 * <p>
 * {@code kweblens-tui}'s {@code ResourceRow.age} answers the same question for the
 * framework's own AGE column and returns an empty string instead, because that column is
 * dropped rather than dashed when it has nothing. The two are not merged for that reason;
 * they are neighbours, not duplicates.
 */
public final class Ages {

	private static final long MINUTE_SECONDS = 60L;

	private static final long HOUR_SECONDS = 3_600L;

	private static final long DAY_SECONDS = 86_400L;

	private Ages() {
	}

	/**
	 * The age of {@code timestamp} at {@code now}.
	 * @param timestamp an RFC 3339 timestamp, may be null or blank
	 * @param now the moment to measure against
	 * @return the short age, or {@link ColumnText#MISSING}
	 */
	public static String of(String timestamp, Instant now) {
		if (timestamp == null || timestamp.isBlank()) {
			return ColumnText.MISSING;
		}
		Instant created;
		try {
			created = Instant.parse(timestamp.trim());
		}
		catch (DateTimeParseException ex) {
			return ColumnText.MISSING;
		}
		long seconds = Math.max(0L, Duration.between(created, now).getSeconds());
		if (seconds >= DAY_SECONDS) {
			return (seconds / DAY_SECONDS) + "d";
		}
		if (seconds >= HOUR_SECONDS) {
			return (seconds / HOUR_SECONDS) + "h";
		}
		if (seconds >= MINUTE_SECONDS) {
			return (seconds / MINUTE_SECONDS) + "m";
		}
		return seconds + "s";
	}

}
