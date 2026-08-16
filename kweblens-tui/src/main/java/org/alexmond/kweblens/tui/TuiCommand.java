package org.alexmond.kweblens.tui;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.alexmond.kweblens.tui.kind.KindCatalog;
import org.alexmond.kweblens.tui.kind.KindIndex;
import org.alexmond.kweblens.tui.render.Screen;
import org.alexmond.kweblens.tui.screen.TickRate;

/**
 * The argv seam. picocli resolves the flags and hands off; the screen is owned by TamboUI
 * after this point, and this class stays the place where a cluster id and a kind are
 * turned into a {@link ResourceQuery}.
 *
 * <p>
 * Two modes. The default is the live screen (#364). {@code --once} prints one listing and
 * exits, which is what a pipe, a script or a terminal-less CI job wants — and which is
 * the only mode that existed in #362. The command line <em>inside</em> the screen is
 * #365; nothing here is meant to grow into it.
 *
 * <p>
 * Nothing in this file imports {@code dev.tamboui}. The screen is behind {@link Screen}
 * so the decision between the two modes stays testable without a terminal.
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

	private final Screen screen;

	private final KindCatalog kinds;

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

	@Option(names = "--aliases",
			description = "List every name the ':' command line answers to on this cluster, then exit.")
	private boolean listAliases;

	@Option(names = "--once", description = "Print one listing and exit instead of opening the live screen.")
	private boolean once;

	@Option(names = "--tick", description = "Repaint period in milliseconds (default: ${DEFAULT-VALUE}). "
			+ "Clamped to 20..10000, and the screen says so when it clamps.")
	private long tickMillis = TickRate.DEFAULT.toMillis();

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
		KindIndex index = this.kinds.of(clusterId);
		if (this.listAliases) {
			index.aliases().forEach(out::println);
			out.flush();
			return 0;
		}
		Optional<ResourceDescriptor> descriptor = index.resolve(this.kind);
		if (descriptor.isEmpty()) {
			return fail(out, unknownKind(index), EXIT_BAD_ARGUMENT);
		}
		ResourceQuery query = new ResourceQuery(clusterId, descriptor.get(), this.namespace);
		if (this.once) {
			this.listing.print(query, this.properties.getChunkSize(), out);
			return 0;
		}
		return this.screen.run(query, this.properties.getChunkSize(), TickRate.of(Duration.ofMillis(this.tickMillis)),
				out);
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

	/**
	 * Why {@code --kind} did not resolve — and it matters which of the two reasons it is.
	 * A cluster that refused discovery knows no kinds at all, which is a different
	 * problem from a typo and has a different fix.
	 */
	private String unknownKind(KindIndex index) {
		if (index.size() == 0) {
			return "Nothing was discovered on this cluster, so '" + this.kind + "' cannot be resolved. "
					+ "The API server refused discovery or could not be reached.";
		}
		List<String> near = index.complete(this.kind, 5);
		String hint = (near.isEmpty()) ? " Run --aliases to see every name."
				: " Did you mean: " + String.join(", ", near) + "?";
		return "Unknown kind '" + this.kind + "'. This cluster serves " + index.size() + " kinds." + hint;
	}

	private static Integer fail(PrintWriter out, String message, int code) {
		out.println(message);
		out.flush();
		return code;
	}

}
