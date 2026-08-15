package org.alexmond.kweblens.tui;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.CoreClusterDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the TUI's Spring context.
 *
 * <p>
 * <b>This test is not a formality.</b> {@code kweblens-cli} shipped a fat jar that died
 * on its first line for as long as the module existed, because its only test constructed
 * the picocli command directly and nothing ever started the application class — so an
 * injected bean that no dependency supplied went unnoticed through a green build and a
 * 0.60 coverage gate (#363). This module injects the same {@code CommandLine.IFactory},
 * from the same starter, and would fail the same way.
 *
 * <p>
 * Loading the context at all is the assertion: a missing bean fails startup before any
 * test method runs. The methods below name the beans so the failure reads as "the factory
 * is gone" rather than a stack trace, and drive the same construct-then-execute path
 * {@code main} takes.
 *
 * <p>
 * Hermetic: {@code kweblens.tui.load-kubeconfig=false} starts the registry empty, so no
 * kubeconfig is read and no cluster is contacted, and {@code --help} is answered entirely
 * by picocli.
 */
@SpringBootTest(args = "--help", properties = "kweblens.tui.load-kubeconfig=false")
class KweblensTuiApplicationTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private KweblensTuiApplication application;

	@Test
	void contextSuppliesTheBeansTheApplicationInjects() {
		assertThat(this.context.getBeanNamesForType(CommandLine.IFactory.class))
			.as("picocli-spring-boot-starter must contribute the CommandLine.IFactory bean")
			.isNotEmpty();
		assertThat(this.context.getBeanNamesForType(TuiCommand.class)).as("the root command must be a bean")
			.isNotEmpty();
	}

	@Test
	void theOnlyDataSourceIsTheCoreOne() {
		assertThat(this.context.getBeansOfType(ClusterDataSource.class).values())
			.as("v1 has exactly one adapter; a second one is a deliberate decision, not a side effect")
			.singleElement()
			.isInstanceOf(CoreClusterDataSource.class);
	}

	@Test
	void coreAccessServicesAreOnTheContext() {
		assertThat(this.context.getBeanNamesForType(ClusterRegistry.class))
			.as("the TUI asks the registry for clients and never builds one")
			.isNotEmpty();
	}

	@Test
	void helpPrintsUsageAndExitsZero() {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream original = System.out;
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			this.application.run("--help");
		}
		finally {
			System.setOut(original);
		}

		assertThat(this.application.getExitCode()).isZero();
		assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Usage: kweblens-tui");
	}

}
