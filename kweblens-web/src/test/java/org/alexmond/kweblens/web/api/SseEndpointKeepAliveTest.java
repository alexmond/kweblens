package org.alexmond.kweblens.web.api;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
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
 * stream a response, and require the declaring class to <b>call
 * {@code SseKeepAlive.attach}</b>.
 *
 * <h2>Why the call and not the name</h2>
 *
 * <p>
 * This guard used to search the class file's bytes for the string {@code "SseKeepAlive"},
 * which every streaming controller satisfies without attaching anything: they all call
 * {@code SseKeepAlive.completeQuietly} from their failed-send path, and that call puts
 * the class name in the constant pool. So the natural way to write the next endpoint —
 * copy the {@code send(…)} helper from a neighbour — would have satisfied the guard while
 * shipping the original leak. {@link #theGuardRejectsAClassThatOnlyCompletesTheStream()}
 * is the positive control for that: a class that is known to reference
 * {@code SseKeepAlive} and known not to attach it, which the old check passed and this
 * one must fail.
 *
 * <p>
 * Two limits are known and accepted. A handler that hides its emitter behind a return
 * type naming neither {@code SseEmitter} nor {@code ResponseBodyEmitter} (say
 * {@code Object}) is invisible here; and {@code SseKeepAlive} is package-private, so an
 * SSE endpoint outside {@code web.api} cannot call it at all — that one fails loudly at
 * compile time, which is the outcome we want anyway.
 */
class SseEndpointKeepAliveTest {

	private static final String BASE_PACKAGE = "org.alexmond.kweblens.web";

	@Test
	void everySseEndpointAttachesTheKeepalive() throws Exception {
		List<Class<?>> streaming = streamingControllers();
		// A scan that found nothing would pass silently, which is the one result this
		// test must never give.
		assertThat(streaming).as("SSE controllers discovered under " + BASE_PACKAGE).isNotEmpty();
		List<String> missing = new ArrayList<>();
		for (Class<?> controller : streaming) {
			if (!attachesKeepAlive(controller)) {
				missing.add(controller.getSimpleName());
			}
		}
		assertThat(missing).as("""
				SSE handlers whose class never calls SseKeepAlive.attach. An SseEmitter learns \
				its client is gone only from a failed write, so a stream that writes only \
				when the cluster produces data holds its watch/log-follow open on a \
				departed subscriber. Calling SseKeepAlive.completeQuietly is NOT enough — \
				that is the failed-send path, not the probe. Attach the keepalive after the \
				completion hooks, or move the emitter's construction into a helper this \
				scan can see.""").isEmpty();
	}

	/**
	 * The endpoints this guard is expected to cover, spelled out so that a scan which
	 * silently stops seeing one of them fails rather than shrinking — and so that a new
	 * streaming endpoint fails here on the day it is written, in the file that states the
	 * requirement.
	 */
	@Test
	void theScanSeesEveryKnownStreamingController() throws Exception {
		assertThat(streamingControllers().stream().map(Class::getSimpleName)).containsExactlyInAnyOrderElementsOf(Set
			.of("LogApiController", "MultiLogApiController", "ObjectApiController", "ResourceWatchApiController"));
	}

	/**
	 * The positive control. {@code MultiLogStream} references {@code SseKeepAlive} — it
	 * calls {@code completeQuietly} when a send fails — and never attaches a probe. The
	 * string search this guard used to perform passes it; the bytecode check must not.
	 */
	@Test
	void theGuardRejectsAClassThatOnlyCompletesTheStream() throws Exception {
		assertThat(mentionsKeepAlive(MultiLogStream.class))
			.as("precondition: MultiLogStream's class file does name SseKeepAlive")
			.isTrue();

		assertThat(attachesKeepAlive(MultiLogStream.class))
			.as("a class that only calls completeQuietly must not satisfy the guard")
			.isFalse();
	}

	/** The other half of the control: a class that really does attach passes. */
	@Test
	void theGuardAcceptsAControllerThatAttaches() throws Exception {
		assertThat(attachesKeepAlive(ObjectApiController.class)).isTrue();
	}

	/**
	 * The shapes a streaming handler can take. A declared {@code SseEmitter} is the one
	 * every endpoint uses today; the wrapped forms are here because they are the ways a
	 * future endpoint could slip past a return-type check that only tested assignability.
	 */
	@Test
	void theScanRecognisesWrappedEmitterReturnTypes() {
		assertThat(streamsAResponse(Shapes.Direct.class)).isTrue();
		assertThat(streamsAResponse(Shapes.Wrapped.class)).isTrue();
		assertThat(streamsAResponse(Shapes.BaseEmitter.class)).isTrue();
		assertThat(streamsAResponse(Shapes.NotStreaming.class)).isFalse();
	}

	/** Controllers with at least one handler that streams a response. */
	private List<Class<?>> streamingControllers() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		// @Controller rather than @RestController: the filter follows meta-annotations,
		// so
		// this covers both, and a plain @Controller with a streaming handler leaks
		// exactly
		// the same way.
		scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
		List<Class<?>> found = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
			Class<?> type = Class.forName(definition.getBeanClassName());
			if (streamsAResponse(type)) {
				found.add(type);
			}
		}
		return found;
	}

	private boolean streamsAResponse(Class<?> type) {
		for (Method method : type.getDeclaredMethods()) {
			if (ResponseBodyEmitter.class.isAssignableFrom(method.getReturnType())) {
				return true;
			}
			// ResponseEntity<SseEmitter> and friends: the raw return type says nothing,
			// so
			// read the generic signature.
			String generic = method.getGenericReturnType().getTypeName();
			if (generic.contains(ResponseBodyEmitter.class.getName()) || generic.contains(SseEmitter.class.getName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Does this class actually <em>invoke</em> {@code SseKeepAlive.attach}? Reflection
	 * cannot answer it — the call is inside a method body — so the class file is read
	 * with spring-core's own repackaged ASM, which is already on this classpath (the
	 * scanner above is ASM-backed).
	 */
	private boolean attachesKeepAlive(Class<?> type) throws IOException {
		String resource = type.getName().replace('.', '/') + ".class";
		try (InputStream bytes = type.getClassLoader().getResourceAsStream(resource)) {
			assertThat(bytes).as("class file for " + type.getName()).isNotNull();
			AttachSeeker seeker = new AttachSeeker();
			new ClassReader(bytes).accept(seeker, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return seeker.found();
		}
	}

	/** The check this guard used to perform, kept only to prove that it was too weak. */
	private boolean mentionsKeepAlive(Class<?> type) throws IOException {
		String resource = type.getName().replace('.', '/') + ".class";
		try (InputStream bytes = type.getClassLoader().getResourceAsStream(resource)) {
			assertThat(bytes).as("class file for " + type.getName()).isNotNull();
			return new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1).contains("SseKeepAlive");
		}
	}

	/** Sets itself when it sees an {@code invokestatic SseKeepAlive.attach}. */
	private static final class AttachSeeker extends ClassVisitor {

		private static final String OWNER = SseKeepAlive.class.getName().replace('.', '/');

		private boolean found;

		AttachSeeker() {
			super(SpringAsmInfo.ASM_VERSION);
		}

		boolean found() {
			return this.found;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
				@Override
				public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean isInterface) {
					if (opcode == Opcodes.INVOKESTATIC && OWNER.equals(owner) && "attach".equals(called)) {
						AttachSeeker.this.found = true;
					}
				}
			};
		}

	}

	/** Return-type fixtures for {@link #theScanRecognisesWrappedEmitterReturnTypes()}. */
	private static final class Shapes {

		private Shapes() {
		}

		static final class Direct {

			SseEmitter stream() {
				return null;
			}

		}

		static final class Wrapped {

			ResponseEntity<SseEmitter> stream() {
				return null;
			}

		}

		static final class BaseEmitter {

			ResponseBodyEmitter stream() {
				return null;
			}

		}

		static final class NotStreaming {

			String plain() {
				return null;
			}

		}

	}

}
