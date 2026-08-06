package org.alexmond.kweblens.web.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;

/**
 * The JSON array a list endpoint returns, assembled <b>one server-side chunk at a
 * time</b>.
 *
 * <p>
 * <b>Why not one line of {@code Serialization.asJson}.</b> That is what this replaces,
 * and the reason is measured rather than assumed (#292, #293). One list request cost ~241
 * KB of transient heap <em>per Secret</em> on the live path — 1.33 GB for 3 000 Secrets,
 * against a chart that limits the container to 1 GiB, i.e. an OOM-kill rather than a slow
 * page. JFR allocation sampling with stacks then attributed it:
 *
 * <table border="1">
 * <caption>Allocation per live Secrets list</caption>
 * <tr>
 * <td>response body + deserialised model graph</td>
 * <td align="right">94%</td>
 * </tr>
 * <tr>
 * <td>{@code Serialization.asJson} — the output {@code String}</td>
 * <td align="right">1.4%</td>
 * </tr>
 * </table>
 *
 * <p>
 * So the copy worth removing is <b>not</b> the output one, and streaming the reply — the
 * fix that was already scoped — would have removed 1.4% of the problem. What is expensive
 * is that the whole collection exists as bytes and then again as objects before this
 * class is called at all. {@link ResourceService#listRawChunked} fetches it in pages
 * instead; each page is projected, written, and dropped, so peak heap follows the chunk
 * size rather than the collection size.
 *
 * <p>
 * <b>What is still O(N), and why that is the right trade.</b> The assembled bytes are
 * held until the response is written, so a caller still holds one copy of the payload.
 * That copy is the projected payload — 498 bytes per Secret row after #279, 2.53 MB for
 * the 3 000 Secrets that used to spike 1.33 GB. Keeping it buys a {@code Content-Length},
 * a {@code ProblemDetail} when the cluster fails on chunk three rather than a truncated
 * body under a 200, and no async plumbing; against a term the measurement puts three
 * orders of magnitude below the one being removed.
 *
 * <p>
 * <b>The bytes must not change.</b> {@link ListProjection} still runs per object, so
 * #279's 88% saving is intact, and the array is written with the same
 * {@code Serialization.jsonMapper()} that {@code asJson} uses, so the output is
 * byte-identical to the whole-list serialisation it replaces — asserted in
 * {@code ListJsonTest} against a real {@code Serialization.asJson}.
 */
final class ListJson {

	/**
	 * Enough for a small list without a resize, small enough not to matter for a big one:
	 * the buffer doubles from here, and the payloads this serves run from a few hundred
	 * bytes to tens of megabytes.
	 */
	private static final int INITIAL_BUFFER = 64 * 1024;

	private ListJson() {
	}

	/**
	 * Every object of a kind, fetched in chunks of {@code chunkSize} and projected on the
	 * way out. The result is the complete collection — chunking is invisible to the
	 * caller, see {@link ResourceService#listRawChunked} for why that matters.
	 */
	static byte[] chunked(ResourceService resources, String clusterId, ResourceDescriptor descriptor, String namespace,
			int chunkSize) {
		return write((mapper, gen) -> resources.listRawChunked(clusterId, descriptor, namespace, chunkSize,
				(chunk) -> writeChunk(mapper, gen, chunk)));
	}

	/**
	 * A collection already in hand — for the callers whose size is bounded by something
	 * other than the cluster (the pods on one node), which therefore have nothing to
	 * chunk. Kept on this path anyway so both list endpoints produce their JSON the same
	 * way.
	 */
	static byte[] of(List<GenericKubernetesResource> objects) {
		return write((mapper, gen) -> writeChunk(mapper, gen, objects));
	}

	private static byte[] write(ArrayBody body) {
		ObjectMapper mapper = Serialization.jsonMapper();
		ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_BUFFER);
		try (JsonGenerator gen = mapper.getFactory().createGenerator(out, JsonEncoding.UTF8)) {
			gen.writeStartArray();
			body.writeInto(mapper, gen);
			gen.writeEndArray();
		}
		catch (IOException ex) {
			// A ByteArrayOutputStream cannot fail, so this is a serialisation fault, not
			// I/O — it must not be reported as the cluster being unreachable.
			throw new UncheckedIOException("Could not serialise the list payload", ex);
		}
		return out.toByteArray();
	}

	private static void writeChunk(ObjectMapper mapper, JsonGenerator gen, List<GenericKubernetesResource> chunk) {
		try {
			for (GenericKubernetesResource resource : chunk) {
				mapper.writeValue(gen, ListProjection.forList(resource));
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not serialise a list chunk", ex);
		}
	}

	/** What goes between the array's brackets. */
	@FunctionalInterface
	private interface ArrayBody {

		void writeInto(ObjectMapper mapper, JsonGenerator gen) throws IOException;

	}

}
