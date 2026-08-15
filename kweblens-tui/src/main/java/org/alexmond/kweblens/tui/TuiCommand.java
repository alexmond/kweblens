package org.alexmond.kweblens.tui;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ResourceQuery;

/**
 * The argv seam. picocli resolves the flags and hands off; from #364 the screen is owned
 * by TamboUI after this point, and this class stays the place where a cluster id and a
 * kind are turned into a {@link ResourceQuery}.
 *
 * <p>
 * v1 has one mode: list a kind and exit. That is deliberately the smallest thing that
 * proves the module boots, reaches a cluster through the port and comes back — the
 * interactive screen is #364 and the command line <em>inside</em> it is #365.
 */
// A terminal app's stdout IS its interface, which is why Sonar's S106 suppression is
// path-scoped to this module as well as kweblens-cli. Everything printable is written
// through a PrintWriter rather than System.out.println so it can be redirected — and so a
// test can read the output as a value instead of capturing a stream.
@Component
@RequiredArgsConstructor
@Command(name = "kweblens-tui", mixinStandardHelpOptions = true, version = "kweblens-tui 0.1.0-SNAPSHOT",
		description = "Browse a Kubernetes cluster from a terminal, straight through your kubeconfig.")
public class TuiCommand implements Callable<Integer> {

	/** Nothing to talk to: no kubeconfig context, so no cluster. */
	private static final int EXIT_NO_CLUSTER = 3;

	/** The flags parsed but named something this build cannot resolve. */
	private static final int EXIT_BAD_ARGUMENT = 2;

	private final TuiClusterBootstrap bootstrap;

	private final ClusterDataSource cluster;

	private final TuiListing listing;

	private final TuiProperties properties;

	@Option(names = { "-c", "--context" },
			description = "kubeconfig context to open (defaults to the kubeconfig's current-context).")
	private String context;

	@Option(names = { "-n", "--namespace" }, description = "Namespace to scope to (defaults to all namespaces).")
	private String namespace;

	@Option(names = { "-k", "--kind" }, defaultValue = "pods",
			description = "Kind id to list (default: ${DEFAULT-VALUE}).")
	private String kind;

	@Option(names = "--contexts", description = "List the kubeconfig contexts this build can open, then exit.")
	private boolean listContexts;

	@Override
	public Integer call() {
		this.bootstrap.load();
		List<String> ids = this.cluster.clusters();
		PrintWriter out = new PrintWriter(System.out, false, StandardCharsets.UTF_8);
		if (this.listContexts) {
			ids.forEach(out::println);
			out.flush();
			return 0;
		}
		if (ids.isEmpty()) {
			return fail(out, "No kubeconfig context to open. Set KUBECONFIG or kweblens.tui.kubeconfig.",
					EXIT_NO_CLUSTER);
		}
		String clusterId = resolveCluster(ids);
		if (clusterId == null) {
			String reason = (StringUtils.hasText(this.context)) ? "Unknown context '" + this.context + "'."
					: "Several contexts and no usable current-context; pass --context.";
			return fail(out, reason + " Available: " + String.join(", ", ids), EXIT_BAD_ARGUMENT);
		}
		Optional<ResourceDescriptor> descriptor = TuiKinds.byId(this.kind);
		if (descriptor.isEmpty()) {
			return fail(out, "Unknown kind '" + this.kind + "'. This build knows: " + String.join(", ", TuiKinds.ids()),
					EXIT_BAD_ARGUMENT);
		}
		this.listing.print(new ResourceQuery(clusterId, descriptor.get(), this.namespace),
				this.properties.getChunkSize(), out);
		return 0;
	}

	/**
	 * Which cluster to open, or null when {@code --context} named one that is not there.
	 *
	 * <p>
	 * There is no {@code default} to fall back to — {@code default} is the server's name
	 * for an in-cluster service account and asserting it here would open a cluster the
	 * operator never asked for. With no flag, the kubeconfig's own current-context is the
	 * answer, so the TUI opens what {@code kubectl} would; failing that, a single
	 * registered cluster is unambiguous.
	 */
	private String resolveCluster(List<String> ids) {
		if (StringUtils.hasText(this.context)) {
			return ids.contains(this.context) ? this.context : null;
		}
		String current = this.bootstrap.currentContext();
		if (StringUtils.hasText(current) && ids.contains(current)) {
			return current;
		}
		return (ids.size() == 1) ? ids.get(0) : null;
	}

	private static Integer fail(PrintWriter out, String message, int code) {
		out.println(message);
		out.flush();
		return code;
	}

}
