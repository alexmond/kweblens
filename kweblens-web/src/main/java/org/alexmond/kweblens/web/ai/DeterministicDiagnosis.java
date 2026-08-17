package org.alexmond.kweblens.web.ai;

/**
 * The half of {@link DiagnoseService} that cannot call a model.
 *
 * <p>
 * <b>Why an interface for one method.</b> {@code DiagnoseService} has two entry points
 * and only one of them is free: {@link DiagnoseService#diagnose} runs the validators and
 * serves whatever summary was already bought for exactly those findings, while
 * {@link DiagnoseService#analyse} is the one thing in kweblens that calls an LLM — it
 * costs money, ships cluster state to a third party, and is therefore a non-GET, auth
 * gated in both security modes, and audited.
 *
 * <p>
 * The MCP tool surface is none of those things. A tool call arrives over a transport
 * whose whole point is that a model drives it, so a tool holding a reference to
 * {@code DiagnoseService} would be one method name away from letting a model spend money
 * by asking. This port has <b>no {@code analyse} method to call</b>, which is the same
 * enforcement {@code kweblens-tui}'s {@code ClusterDataSource} uses to be read-only: not
 * a rule about what callers should do, but a type in which the call does not compile.
 *
 * <p>
 * Two tests keep it that way, because an interface alone is only as good as what people
 * inject. {@code McpToolsNeverCallAModelTest} asserts this type still declares exactly
 * one method, and that no shipped class in {@code web/mcp} mentions
 * {@code DiagnoseService} or a {@code ChatClient} — so a second field, or a downcast,
 * fails the build rather than the review.
 */
// Not @FunctionalInterface: one method is the point, not a coincidence worth advertising
// as a lambda target. This is a port with exactly one adapter, DiagnoseService.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface DeterministicDiagnosis {

	/**
	 * Every deterministic validator over this scope, most severe first, plus whatever
	 * summary the {@link DiagnosisSummaryCache} already holds for exactly these findings.
	 * Reaches no model, on any path, including a cache miss.
	 * @param clusterId the kweblens cluster id
	 * @param namespace the namespace, or null for the whole cluster
	 * @return the findings and everything known about a summary of them
	 */
	DiagnoseResult diagnose(String clusterId, String namespace);

}
