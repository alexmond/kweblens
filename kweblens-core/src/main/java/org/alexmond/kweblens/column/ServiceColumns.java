package org.alexmond.kweblens.column;

import java.util.List;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * The Service list's kind-specific columns.
 *
 * <p>
 * {@code Ports} is the only computed one, and the thing it encodes is that a port entry
 * may omit its protocol: the API server defaults it to TCP without writing it down, so a
 * consumer that printed the field verbatim would show a bare number for the commonest
 * case and {@code 53/UDP} beside it. The default belongs on the server side of the seam,
 * once, rather than in every renderer that meets a Service.
 */
final class ServiceColumns {

	private static final String DEFAULT_PROTOCOL = "TCP";

	private ServiceColumns() {
	}

	static List<Column> columns() {
		return List.of(Column.path("type", "Type", "spec.type"),
				Column.path("clusterip", "Cluster IP", "spec.clusterIP"),
				new Column("ports", "Ports", ServiceColumns::ports));
	}

	private static String ports(GenericKubernetesResource service) {
		return ColumnText.dash(ObjectPath.list(service, "spec.ports")
			.stream()
			.map(ServiceColumns::port)
			.collect(Collectors.joining(", ")));
	}

	private static String port(Object entry) {
		String protocol = ColumnText.str(ObjectPath.field(entry, "protocol"));
		return ColumnText.str(ObjectPath.field(entry, "port")) + "/"
				+ (protocol.isEmpty() ? DEFAULT_PROTOCOL : protocol);
	}

}
