package org.alexmond.kweblens.web.access;

import java.util.Map;

/**
 * What the service account this deployment runs as may do with one kind, in one scope.
 *
 * <p>
 * The wire shape the SPA consumes. It carries the scope it was asked about — the kind and
 * the namespace — because an answer without its question is how a verdict about one
 * namespace ends up disabling a button in another.
 *
 * @param kind the Kubernetes kind the verdicts are about
 * @param namespace the namespace they are about, or {@code null} when the question was
 * cluster-wide (a cluster-scoped kind, or a list spanning every namespace)
 * @param verbs one entry per verb asked; a verb the server did not ask about is simply
 * absent, which the client reads as unknown
 */
public record KindAccess(String kind, String namespace, Map<String, VerbAccess> verbs) {
}
