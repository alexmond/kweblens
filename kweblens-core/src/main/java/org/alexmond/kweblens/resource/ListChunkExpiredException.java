package org.alexmond.kweblens.resource;

import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * A chunked list could not be finished because the snapshot it was reading expired.
 *
 * <p>
 * {@link ResourceService#listRawChunked} fetches a collection in server-side pages, and a
 * continue token pins the API server's revision so the pages compose into one consistent
 * snapshot. That revision can be compacted away mid-scan — normally only if the scan is
 * unusually slow relative to the cluster's compaction window — and the API server then
 * answers <b>410 Gone</b> rather than serving a page that would silently mix revisions.
 *
 * <p>
 * It is a distinct type because 410 on the <em>first</em> request means something quite
 * different (and is left as a plain {@link KubernetesClientException}); only a request
 * that carried a token can mean "your snapshot is gone". The sanctioned response is to
 * <b>restart the scan</b> — every chunk already handed over describes a revision that no
 * longer exists, so they must be discarded rather than topped up.
 */
public class ListChunkExpiredException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ListChunkExpiredException(String resourceId, KubernetesClientException cause) {
		super("The chunked list of '" + resourceId + "' expired before it finished; it must be restarted", cause);
	}

}
