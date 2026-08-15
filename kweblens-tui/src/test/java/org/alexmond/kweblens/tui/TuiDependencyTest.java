package org.alexmond.kweblens.tui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What must <b>not</b> be on this module's classpath.
 *
 * <p>
 * Two claims, and both are decisions rather than accidents:
 * <ul>
 * <li><b>No {@code kweblens-web}.</b> The TUI talks to a cluster directly through
 * {@code kweblens-core}, never to a running kweblens server. A dependency on the web
 * module would not merely be redundant — it would drag a servlet container into a console
 * app and would make the terminal's answers depend on a deployment the operator may not
 * have.</li>
 * <li><b>No servlet stack at all.</b> {@code spring-web}, {@code spring-webmvc},
 * {@code jakarta.servlet}, Tomcat, Jetty, Undertow.</li>
 * </ul>
 *
 * <p>
 * The test asserts on the <em>test</em> classpath, which is a superset of the compile
 * one, so a compile-scoped leak cannot hide from it. Absence is asserted by
 * {@link ClassNotFoundException}, and the positive control below is what stops the whole
 * file from passing because the probe itself is broken: a name that IS present must load.
 */
class TuiDependencyTest {

	private static final List<String> FORBIDDEN = List.of(
			// kweblens-web — the module this one must not know about.
			"org.alexmond.kweblens.web.config.ClusterBootstrap", "org.alexmond.kweblens.KweblensApplication",
			// The servlet stack, in the order dependency:tree would show it.
			"org.springframework.web.context.request.RequestContextHolder",
			"org.springframework.web.servlet.DispatcherServlet", "jakarta.servlet.Servlet",
			"org.apache.catalina.startup.Tomcat", "org.eclipse.jetty.server.Server", "io.undertow.Undertow");

	@Test
	void theProbeCanActuallySeeClassesThatArePresent() {
		assertThat(load("org.alexmond.kweblens.resource.ResourceService"))
			.as("positive control: kweblens-core IS a dependency, so its classes must load — "
					+ "without this, every assertion below would pass on a broken probe")
			.isNotNull();
		assertThat(load("org.alexmond.kweblens.tui.data.CoreClusterDataSource")).isNotNull();
	}

	@Test
	void neitherKweblensWebNorAServletContainerIsReachable() {
		for (String name : FORBIDDEN) {
			assertThatExceptionOfType(ClassNotFoundException.class)
				.as("%s must not be on kweblens-tui's classpath", name)
				.isThrownBy(() -> Class.forName(name));
		}
	}

	private static Class<?> load(String name) {
		try {
			return Class.forName(name);
		}
		catch (ClassNotFoundException ex) {
			throw new AssertionError(name + " should be on the classpath", ex);
		}
	}

}
