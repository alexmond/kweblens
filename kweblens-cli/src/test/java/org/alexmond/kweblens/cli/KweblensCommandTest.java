package org.alexmond.kweblens.cli;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import picocli.CommandLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KweblensCommandTest {

	@Test
	void rendersMasterUrlAndNamespace() {
		Config config = new ConfigBuilder().withMasterUrl("https://api.example:6443/").withNamespace("web").build();

		String out = new KweblensCommand().render(config);

		assertThat(out).contains("MASTER-URL").contains("https://api.example:6443/").contains("web");
	}

	@Test
	void missingValuesRenderAsDash() {
		Config config = new ConfigBuilder().withMasterUrl("https://api.example:6443/").build();

		String out = new KweblensCommand().render(config);

		// No namespace or resolvable context in this bare config.
		assertThat(out).contains("-");
	}

	@Test
	void executesWithHelpWithoutError() {
		int code = new CommandLine(new KweblensCommand()).execute("--help");

		assertThat(code).isZero();
	}

}
