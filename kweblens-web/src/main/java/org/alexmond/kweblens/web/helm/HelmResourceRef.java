package org.alexmond.kweblens.web.helm;

/**
 * One Kubernetes object managed by a Helm release, parsed from the release's rendered
 * manifest. Used to link a release to the actual resources it created.
 *
 * @param apiVersion the object's apiVersion (e.g. {@code apps/v1})
 * @param kind the object's kind (e.g. {@code Deployment})
 * @param namespace the object's namespace (the release namespace when the manifest omits
 * it)
 * @param name the object's name
 */
public record HelmResourceRef(String apiVersion, String kind, String namespace, String name) {
}
