package org.alexmond.kweblens.web.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * Strips what must not leave the server through a tool call.
 *
 * <p>
 * <b>Why this exists at the tool boundary and not deeper.</b> The dashboard deliberately
 * shows an operator the Secrets they are entitled to read — that is the product working.
 * The tool surface has a different rule, because its output goes to a model, over a
 * network, into somebody's inference logs and possibly into training data. So the
 * asymmetry is intentional and belongs exactly here; pushing redaction into the access
 * layer would break the SPA, and leaving it to each tool would mean one forgotten method
 * hands over every credential in the cluster.
 *
 * <p>
 * Keys are kept and values replaced. "There is a Secret named {@code db-credentials} with
 * a key {@code password}" is genuinely useful for diagnosis — a container failing with
 * {@code CreateContainerConfigError} because a key is missing is a real failure mode —
 * and it can be said without disclosing anything.
 *
 * <p>
 * {@code managedFields} goes too, for a different reason: it is server-side bookkeeping
 * that no diagnosis needs, and on a real object it is routinely a third of the payload.
 */
final class ToolRedaction {

	private static final String REDACTED = "<redacted by kweblens>";

	private ToolRedaction() {
	}

	/**
	 * Return the object safe for tool output. Mutates and returns the same instance: it
	 * was deserialised for this call and is not shared.
	 */
	static GenericKubernetesResource forTool(GenericKubernetesResource resource) {
		if (resource == null) {
			return null;
		}
		if (resource.getMetadata() != null) {
			resource.getMetadata().setManagedFields(null);
			redactAnnotation(resource);
		}
		if ("Secret".equals(resource.getKind())) {
			redactValues(resource, "data");
			redactValues(resource, "stringData");
		}
		return resource;
	}

	/**
	 * {@code kubectl.kubernetes.io/last-applied-configuration} is a verbatim copy of the
	 * object as applied — including, for a Secret, its data. Redacting the live fields
	 * while leaving a full copy in an annotation would be a leak that looks like it was
	 * handled.
	 */
	private static void redactAnnotation(GenericKubernetesResource resource) {
		Map<String, String> annotations = resource.getMetadata().getAnnotations();
		if (annotations != null && annotations.containsKey("kubectl.kubernetes.io/last-applied-configuration")) {
			Map<String, String> copy = new LinkedHashMap<>(annotations);
			copy.put("kubectl.kubernetes.io/last-applied-configuration", REDACTED);
			resource.getMetadata().setAnnotations(copy);
		}
	}

	@SuppressWarnings("unchecked")
	private static void redactValues(GenericKubernetesResource resource, String field) {
		Object raw = resource.getAdditionalProperties().get(field);
		if (!(raw instanceof Map<?, ?> map)) {
			return;
		}
		Map<String, Object> redacted = new LinkedHashMap<>();
		((Map<String, Object>) map).keySet().forEach((key) -> redacted.put(key, REDACTED));
		resource.getAdditionalProperties().put(field, redacted);
	}

}
