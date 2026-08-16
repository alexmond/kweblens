package org.alexmond.kweblens.tui.kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.alexmond.kweblens.resource.DiscoveredKind;
import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * Every name a kind answers to, and the one kind each name resolves to.
 *
 * <p>
 * This is what {@code TuiKinds} was a placeholder for, and it is why that class is gone.
 * The placeholder listed eight kinds by hand; this is built from
 * {@code ClusterDataSource.kinds}, so on a plain k3s cluster it indexes every built-in
 * <em>and</em> every CRD — including ones this build has never heard of — and the short
 * names are the API server's own rather than a table somebody has to remember to update.
 *
 * <h2>The names</h2>
 *
 * For each discovered kind:
 * <ul>
 * <li>its <b>plural</b> — {@code pods}, {@code ingressroutes}
 * <li>its <b>singular</b> — {@code pod}
 * <li>its <b>kind</b>, lower-cased — {@code pod}, {@code ingressroute}
 * <li>every <b>short name</b> the server declares — {@code po}, {@code deploy},
 * {@code svc}
 * <li>two <b>fully-qualified</b> forms that are unique by construction:
 * {@code plural.group} ({@code ingressroutes.traefik.io}) and
 * {@code group/version/plural} ({@code apps/v1/deployments}, {@code v1/pods})
 * </ul>
 *
 * <h2>When two groups want the same word</h2>
 *
 * They do: {@code events} is in the core group and in {@code events.k8s.io};
 * {@code ingresses} was in {@code extensions} before {@code networking.k8s.io}. A short
 * name resolves to <b>one</b> kind, so the rule is fixed and stated rather than left to
 * map insertion order: <b>the core group wins, otherwise the alphabetically first group
 * does</b>. The loser is not lost — its fully-qualified forms are unique and always
 * resolve to it, which is the whole reason they are indexed.
 *
 * <p>
 * Nothing here talks to a cluster and nothing here draws: it is a map, so a test can
 * assert what {@code :po} resolves to without a terminal or an API server.
 */
public final class KindIndex {

	private static final KindIndex EMPTY = new KindIndex(List.of(), Map.of());

	private final List<DiscoveredKind> kinds;

	/** alias → kind, sorted, so {@link #complete} can walk a prefix range. */
	private final TreeMap<String, DiscoveredKind> byAlias;

	private KindIndex(List<DiscoveredKind> kinds, Map<String, DiscoveredKind> byAlias) {
		this.kinds = List.copyOf(kinds);
		this.byAlias = new TreeMap<>(byAlias);
	}

	/** The index a cluster that could not be discovered leaves behind. */
	public static KindIndex empty() {
		return EMPTY;
	}

	/**
	 * Build the index. {@code kinds} is what the port returned; the order it arrives in
	 * does not decide anything, because the conflict rule below sorts first.
	 */
	public static KindIndex of(List<DiscoveredKind> kinds) {
		if (kinds == null || kinds.isEmpty()) {
			return EMPTY;
		}
		List<DiscoveredKind> ordered = new ArrayList<>(kinds);
		// Core group first, then alphabetically by group: that IS the conflict rule, and
		// doing it by sorting means the rule is one line rather than a branch inside the
		// loop that is easy to read two ways.
		ordered.sort(Comparator.comparing((DiscoveredKind kind) -> kind.group().isEmpty() ? 0 : 1)
			.thenComparing(DiscoveredKind::group)
			.thenComparing(DiscoveredKind::plural));
		Map<String, DiscoveredKind> byAlias = new LinkedHashMap<>();
		for (DiscoveredKind kind : ordered) {
			for (String alias : shortAliases(kind)) {
				byAlias.putIfAbsent(alias, kind);
			}
		}
		// The qualified forms are put in last and unconditionally: they are unique by
		// construction, so nothing can be shadowed by a short name that got there first.
		for (DiscoveredKind kind : ordered) {
			for (String alias : qualifiedAliases(kind)) {
				byAlias.put(alias, kind);
			}
		}
		return new KindIndex(ordered, byAlias);
	}

	private static List<String> shortAliases(DiscoveredKind kind) {
		List<String> aliases = new ArrayList<>(4 + kind.shortNames().size());
		add(aliases, kind.plural());
		add(aliases, kind.singular());
		add(aliases, kind.kind());
		for (String shortName : kind.shortNames()) {
			add(aliases, shortName);
		}
		return aliases;
	}

	private static List<String> qualifiedAliases(DiscoveredKind kind) {
		if (kind.group().isEmpty()) {
			return List.of(kind.groupVersion() + "/" + kind.plural());
		}
		return List.of(kind.plural() + "." + kind.group(), kind.groupVersion() + "/" + kind.plural());
	}

	private static void add(List<String> aliases, String alias) {
		if (alias != null && !alias.isBlank()) {
			aliases.add(normalise(alias));
		}
	}

	private static String normalise(String token) {
		return token.strip().toLowerCase(Locale.ROOT);
	}

	/** The kind {@code token} names, or empty when nothing does. */
	public Optional<DiscoveredKind> find(String token) {
		return Optional.ofNullable((token != null) ? this.byAlias.get(normalise(token)) : null);
	}

	/**
	 * The descriptor {@code token} names — what a {@code ResourceQuery} is built from.
	 */
	public Optional<ResourceDescriptor> resolve(String token) {
		return find(token).map(DiscoveredKind::descriptor);
	}

	/**
	 * The aliases that continue {@code prefix}, in alphabetical order, at most
	 * {@code limit} of them.
	 *
	 * <p>
	 * <b>Candidates come from the index</b>, so a CRD installed this morning completes,
	 * and a fixed list cannot be substituted without this method changing shape. An empty
	 * prefix offers the first {@code limit} aliases rather than nothing: a prompt that
	 * shows what is available before you type is how you learn a vocabulary of 300 names.
	 */
	public List<String> complete(String prefix, int limit) {
		String from = normalise((prefix != null) ? prefix : "");
		List<String> candidates = new ArrayList<>(limit);
		for (String alias : this.byAlias.tailMap(from).keySet()) {
			if (!alias.startsWith(from)) {
				break;
			}
			candidates.add(alias);
			if (candidates.size() >= limit) {
				break;
			}
		}
		return List.copyOf(candidates);
	}

	/**
	 * The first alias that continues {@code prefix} and is not {@code prefix} itself —
	 * the inline suggestion, k9s's completion of {@code :pods} to a real kind.
	 */
	public Optional<String> suggestion(String prefix) {
		if (prefix == null || prefix.isBlank()) {
			return Optional.empty();
		}
		return complete(prefix, 2).stream().filter((alias) -> !alias.equals(normalise(prefix))).findFirst();
	}

	/** Every alias, sorted — what {@code --aliases} prints. */
	public List<String> aliases() {
		return List.copyOf(this.byAlias.keySet());
	}

	/** Every discovered kind, in the index's own order. */
	public List<DiscoveredKind> kinds() {
		return this.kinds;
	}

	/** How many kinds are addressable. Zero means discovery found nothing. */
	public int size() {
		return this.kinds.size();
	}

}
