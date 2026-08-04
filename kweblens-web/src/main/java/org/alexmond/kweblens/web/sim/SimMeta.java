package org.alexmond.kweblens.web.sim;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;

/**
 * The metadata a real object carries and a generated one usually does not: a uid, a
 * creation time that is not "now", eight labels rather than one, the annotations a
 * controller and a package manager leave behind, and an owner reference.
 *
 * <p>
 * Two of these are load-bearing beyond looking right. <b>Ages</b> are spread over weeks,
 * so the Age column sorts and formats over its whole range instead of showing the same
 * value in every row. And <b>{@code kubectl.kubernetes.io/last-applied-configuration}</b>
 * is a whole serialised manifest stored as one annotation string — on the live cluster it
 * is the single largest annotation on anything applied with client-side apply, which is
 * why the redaction path has a rule for it and why a simulator without one could never
 * exercise that rule.
 */
final class SimMeta {

	/**
	 * The oldest an object gets: ages spread over four weeks, as on a settled cluster.
	 */
	private static final int MAX_AGE_HOURS = 28 * 24;

	private SimMeta() {
	}

	/** A stable uid for (kind, index) — the same one every run, as a real uid is. */
	static String uid(String kind, int index) {
		SimRandom random = new SimRandom("uid-" + kind, index);
		return random.hex(8) + '-' + random.hex(4) + '-' + random.hex(4) + '-' + random.hex(4) + '-' + random.hex(12);
	}

	/**
	 * A creation time between one hour and four weeks ago. Relative to now rather than
	 * fixed: a hard-coded date makes every object in the rig read "1y" once the rig is a
	 * year old, and Age is a rendered column.
	 */
	static String created(String kind, int index) {
		int hours = new SimRandom("age-" + kind, index).between(1, MAX_AGE_HOURS);
		return Instant.now().minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();
	}

	static String resourceVersion(String kind, int index) {
		return String.valueOf(new SimRandom("rv-" + kind, index).between(100_000, 99_999_999));
	}

	/**
	 * The label set a Helm-installed workload carries. Not decoration: every one of these
	 * keys costs a path in {@code managedFields} too, which is a large part of why real
	 * objects are big.
	 */
	static Map<String, String> appLabels(int index, String component) {
		SimRandom random = new SimRandom("labels", index);
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("app", "sim");
		labels.put("app.kubernetes.io/name", "sim-" + component);
		labels.put("app.kubernetes.io/instance", "sim-release-" + (index % 7));
		labels.put("app.kubernetes.io/version", "1." + (index % 12) + "." + (index % 5));
		labels.put("app.kubernetes.io/managed-by", "Helm");
		labels.put("app.kubernetes.io/component", component);
		labels.put("app.kubernetes.io/part-of", "sim-platform");
		labels.put("helm.sh/chart", "sim-" + component + "-1." + (index % 12) + "." + (index % 5));
		labels.put("pod-template-hash", random.hex(9));
		return labels;
	}

	/** The annotations Helm and the usual controllers leave on an installed object. */
	static Map<String, String> commonAnnotations(int index, String kind, String namespace) {
		SimRandom random = new SimRandom("ann-" + kind, index);
		Map<String, String> annotations = new LinkedHashMap<>();
		annotations.put("meta.helm.sh/release-name", "sim-release-" + (index % 7));
		annotations.put("meta.helm.sh/release-namespace", namespace);
		annotations.put("checksum/config", random.hex(64));
		annotations.put("prometheus.io/scrape", "true");
		annotations.put("prometheus.io/port", "9102");
		return annotations;
	}

	/**
	 * A {@code last-applied-configuration} annotation: one JSON manifest, as a string, of
	 * roughly {@code bytes}. Padded with env entries because that is what makes a real
	 * one big — a manifest with thirty environment variables carries all thirty here too.
	 */
	static String lastApplied(String apiVersion, String kind, String name, String namespace, int bytes) {
		StringBuilder json = new StringBuilder(bytes + 256);
		json.append("{\"apiVersion\":\"")
			.append(apiVersion)
			.append("\",\"kind\":\"")
			.append(kind)
			.append("\",\"metadata\":{\"annotations\":{},\"labels\":{\"app\":\"sim\"},\"name\":\"")
			.append(name)
			.append("\",\"namespace\":\"")
			.append(namespace)
			.append("\"},\"spec\":{\"containers\":[{\"image\":\"registry.example.test/sim/app:1.4.2\","
					+ "\"name\":\"app\",\"env\":[");
		SimRandom random = new SimRandom("last-applied-" + kind, name.hashCode());
		for (int i = 0; json.length() < bytes; i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append("{\"name\":\"SIM_")
				.append(random.word().toUpperCase(Locale.ROOT))
				.append('_')
				.append(i)
				.append("\",\"value\":\"")
				.append(random.word())
				.append('-')
				.append(random.hex(12))
				.append("\"}");
		}
		return json.append("]}]}}").toString();
	}

	/** The owner reference a controller writes onto everything it creates. */
	static OwnerReference owner(String apiVersion, String kind, String name, String uid) {
		return new OwnerReferenceBuilder().withApiVersion(apiVersion)
			.withKind(kind)
			.withName(name)
			.withUid(uid)
			.withController(true)
			.withBlockOwnerDeletion(true)
			.build();
	}

	/** Assemble the metadata every seeded object shares. */
	static ObjectMeta meta(String kind, int index, String name, String namespace, Map<String, String> labels,
			Map<String, String> annotations) {
		return new ObjectMetaBuilder().withName(name)
			.withNamespace(namespace)
			.withUid(uid(kind, index))
			.withResourceVersion(resourceVersion(kind, index))
			.withCreationTimestamp(created(kind, index))
			.withGeneration(1L)
			.withLabels(labels)
			.withAnnotations(annotations)
			.build();
	}

}
