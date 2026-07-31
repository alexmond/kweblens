package org.alexmond.kweblens.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps runtime clusters for the life of the process only.
 *
 * <p>
 * Selected by {@code kweblens.cluster-store.mode=memory}. It exists so that a deployment
 * with no writable volume and no in-cluster API access can still add a cluster for the
 * session without kweblens inventing a place to write a credential — and so tests never
 * touch a developer's real data directory.
 */
public class InMemoryClusterStore implements ClusterStore {

	private final Map<String, ClusterDefinition> definitions = new ConcurrentHashMap<>();

	@Override
	public List<ClusterDefinition> load() {
		return this.definitions.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
	}

	@Override
	public void save(ClusterDefinition definition) {
		this.definitions.put(definition.id(), definition);
	}

	@Override
	public void delete(String id) {
		this.definitions.remove(id);
	}

	@Override
	public String describe() {
		return "in-memory — runtime clusters are lost on restart";
	}

	@Override
	public boolean persistent() {
		return false;
	}

}
