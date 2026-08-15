package org.alexmond.kweblens.tui.data;

/**
 * A live thing the TUI is holding open on the cluster's behalf — today a watch — that
 * must be released when the view that opened it goes away.
 *
 * <p>
 * {@link AutoCloseable} with the checked exception removed, so a view can close one in a
 * {@code finally} or a try-with-resources without a catch block that would only ever
 * swallow. The implementation is responsible for making close actually release the
 * connection, not merely set a flag.
 */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

	@Override
	void close();

}
