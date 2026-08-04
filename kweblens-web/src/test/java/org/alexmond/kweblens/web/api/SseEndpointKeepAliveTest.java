package org.alexmond.kweblens.web.api;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every SSE endpoint in the app must attach {@link SseKeepAlive}.
 *
 * <p>
 * This is a wiring guard, not a behaviour test — {@code SseKeepAliveTest} covers the
 * mechanism. It exists because the failure it prevents is invisible: a stream whose
 * writes are data-driven works perfectly in every test and every demo, and only leaks a
 * cluster-side resource when a real subscriber walks away from a quiet stream. Two of the
 * four SSE endpoints shipped in exactly that state, and the review that added the
 * keepalive to a third did not notice.
 *
 * <p>
 * So the invariant is checked structurally: scan the controllers, find the handlers that
 * return an {@link SseEmitter}, and require the declaring class to reference
 * {@code SseKeepAlive}. A new streaming endpoint fails here on the day it is written.
 */
class SseEndpointKeepAliveTest {

	private static final String BASE_PACKAGE = "org.alexmond.kweblens.web";

	private static final String REQUIRED = "SseKeepAlive";

	@Test
	void everySseEndpointAttachesTheKeepalive() throws Exception {
		List<Class<?>> streaming = streamingControllers();
		// A scan that found nothing would pass silently, which is the one result this
		// test
		// must never give.
		assertThat(streaming).as("SSE controllers discovered under " + BASE_PACKAGE).isNotEmpty();
		List<String> missing = new ArrayList<>();
		for (Class<?> controller : streaming) {
			if (!referencesKeepAlive(controller)) {
				missing.add(controller.getSimpleName());
			}
		}
		assertThat(missing).as("""
				SSE handlers whose class never mentions SseKeepAlive. An SseEmitter learns \
				its client is gone only from a failed write, so a stream that writes only \
				when the cluster produces data holds its watch/log-follow open on a \
				departed subscriber. Attach SseKeepAlive after the completion hooks, or \
				move the emitter's construction into a helper this scan can see.""").isEmpty();
	}

	/** Controllers with at least one handler returning an {@link SseEmitter}. */
	private List<Class<?>> streamingControllers() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
		List<Class<?>> found = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
			Class<?> type = Class.forName(definition.getBeanClassName());
			if (returnsAnEmitter(type)) {
				found.add(type);
			}
		}
		return found;
	}

	private boolean returnsAnEmitter(Class<?> type) {
		for (Method method : type.getDeclaredMethods()) {
			if (SseEmitter.class.isAssignableFrom(method.getReturnType())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The class file's own bytes carry every type and method name it uses in the constant
	 * pool, so a plain search over them answers "does this class talk to SseKeepAlive"
	 * without a bytecode library. Reflection cannot answer it: the call is inside a
	 * method body.
	 */
	private boolean referencesKeepAlive(Class<?> type) throws IOException {
		String resource = type.getName().replace('.', '/') + ".class";
		InputStream bytes = type.getClassLoader().getResourceAsStream(resource);
		assertThat(bytes).as("class file for " + type.getName()).isNotNull();
		try (bytes) {
			return new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1).contains(REQUIRED);
		}
	}

	/**
	 * The endpoints this guard is expected to cover, spelled out so that a scan which
	 * silently stops seeing one of them fails rather than shrinking.
	 */
	@Test
	void theScanSeesEveryKnownStreamingController() throws Exception {
		assertThat(streamingControllers().stream().map(Class::getSimpleName)).containsExactlyInAnyOrderElementsOf(Set
			.of("LogApiController", "MultiLogApiController", "ObjectApiController", "ResourceWatchApiController"));
	}

}
