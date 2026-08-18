package org.alexmond.kweblens.tui.render;

import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.tui.data.ObjectDetail;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.kind.KindIndex;
import org.alexmond.kweblens.tui.log.LogOpen;
import org.alexmond.kweblens.tui.log.LogRequest;
import org.alexmond.kweblens.tui.screen.Navigation;
import org.alexmond.kweblens.tui.screen.ResourceRow;

/**
 * A screen with nowhere to go: it draws, it moves its cursor, and every command answers
 * that this cluster serves no kinds.
 *
 * <p>
 * It exists so {@link ResourceScreen} can be built from a model, a coalescer and a query
 * alone — which is what the chrome tests want, and which keeps "what does the header say"
 * a question you can ask without a cluster. <b>It is not a null object that pretends
 * things worked</b>: {@link KindIndex#empty()} makes every {@code :} say so in words, and
 * a navigation that silently did nothing would be indistinguishable from one that failed.
 */
record NoNavigation(ResourceQuery query) implements Navigation {

	@Override
	public String clusterId() {
		return this.query.clusterId();
	}

	@Override
	public KindIndex kinds() {
		return KindIndex.empty();
	}

	@Override
	public GenericKubernetesResource object(String namespace, String name) {
		return null;
	}

	@Override
	public String show(ResourceQuery target, Predicate<ResourceRow> filter) {
		// Nothing to show onto. A screen built this way has no session behind it, and
		// KindIndex.empty() means no command ever resolves far enough to reach here.
		return "";
	}

	@Override
	public ObjectDetail detail(String namespace, String name) {
		// Same posture as everything else here: say so rather than draw an empty pane.
		return ObjectDetail.failed("This screen has no cluster behind it, so there is no detail to read.");
	}

	@Override
	public LogOpen logs(LogRequest request) {
		// And the same again. An empty log pane would be the claim that the container has
		// logged nothing, which is a finding — see PreviousLog for the same trap.
		return LogOpen.failed("This screen has no cluster behind it, so there are no logs to follow.");
	}

	@Override
	public void closeLogs() {
		// Nothing was ever opened, so there is nothing to release. This is the one method
		// here that has to be a genuine no-op rather than a sentence: it is called from
		// teardown, where there is nobody to tell.
	}

}
