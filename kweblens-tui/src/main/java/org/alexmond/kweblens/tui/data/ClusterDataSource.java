package org.alexmond.kweblens.tui.data;

import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.column.Column;
import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.resource.DiscoveredKind;

/**
 * Everything the terminal asks a cluster for: <b>list, watch, get, logs, exec</b> — and
 * nothing else.
 *
 * <h2>Why there is a port at all</h2>
 *
 * v1 has exactly one implementation, {@link CoreClusterDataSource}, which calls
 * {@code kweblens-core}'s access services directly on the operator's own kubeconfig. That
 * is the decided design (GH#362): core has no Spring-MVC contamination, its streaming
 * APIs already hand back the primitives a terminal wants, and a direct TUI is bounded by
 * the operator's own RBAC instead of the single shared admin credential.
 *
 * <p>
 * The port exists for the one case direct cannot serve: kweblens running in-cluster with
 * a service account and an operator who has no kubeconfig. That would be an HTTP adapter
 * over the running server's API. <b>It is deliberately not written yet</b> — a second
 * implementation built before anything needs it is a guess at what the first one's shape
 * should have been, and this interface's job today is only to keep that guess cheap to
 * make later. If you are about to add one, that is a separate, deliberate decision, not a
 * side effect of another ticket.
 *
 * <h2>Read-only, and that is structural</h2>
 *
 * There is no delete, scale, restart, cordon, drain, edit or apply here, because v1 of
 * the TUI has none. Writes arrive later through kweblens's suggest → approve → apply →
 * audit discipline, not as a method quietly added to this interface. Until then the
 * posture is shown in the header ({@link org.alexmond.kweblens.tui.TuiPosture}) rather
 * than assumed.
 *
 * <h2>What flows across it</h2>
 *
 * Objects are fabric8 {@link GenericKubernetesResource}s. That is core's own currency and
 * it is not an accident: projecting to a TUI-specific row type here would be a second
 * copy of a projection the server already owns, and it would go stale silently.
 */
public interface ClusterDataSource {

	/**
	 * The cluster ids this source can address, in a stable order. A TUI addresses a
	 * cluster purely by id, exactly as every other kweblens surface does; there is no
	 * "default" to assume.
	 */
	List<String> clusters();

	/**
	 * Every kind the cluster serves, with the names it answers to — the command line's
	 * whole vocabulary.
	 *
	 * <p>
	 * <b>Discovered, never listed here.</b> The catalog one module over is a curated menu
	 * and a good one; it cannot name a CRD installed this morning. This returns what the
	 * API server publishes, so a kind is addressable the moment it exists, and the short
	 * names are the server's own rather than a table that goes stale silently.
	 *
	 * <p>
	 * One round trip per group/version, so a caller holding a screen open should ask once
	 * and remember the answer rather than asking per keystroke.
	 */
	List<DiscoveredKind> kinds(String clusterId);

	/**
	 * Every object of a kind, delivered a page at a time.
	 *
	 * <p>
	 * <b>A page at a time is the contract, not an optimisation.</b> One unchunked list is
	 * ~241 KB of transient heap per Secret against a live cluster (#292/#293) and a
	 * terminal has no more heap than a browser does. A caller that drops each page holds
	 * one page, not the collection — so do not accumulate the pages into a list unless
	 * you have decided you can afford the whole kind.
	 * @param chunkSize objects per request; zero or less asks for the whole collection in
	 * one request. Not honoured by every API server — see
	 * {@code ResourceService.listRawChunked}.
	 * @param onPage called once per page, in order
	 */
	void list(ResourceQuery query, int chunkSize, Consumer<List<GenericKubernetesResource>> onPage);

	/**
	 * Each object's verdict, positionally: same size and same order as {@code objects},
	 * empty where nothing judges the object — which is a third answer, not "OK".
	 *
	 * <p>
	 * Takes the list rather than one object because the {@code StatusContext} a verdict
	 * may need (a Service's Endpoints, a claim's metrics, a ConfigMap's usage scan) is
	 * opened once for the whole call. Pass the same {@code query} the objects were listed
	 * with, or the context describes a different set of objects than the rows do.
	 *
	 * <p>
	 * The vocabulary is <b>open</b>: {@link ObjectState#label()} carries values that come
	 * straight from the cluster. Do not switch exhaustively over it.
	 */
	List<Optional<ObjectState>> states(ResourceQuery query, List<GenericKubernetesResource> objects);

	/**
	 * The kind-specific columns this kind's rows carry — the values the server computes
	 * once instead of every surface deriving them (GH#367).
	 *
	 * <p>
	 * <b>Per kind, never per object.</b> A built-in in the covered tranche answers from a
	 * static table; a custom kind answers from its CRD's
	 * {@code additionalPrinterColumns}, which is a call to the cluster, so this is asked
	 * once when a kind is opened and the result travels with the projection.
	 * @param query the kind and cluster
	 * @return the columns, empty for a kind with none — which draws the framework's own
	 * four columns and nothing else, exactly as every kind did before this
	 */
	List<Column> columns(ResourceQuery query);

	/** One object by name, or {@code null} if it does not exist. */
	GenericKubernetesResource get(ResourceQuery query, String name);

	/**
	 * Everything the detail pane shows about one object: its YAML, the relations the
	 * server computed for it, and its events (GH#368).
	 *
	 * <p>
	 * <b>One method rather than three, because it is one reading.</b> The relations are
	 * joins over the object's own fields, so relations fetched from a second GET can
	 * describe a spec the YAML beside them does not show. It is also one round trip for a
	 * pane the operator is waiting on, on the render thread, exactly as a navigation's
	 * list is.
	 *
	 * <p>
	 * <b>The joins are the server's and there is no version of them here.</b> The twelve
	 * relation keys come from {@code RelationService} in {@code kweblens-core}, which
	 * exists so the SPA, this terminal and the agent tools share one implementation
	 * instead of each reimplementing "which pods back this Service". A terminal that
	 * walked {@code ownerReferences} itself would be a second answer to a question that
	 * already has one — see {@code TuiComputesNoRelationTest}, which fails the build for
	 * it.
	 *
	 * <p>
	 * <b>It never throws for an object that is not there</b>, because that is an ordinary
	 * outcome: a row is listed and the object is deleted before the key is pressed. It
	 * reports it, so the pane can say so instead of drawing empty sections.
	 * @param query the kind and cluster; the namespace is the object's
	 * @param name the object's name
	 * @return the detail, or one carrying the reason there is nothing to show
	 */
	ObjectDetail detail(ResourceQuery query, String name);

	/**
	 * Watch a kind, delivering each change as (action, object) where action is
	 * {@code ADDED}, {@code MODIFIED} or {@code DELETED}.
	 *
	 * <p>
	 * <b>Never repaint per event.</b> Buffer and flush on a tick (#364): measured on this
	 * stack, one redraw posted per watch event does not merely cost frames, it starves
	 * the keyboard — a 2 000-event burst left keystrokes unprocessed entirely.
	 * <p>
	 * <b>{@code onEnd} is not optional and there is no overload without it</b> (GH#413).
	 * A watch stops on etcd compaction, a proxy's idle timeout, an API-server restart; a
	 * screen that is not told goes on drawing the last state it saw with a row count that
	 * reads as current, which is worse than an error because there is nothing to notice.
	 * The signal existed and was dropped, so the fix is a parameter a caller cannot
	 * forget rather than a second method it can pick.
	 * @param onEvent called on the watch thread, once per change; must stay cheap
	 * @param onEnd called at most once, on the watch thread, when nothing further will
	 * arrive — <b>never</b> for a close the caller itself asked for
	 * @return a handle that must be closed to stop watching
	 */
	Subscription watch(ResourceQuery query, BiConsumer<String, GenericKubernetesResource> onEvent,
			Consumer<WatchEnd> onEnd);

	/**
	 * Every container of a pod, in the pod's own order — what a log pane offers when
	 * there is more than one to choose from.
	 *
	 * <p>
	 * <b>The server's answer, not a walk of the object.</b> {@code LogSourceResolver}
	 * already expands "logs for this pod" into concrete containers for the SPA, and it is
	 * the one place that knows what an init container does and does not count as; a
	 * terminal that read {@code spec.containers} itself would be a second implementation
	 * of that decision, which is the rule {@code TuiComputesNoRelationTest} exists for
	 * one projection over.
	 *
	 * <p>
	 * Init containers are excluded, deliberately: their logs are usually finished and
	 * would dilute a live tail. The pod stuck in {@code Init:CrashLoopBackOff} is the
	 * case that wants them and it is not covered here yet.
	 * @throws RuntimeException if there is no such pod — the caller reports it as a
	 * sentence, because this is called on the render thread inside a key press
	 */
	List<String> containers(String clusterId, String namespace, String pod);

	/**
	 * Follow a container's log from now. The returned {@link LogStream} must be closed,
	 * and closing it really does stop the follow — see {@link LogStream} for why that
	 * sentence needs saying.
	 */
	LogStream logs(PodTarget target);

	/**
	 * The same follow, with the Kubernetes-supplied timestamp on every line.
	 *
	 * <p>
	 * <b>Two methods rather than one flag, because core has two</b> and the reason is in
	 * the DSL: {@code usingTimestamps()} must be called <em>before</em>
	 * {@code tailingLines(…)} or the option is gone from the narrowed builder type. A
	 * boolean here would put that ordering behind an {@code if} in the adapter and read
	 * at the call site as a parameter nobody can see the meaning of.
	 */
	LogStream logsWithTimestamps(PodTarget target);

	/**
	 * The log of the container's <em>previous</em> run — the crashloop diagnostic, since
	 * the current log starts from the new process and the output that explains the crash
	 * lives only in the terminated instance's.
	 *
	 * <p>
	 * A snapshot rather than a stream, because a terminated instance is not producing
	 * anything: there is nothing to follow. <b>It never throws for a container that has
	 * not restarted</b> — that is an ordinary outcome, and {@link PreviousLog} carries it
	 * as words so the pane cannot draw it as an empty document.
	 * @param tailLines how many lines back to read
	 */
	PreviousLog previousLog(PodTarget target, int tailLines);

	/**
	 * Start an interactive shell in a container, writing its output to {@code output}.
	 *
	 * <p>
	 * The bytes are raw terminal output, not lines: they are meant for a VT emulator
	 * ({@code org.jline.builtins.ScreenTerminal}), which is what makes an exec pane
	 * possible without a local PTY and without {@code kubectl} on {@code PATH}.
	 */
	ExecSession exec(PodTarget target, OutputStream output);

}
