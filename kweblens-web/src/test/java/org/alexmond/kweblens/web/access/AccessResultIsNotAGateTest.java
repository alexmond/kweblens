package org.alexmond.kweblens.web.access;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An access-review verdict may reach <b>presentation code and nothing else</b>.
 *
 * <p>
 * This is the structural half of the rule the whole feature rests on: a
 * {@code SelfSubjectAccessReview} is a UI affordance and never an authorization gate, and
 * it fails open. A service or controller that consulted a verdict before performing a
 * request would quietly turn a <i>probe</i> into a gate — one that answers
 * {@code UNKNOWN} whenever the cluster is unreachable, and so would let through exactly
 * what it appeared to be protecting. The comment saying so is worth having; a check that
 * fails the build is worth more.
 *
 * <p>
 * So: scan every shipped class for a reference to the {@code access} package, and require
 * each referrer to be either the package itself or the {@code web.access} slice that
 * renders it. {@link #theScanCanTellAReferrerFromANonReferrer()} is the positive control
 * — a class known to reference the package and a class known not to — because a scan that
 * silently found nothing would pass this test for the wrong reason.
 */
class AccessResultIsNotAGateTest {

	/** Any mention of the access package: a type, a descriptor, a method reference. */
	private static final String MARKER = "org/alexmond/kweblens/access/";

	/** The core service and its types. */
	private static final String CORE_PACKAGE = "org/alexmond/kweblens/access/";

	/** The web slice that serves the verdicts to the SPA — presentation, no writes. */
	private static final String WEB_PACKAGE = "org/alexmond/kweblens/web/access/";

	@Test
	void onlyThePresentationSliceEverSeesAVerdict() throws IOException {
		List<String> referrers = referrers();
		// A scan that found nothing would pass silently, which is the one result this
		// test must never give.
		assertThat(referrers).as("classes referencing " + MARKER).isNotEmpty();
		List<String> outside = referrers.stream()
			.filter((name) -> !name.startsWith(CORE_PACKAGE) && !name.startsWith(WEB_PACKAGE))
			.toList();
		assertThat(outside).as("""
				An access-review verdict escaped presentation code. \
				A SelfSubjectAccessReview is a UI affordance that FAILS OPEN — it may never \
				decide whether a request proceeds. If one of these needs to know what the \
				service account can do, it needs to ask the cluster by making the request.""").isEmpty();
	}

	@Test
	void theScanCanTellAReferrerFromANonReferrer() throws IOException {
		List<String> referrers = referrers();

		assertThat(referrers).contains(WEB_PACKAGE + "AccessPageService.class");
		assertThat(referrers).doesNotContain("org/alexmond/kweblens/resource/ResourceService.class");
	}

	/** Every shipped class whose bytes mention the access package, by class-file path. */
	private static List<String> referrers() throws IOException {
		List<String> found = new ArrayList<>();
		Resource[] classes = new PathMatchingResourcePatternResolver()
			.getResources("classpath*:org/alexmond/kweblens/**/*.class");
		for (Resource resource : classes) {
			String location = resource.getURL().toString();
			// Test classes are not shipped, and this file itself names the marker.
			if (location.contains("test-classes")) {
				continue;
			}
			try (InputStream in = resource.getInputStream()) {
				String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
				if (bytes.contains(MARKER)) {
					found.add(location.substring(location.indexOf("org/alexmond/kweblens/")));
				}
			}
		}
		return found;
	}

}
