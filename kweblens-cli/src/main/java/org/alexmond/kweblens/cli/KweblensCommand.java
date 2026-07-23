package org.alexmond.kweblens.cli;

import java.util.List;
import java.util.concurrent.Callable;

import io.fabric8.kubernetes.client.Config;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.springframework.stereotype.Component;

/**
 * Root CLI command. With no options it prints the cluster the ambient kubeconfig
 * currently points at (the CLI's "am I talking to the right cluster?" check);
 * {@code --context <name>} selects a different kubeconfig context.
 */
// A CLI command's stdout IS its interface, so println is intentional here (not in
// core/web).
@SuppressWarnings("PMD.SystemPrintln")
@Component
@Command(name = "kweblens", mixinStandardHelpOptions = true, version = "kweblens 0.1.0-SNAPSHOT",
		description = "Inspect the Kubernetes cluster your kubeconfig points at.")
public class KweblensCommand implements Callable<Integer> {

	@Option(names = { "-c", "--context" }, description = "kubeconfig context to use (defaults to current-context).")
	private String context;

	@Override
	public Integer call() {
		Config config = Config.autoConfigure(context);
		System.out.print(render(config));
		return 0;
	}

	/**
	 * Build the current-context table for a resolved {@link Config}. Package-private for
	 * testing.
	 */
	String render(Config config) {
		List<String> headers = List.of("MASTER-URL", "NAMESPACE", "CONTEXT");
		String currentContext = (context != null) ? context : contextName(config);
		List<List<String>> rows = List.of(List.of(nullToDash(config.getMasterUrl()), nullToDash(config.getNamespace()),
				nullToDash(currentContext)));
		return TableFormatter.table(headers, rows);
	}

	private String contextName(Config config) {
		return (config.getCurrentContext() != null) ? config.getCurrentContext().getName() : null;
	}

	private String nullToDash(String value) {
		return (value == null || value.isBlank()) ? "-" : value;
	}

}
