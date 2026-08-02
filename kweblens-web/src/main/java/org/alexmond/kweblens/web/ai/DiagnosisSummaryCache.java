package org.alexmond.kweblens.web.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * The LLM summaries produced so far, so reading the diagnosis never buys one.
 *
 * <p>
 * <b>The cache key is the findings, not the scope.</b> Cluster and namespace alone would
 * serve yesterday's verdict about a cluster that has since been rolled — the failure that
 * makes cached analysis untrustworthy. The deterministic findings ARE the model's entire
 * input, so a fingerprint of them is the honest key: a hit means the model would be shown
 * exactly what it was shown before, and anything else is a miss.
 *
 * <p>
 * Only the newest summary per scope is retained, because an older one can never be served
 * again — its fingerprint will not match. What a superseded entry is still good for is
 * saying <em>"this was analysed, and the findings have changed since"</em>, which is why
 * {@link #supersededAt} exists: the panel can offer a re-analysis with a reason instead
 * of silently reverting to "never analysed".
 *
 * <p>
 * <b>In-memory and per-process, like {@code AuditService}.</b> A restart empties it, a
 * second replica has its own, and the least recently used scope is evicted past
 * {@value #MAX_SCOPES}. Nothing here is durable, and the API says so — an evicted or
 * restarted entry costs one more inference call, never a wrong answer.
 */
@Service
public class DiagnosisSummaryCache {

	/**
	 * Scopes retained. Each entry is one short prose summary, so this is kilobytes; the
	 * cap exists so a long-lived process browsing many namespaces cannot grow without
	 * bound.
	 */
	private static final int MAX_SCOPES = 64;

	private static final HexFormat HEX = HexFormat.of();

	/** Field separator inside the hashed canonical form. */
	private static final char FIELD = '\u001f';

	/** Record separator inside the hashed canonical form. */
	private static final char RECORD = '\u001e';

	// Access-ordered, so eviction drops the least recently READ scope rather than merely
	// the
	// least recently written: the namespace someone keeps looking at is the one worth
	// keeping. Access order mutates the map on get(), so even reads have to go through
	// the
	// synchronized wrapper — this must never be unwrapped for a "read-only" fast path.
	private final Map<String, CachedSummary> byScope = Collections.synchronizedMap(new LruScopes());

	/**
	 * A stable fingerprint of a finding list — the cache key's load-bearing part.
	 *
	 * <p>
	 * Every field of every finding is hashed, in order, with unambiguous separators, so
	 * one changed detail message is a different key. SHA-256 for collision resistance
	 * rather than for secrecy: a collision would serve the wrong summary, which is
	 * exactly the failure this mechanism exists to prevent.
	 */
	public static String fingerprint(List<Finding> findings) {
		StringBuilder canonical = new StringBuilder(256);
		for (Finding finding : findings) {
			append(canonical, finding.severity());
			append(canonical, finding.title());
			append(canonical, finding.object());
			append(canonical, finding.detail());
			append(canonical, finding.suggestedFix());
			append(canonical, finding.source());
			canonical.append(RECORD);
		}
		return HEX.formatHex(sha256(canonical.toString()));
	}

	private static void append(StringBuilder out, String value) {
		out.append((value != null) ? value : "").append(FIELD);
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required of every JVM", ex);
		}
	}

	private static String scopeKey(String clusterId, String namespace) {
		return clusterId + FIELD + ((namespace != null) ? namespace : "");
	}

	/**
	 * The cached summary written about exactly these findings, or null.
	 *
	 * <p>
	 * The fingerprint comparison lives here rather than in the caller, so there is no way
	 * to obtain a summary that was written about different findings.
	 */
	public CachedSummary find(String clusterId, String namespace, String fingerprint) {
		CachedSummary hit = this.byScope.get(scopeKey(clusterId, namespace));
		return (hit != null && hit.fingerprint().equals(fingerprint)) ? hit : null;
	}

	/**
	 * When this scope was last analysed, if that analysis no longer describes it — null
	 * when there is a usable summary, or none at all.
	 */
	public Instant supersededAt(String clusterId, String namespace, String fingerprint) {
		CachedSummary hit = this.byScope.get(scopeKey(clusterId, namespace));
		return (hit != null && !hit.fingerprint().equals(fingerprint)) ? hit.producedAt() : null;
	}

	/** Record a freshly produced summary, replacing whatever this scope held. */
	public CachedSummary put(String clusterId, String namespace, String fingerprint, String summary, int findingCount) {
		CachedSummary entry = new CachedSummary(fingerprint, summary, Instant.now(), findingCount);
		this.byScope.put(scopeKey(clusterId, namespace), entry);
		return entry;
	}

	/**
	 * Forget everything. A test seam — nothing in the running app calls it, and there is
	 * deliberately no endpoint for it: a summary is already discarded the moment the
	 * findings change, which is the only invalidation that means anything.
	 */
	public void clear() {
		this.byScope.clear();
	}

	/**
	 * One retained summary.
	 *
	 * @param fingerprint of the findings it was written about — never serve the summary
	 * without matching this
	 * @param summary the model's prose
	 * @param producedAt when the inference call returned, so the panel can say how old
	 * the reading is
	 * @param findingCount how many findings it was written from, for the same reason
	 */
	public record CachedSummary(String fingerprint, String summary, Instant producedAt, int findingCount) {
	}

	/** Access-ordered map that evicts the least recently used scope past the cap. */
	private static final class LruScopes extends LinkedHashMap<String, CachedSummary> {

		private static final long serialVersionUID = 1L;

		LruScopes() {
			super(16, 0.75f, true);
		}

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CachedSummary> eldest) {
			return size() > MAX_SCOPES;
		}

	}

}
