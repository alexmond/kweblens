package org.alexmond.kweblens.resource;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSummaryTest {

	private static final Instant NOW = Instant.parse("2024-06-01T12:00:00Z");

	@Test
	void agePicksTheLargestSingleUnit() {
		assertThat(ResourceSummary.age(NOW.minus(Duration.ofDays(3)), NOW)).isEqualTo("3d");
		assertThat(ResourceSummary.age(NOW.minus(Duration.ofHours(5)), NOW)).isEqualTo("5h");
		assertThat(ResourceSummary.age(NOW.minus(Duration.ofMinutes(12)), NOW)).isEqualTo("12m");
		assertThat(ResourceSummary.age(NOW.minus(Duration.ofSeconds(45)), NOW)).isEqualTo("45s");
	}

	@Test
	void ageOfZeroDurationIsSeconds() {
		assertThat(ResourceSummary.age(NOW, NOW)).isEqualTo("0s");
	}

	@Test
	void ageIsDashForNullOrFutureCreation() {
		assertThat(ResourceSummary.age(null, NOW)).isEqualTo("-");
		assertThat(ResourceSummary.age(NOW, null)).isEqualTo("-");
		assertThat(ResourceSummary.age(NOW.plus(Duration.ofHours(1)), NOW)).isEqualTo("-");
	}

}
