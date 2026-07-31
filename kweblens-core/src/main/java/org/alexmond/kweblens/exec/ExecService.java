package org.alexmond.kweblens.exec;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.TtyExecOutputErrorable;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Opens an interactive shell ({@code sh}) in a pod/container. The returned
 * {@link ExecWatch} streams the process's combined output to the caller-supplied
 * {@link OutputStream} (bridged to a WebSocket by the web layer) and accepts keystrokes
 * via {@link ExecWatch#getInput()}. A blank container selects the pod's default
 * container.
 */
@Service
@RequiredArgsConstructor
public class ExecService {

	/** Cap on captured stderr — it is a diagnostic message, never payload. */
	private static final long MAX_STDERR_BYTES = 8192;

	private final ClusterRegistry clusters;

	public ExecWatch exec(String clusterId, String namespace, String pod, String container, OutputStream output,
			ExecListener listener) {
		return session(clusterId, namespace, pod, container, output, listener).exec("sh");
	}

	/**
	 * Attach to a running container's process (like {@code kubectl attach}) — streams its
	 * existing stdout/stderr and forwards keystrokes to its stdin, rather than starting a
	 * new shell. A blank container selects the pod's default container.
	 */
	public ExecWatch attach(String clusterId, String namespace, String pod, String container, OutputStream output,
			ExecListener listener) {
		return session(clusterId, namespace, pod, container, output, listener).attach();
	}

	/**
	 * Run one command to completion in a container and collect its output — the
	 * non-interactive counterpart of {@link #exec}, used by surfaces that need a single
	 * answer rather than a terminal (the pod file browser).
	 *
	 * <p>
	 * Deliberately <strong>no TTY</strong>: a PTY merges stdout and stderr onto one
	 * stream and performs newline translation, which silently corrupts binary payloads.
	 * Without one, fabric8 keeps the two channels separate and the bytes intact.
	 *
	 * <p>
	 * {@code command} is passed as an argv array straight to the Kubernetes exec API — no
	 * shell parses it — so caller-supplied values (file paths) are safe as long as they
	 * are separate elements and never spliced into a shell string.
	 * @param command argv; element 0 is the executable
	 * @param stdin bytes to feed the process, or {@code null} to attach no stdin
	 * @param maxOutputBytes cap on captured stdout; beyond it output is dropped and
	 * {@link ExecResult#truncated()} is set
	 * @param timeout how long to wait for the process to exit
	 */
	public ExecResult run(String clusterId, String namespace, String pod, String container, List<String> command,
			byte[] stdin, long maxOutputBytes, Duration timeout) {
		PodResource podResource = clusters.require(clusterId).pods().inNamespace(namespace).withName(pod);
		ContainerResource resource = (StringUtils.hasText(container)) ? podResource.inContainer(container)
				: podResource;
		BoundedOutputStream out = new BoundedOutputStream(maxOutputBytes);
		BoundedOutputStream err = new BoundedOutputStream(MAX_STDERR_BYTES);
		TtyExecOutputErrorable target = (stdin != null) ? resource.readingInput(new ByteArrayInputStream(stdin))
				: resource;
		try (ExecWatch watch = target.writingOutput(out).writingError(err).exec(command.toArray(new String[0]))) {
			Integer exit = watch.exitCode().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			return new ExecResult((exit != null) ? exit : -1, out.toByteArray(), err.toByteArray(), out.isTruncated());
		}
		catch (TimeoutException ex) {
			throw new ExecFailedException(
					"command timed out after " + timeout.toSeconds() + "s in " + namespace + "/" + pod, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ExecFailedException("interrupted while running a command in " + namespace + "/" + pod, ex);
		}
		catch (ExecutionException ex) {
			throw new ExecFailedException(rootMessage(ex), ex);
		}
	}

	private String rootMessage(Throwable error) {
		Throwable cause = (error.getCause() != null) ? error.getCause() : error;
		while (cause.getCause() != null && cause.getMessage() == null) {
			cause = cause.getCause();
		}
		return (cause.getMessage() != null) ? cause.getMessage() : cause.getClass().getSimpleName();
	}

	private io.fabric8.kubernetes.client.dsl.Execable session(String clusterId, String namespace, String pod,
			String container, OutputStream output, ExecListener listener) {
		PodResource podResource = clusters.require(clusterId).pods().inNamespace(namespace).withName(pod);
		ContainerResource target = StringUtils.hasText(container) ? podResource.inContainer(container) : podResource;
		return target.redirectingInput().writingOutput(output).writingError(output).withTTY().usingListener(listener);
	}

}
