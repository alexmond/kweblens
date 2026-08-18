package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * One line typed at the {@code :} prompt, taken apart.
 *
 * <h2>The grammar</h2>
 *
 * <pre>
 * :&lt;kind&gt; [namespace] [filter terms…]
 * </pre>
 *
 * <ul>
 * <li><b>kind</b> — anything {@code KindIndex} answers to: plural, singular, kind, a
 * server-declared short name, or a fully-qualified {@code plural.group} /
 * {@code group/version/plural}. Resolution happens in the caller, because it needs the
 * cluster's discovery and this class needs nothing.</li>
 * <li><b>namespace</b> — the second token, when it is shaped like a namespace (a DNS
 * label) or is the word {@code all}. {@code all} means every namespace.</li>
 * <li><b>filter terms</b> — everything left, joined with spaces, passed verbatim to
 * {@code ObjectFilter}. A leading {@code /} is stripped from the first term, so
 * {@code :pods kube-system /coredns} reads the way k9s writes it and
 * {@code :pods kube-system k8s-app=kube-dns} works too.</li>
 * </ul>
 *
 * <h2>What is refused rather than ignored</h2>
 *
 * k9s's full line is {@code :cmd [ns] [/filter] [-f fuzzy] [@context] ['labels']}. This
 * build has no {@code -f} <em>flag</em> and does not switch cluster from the prompt, so a
 * line carrying {@code -f} or {@code @} is <b>an error naming what is unsupported</b>,
 * never a line quietly run without the half you asked for: a filter that silently did not
 * apply is a list that lies about what it contains. The reasoning is unchanged from the
 * day it was written; one fact under it is not.
 *
 * <h2>Fuzzy exists, and it is still not {@code -f}</h2>
 *
 * #411 landed {@code ~fuzzy} in {@link org.alexmond.kweblens.tui.filter.ObjectFilter} and
 * in {@code objectFilter.ts} together, so the refusal <b>names the spelling that
 * works</b> ({@code -f cored} → "spell it {@code ~cored}") rather than denying the
 * capability — the shape the {@code @context} refusal beside it already had. What it
 * deliberately does not do is <b>translate</b> {@code -f xyz} into {@code ~xyz} (GH#469),
 * for a reason this grammar owns: a leading {@code -} negates any term, so {@code -f} is
 * already a legal filter here meaning "does not contain f". Reading it as a flag instead
 * would take a query that means one thing and silently run another — the exact failure
 * the paragraph above refuses to commit, arrived at from the inside. Muscle memory is
 * served by being told the one character to change; it is not served by a second spelling
 * of a term that already exists.
 *
 * @param kind the kind token, as typed
 * @param namespace the namespace, or null for every namespace
 * @param filter the filter query, {@code ""} for none
 * @param error what is wrong with the line, or null. Never set together with the rest.
 */
public record CommandRequest(String kind, String namespace, String filter, String error) {

	/** The word that means "every namespace", as k9s spells it. */
	public static final String ALL = "all";

	/**
	 * Take a command line apart. Never throws; a bad line comes back carrying an error.
	 */
	public static CommandRequest parse(String line) {
		List<String> tokens = tokens(line);
		if (tokens.isEmpty()) {
			return failed("Type a kind, e.g. :pods, :deploy, :ingressroutes.");
		}
		String unsupported = unsupported(tokens);
		if (unsupported != null) {
			return failed(unsupported);
		}
		String kind = tokens.get(0);
		int next = 1;
		String namespace = null;
		if (tokens.size() > next && namespaceLike(tokens.get(next))) {
			namespace = ALL.equals(tokens.get(next)) ? null : tokens.get(next);
			next++;
		}
		return new CommandRequest(kind, namespace, filter(tokens, next), null);
	}

	private static CommandRequest failed(String error) {
		return new CommandRequest("", null, "", error);
	}

	private static List<String> tokens(String line) {
		List<String> tokens = new ArrayList<>(4);
		for (String token : ((line != null) ? line : "").strip().split("\\s+")) {
			if (!token.isEmpty()) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	private static String unsupported(List<String> tokens) {
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if ("-f".equals(token)) {
				return fuzzy((i + 1 < tokens.size()) ? tokens.get(i + 1) : null);
			}
			if (token.startsWith("@")) {
				return "No context switching from the prompt (@" + token.substring(1)
						+ "). Restart with --context to open another cluster.";
			}
		}
		return null;
	}

	/**
	 * The {@code -f} refusal, quoting the term the operator typed so the working line is
	 * one character away rather than one paragraph away.
	 * @param term what followed {@code -f}, or null when nothing did
	 */
	private static String fuzzy(String term) {
		String spelling = (term != null) ? term : "xyz";
		return "This build spells fuzzy ~" + spelling + ", not -f " + spelling + " (a leading - here means “not”).";
	}

	/**
	 * Is this token a namespace rather than the start of a filter? A namespace is a DNS
	 * label — lower-case letters, digits and dashes — so anything carrying {@code =},
	 * {@code :}, {@code /} or a capital is a filter term and is left alone. That is what
	 * lets {@code :pods app=web} and {@code :pods kube-system app=web} both mean what
	 * they look like.
	 */
	private static boolean namespaceLike(String token) {
		if (token.isEmpty() || token.length() > 63) {
			return false;
		}
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
			if (!allowed) {
				return false;
			}
		}
		return true;
	}

	private static String filter(List<String> tokens, int from) {
		if (from >= tokens.size()) {
			return "";
		}
		List<String> terms = new ArrayList<>(tokens.subList(from, tokens.size()));
		String first = terms.get(0);
		if (first.startsWith("/") && first.length() > 1 && !first.endsWith("/")) {
			// k9s writes the filter with a leading slash; the grammar here uses a bare
			// term, and /…/ with BOTH slashes is a regex it must keep.
			terms.set(0, first.substring(1));
		}
		return String.join(" ", terms);
	}

	/** Whether the line could not be read. */
	public boolean failed() {
		return this.error != null;
	}

}
