package org.alexmond.kweblens.tui.data;

import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.health.ObjectState;

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

	/** One object by name, or {@code null} if it does not exist. */
	GenericKubernetesResource get(ResourceQuery query, String name);

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
	 * Follow a container's log from now. The returned {@link LogStream} must be closed,
	 * and closing it really does stop the follow — see {@link LogStream} for why that
	 * sentence needs saying.
	 */
	LogStream logs(PodTarget target);

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
