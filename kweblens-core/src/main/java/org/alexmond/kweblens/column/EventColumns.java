package org.alexmond.kweblens.column;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * The Event list's kind-specific columns.
 *
 * <p>
 * An Event keeps almost everything at the top level rather than under {@code spec} or
 * {@code status}, so four of the five are plain path reads. The fifth,
 * {@code involvedObject}, is a join of two fields that must survive either being absent:
 * an Event about a deleted object can carry a kind and no name.
 *
 * <p>
 * There is no {@code Status} column here and that is a decision, not an omission (GH#339)
 * — an Event's {@code Warning}/{@code Normal} is a property of a report <em>about</em>
 * another object, not a verdict on the Event, so it stays a {@code Type} column.
 */
final class EventColumns {

	private EventColumns() {
	}

	static List<Column> columns() {
		return List.of(Column.path("type", "Type", "type"), Column.path("reason", "Reason", "reason"),
				new Column("object", "Object", EventColumns::involved), Column.path("message", "Message", "message"),
				Column.path("count", "Count", "count"));
	}

	private static String involved(GenericKubernetesResource event) {
		Object involved = ObjectPath.read(event, "involvedObject");
		String kind = ColumnText.str(ObjectPath.field(involved, "kind"));
		String name = ColumnText.str(ObjectPath.field(involved, "name"));
		if (kind.isEmpty() || name.isEmpty()) {
			return ColumnText.dash(kind + name);
		}
		return kind + "/" + name;
	}

}
