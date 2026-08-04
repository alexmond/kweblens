package org.alexmond.kweblens.web.sim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code data} of a ConfigMap or Secret — and, more importantly, <b>how big it
 * is</b>.
 *
 * <p>
 * The seeder used to give every ConfigMap one 12-byte value and every Secret one 8-byte
 * one, so the two kinds that dominate a real cluster's payload were the two smallest
 * objects in the rig. On the live cluster a ConfigMap averages 16.9 KB and a Secret 53.4
 * KB, of which 95% and 99% respectively <em>is</em> the data.
 *
 * <p>
 * <b>The distribution is the point, not the mean.</b> That 53 KB average is made of a
 * long tail: the median Secret is 9.8 KB and the largest on the same cluster is 673 KB —
 * one Helm release blob. A rig where every object is the mean cannot reproduce the
 * failures that matter, because those failures are always about the tail: the drawer that
 * fetches the 673 KB object, the list that happens to contain it, the heap it lands in.
 * So sizes are drawn from four buckets whose shape matches the measured one, and the
 * bucket a given index lands in is deterministic (see {@link SimRandom}) — the big ones
 * are always at the same indices, so a defect found on one is reproducible at
 * {@code size=20}.
 *
 * <p>
 * {@code payloadScale} multiplies every size. It exists because the honest cost of this
 * realism is memory: at {@code size=3000} the Secrets alone are ~150 MB of generated data
 * held in the mock API server's heap. A sweep that needs many rows rather than real ones
 * can turn that down without turning off every other realism this package adds.
 */
final class SimPayloads {

	/**
	 * ConfigMap sizes: application settings, a rendered config file, a ruleset, and — the
	 * tail — a bundled CA set or a dashboard JSON.
	 */
	private static final List<Bucket> CONFIG_MAP = List.of(new Bucket(70, 300, 2_000), new Bucket(92, 6_000, 40_000),
			new Bucket(99, 60_000, 150_000), new Bucket(100, 220_000, 320_000));

	/**
	 * Secret sizes: a service-account token, a TLS keypair, a large keypair or bundle,
	 * and — the tail — a Helm release archive.
	 */
	private static final List<Bucket> SECRET = List.of(new Bucket(55, 3_000, 11_000), new Bucket(85, 15_000, 60_000),
			new Bucket(97, 60_000, 140_000), new Bucket(100, 400_000, 800_000));

	private SimPayloads() {
	}

	/** Which bucket index {@code index} falls in — 0-99, and the same on every run. */
	static int roll(String salt, int index) {
		return new SimRandom(salt, index).between(0, 99);
	}

	static Map<String, String> configMapData(int index, double payloadScale) {
		SimRandom random = new SimRandom("configmap-data", index);
		int bytes = scale(size(random, roll("configmap-roll", index), CONFIG_MAP), payloadScale);
		Map<String, String> data = new LinkedHashMap<>();
		int keys = random.between(1, 6);
		int each = Math.max(16, bytes / keys);
		for (int k = 0; k < keys; k++) {
			data.put(random.word() + "-" + k + ".conf", random.text(each));
		}
		return data;
	}

	/**
	 * A Secret's data, base64 as the API stores it. The shapes are the ones that actually
	 * occupy a cluster, and the key names matter as much as the bytes: the config-usage
	 * check and the drawer both read keys, so {@code token}/{@code tls.crt} exercise
	 * something a generic {@code key} does not.
	 */
	static Map<String, String> secretData(int index, double payloadScale) {
		SimRandom random = new SimRandom("secret-data", index);
		int roll = roll("secret-roll", index);
		int bytes = scale(size(random, roll, SECRET), payloadScale);
		Map<String, String> data = new LinkedHashMap<>();
		if (roll >= 97) {
			// The tail: a Helm release is one enormous value and nothing else.
			data.put("release", random.base64(bytes * 3 / 4));
			return data;
		}
		if (roll < 55) {
			data.put("ca.crt", random.pem("CERTIFICATE", bytes / 8));
			data.put("token", random.base64(bytes / 2));
			data.put("namespace", random.base64(16));
			return data;
		}
		data.put("tls.crt", random.pem("CERTIFICATE", bytes / 3));
		data.put("tls.key", random.pem("PRIVATE KEY", bytes / 3));
		return data;
	}

	private static int size(SimRandom random, int roll, List<Bucket> buckets) {
		for (Bucket bucket : buckets) {
			if (roll < bucket.upTo()) {
				return random.between(bucket.min(), bucket.max());
			}
		}
		return random.between(buckets.getLast().min(), buckets.getLast().max());
	}

	private static int scale(int bytes, double payloadScale) {
		return Math.max(8, (int) Math.round(bytes * payloadScale));
	}

	/** One size band: taken when the 0-99 roll is below {@code upTo}. */
	record Bucket(int upTo, int min, int max) {
	}

}
