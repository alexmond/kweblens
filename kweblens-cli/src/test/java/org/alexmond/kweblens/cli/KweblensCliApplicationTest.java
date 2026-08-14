package org.alexmond.kweblens.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import picocli.CommandLine;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the CLI's Spring context, which is the gap that let a jar that cannot start ship
 * green (#363). {@link KweblensCommandTest} drives picocli directly and so never touches
 * the application class; every bean {@link KweblensCliApplication} injects was missing
 * from the context and nothing noticed until the fat jar was run by hand.
 * <p>
 * Loading this test at all is the assertion: a missing bean fails context startup before
 * any test method runs. The assertions below name the two beans so the failure reads as
 * "the factory is gone" rather than a stack trace, and run the same
 * construct-then-execute path {@code main} takes.
 * <p>
 * Hermetic: {@code --help} is answered entirely by picocli, so no kubeconfig is read and
 * no cluster is contacted.
 */
@SpringBootTest(args = "--help")
class KweblensCliApplicationTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private KweblensCliApplication application;

	@Test
	void contextSuppliesTheBeansTheApplicationInjects() {
		assertThat(context.getBeanNamesForType(CommandLine.IFactory.class))
			.as("picocli-spring-boot-starter must contribute the CommandLine.IFactory bean")
			.isNotEmpty();
		assertThat(context.getBeanNamesForType(KweblensCommand.class)).as("the root command must be a bean")
			.isNotEmpty();
	}

	@Test
	void helpPrintsUsageAndExitsZero() {
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream original = System.out;
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			application.run("--help");
		}
		finally {
			System.setOut(original);
		}

		assertThat(application.getExitCode()).isZero();
		assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Usage: kweblens");
	}

}
