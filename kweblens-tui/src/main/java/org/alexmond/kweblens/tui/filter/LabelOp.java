package org.alexmond.kweblens.tui.filter;

/**
 * The three operators apimachinery's {@code labels.Requirement} really has.
 *
 * <p>
 * There is deliberately no {@code EQUALS} or {@code NOT_EQUALS}: {@code k=v} parses to
 * {@code IN} over one value and {@code k!=v} to {@code NOTIN} over one value, so the
 * absent-key rule is written once.
 */
enum LabelOp {

	IN, NOTIN, EXISTS

}
