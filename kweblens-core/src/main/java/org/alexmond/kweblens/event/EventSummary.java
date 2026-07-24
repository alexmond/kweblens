package org.alexmond.kweblens.event;

/**
 * One Kubernetes event, projected for the dashboard/API.
 *
 * @param type event type ({@code Normal} / {@code Warning})
 * @param reason short machine reason (e.g. {@code BackOff})
 * @param object the involved object as {@code Kind/name}
 * @param namespace the event's namespace
 * @param message human-readable message
 * @param age compact age since the event's last occurrence
 */
public record EventSummary(String type, String reason, String object, String namespace, String message, String age) {
}
