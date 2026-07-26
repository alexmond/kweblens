package org.alexmond.kweblens.web.helm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.alexmond.jhelm.core.model.Chart;
import org.alexmond.jhelm.core.service.ChartLoader;
import org.alexmond.jhelm.core.service.RepoManager;

import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;

/**
 * Resolves a {@code repo/chart} reference to a loaded jhelm {@link Chart} for install and
 * upgrade. Uses the <strong>shared</strong> jhelm {@link RepoManager} bean (from
 * JhelmCoreAutoConfiguration, persisted under {@code jhelm.core.config-path}) so installs
 * draw from the exact same repository set that jhelm-rest's {@code /api/v1/helm/repos}
 * API manages — a repo added at runtime is immediately usable for install. Repositories
 * are seeded on startup by {@link JhelmRestIntegrationConfig}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelmChartResolver {

	private final RepoManager repoManager;

	private final ChartLoader chartLoader;

	private final ReentrantLock lock = new ReentrantLock();

	/** Pull and load {@code repo/chart} at {@code version} (blank version = latest). */
	public Chart resolve(String repository, String chart, String version) {
		lock.lock();
		Path work = null;
		try {
			work = Files.createTempDirectory("kweblens-chart-");
			repoManager.pull(repository + "/" + chart, StringUtils.hasText(version) ? version : "", work.toString());
			return chartLoader.load(chartDir(work).toFile());
		}
		catch (IOException | RuntimeException ex) {
			throw new HelmException(
					"Could not resolve chart " + repository + "/" + chart + " " + version + ": " + ex.getMessage(), ex);
		}
		finally {
			if (work != null) {
				FileSystemUtils.deleteRecursively(work.toFile());
			}
			lock.unlock();
		}
	}

	private Path chartDir(Path work) throws IOException {
		try {
			return ChartLoader.findChartDir(work);
		}
		catch (IOException notExtracted) {
			File archive = firstArchive(work)
				.orElseThrow(() -> new HelmException("No chart archive was pulled", notExtracted));
			repoManager.untar(archive, work.toFile());
			return ChartLoader.findChartDir(work);
		}
	}

	private Optional<File> firstArchive(Path work) throws IOException {
		try (Stream<Path> entries = Files.list(work)) {
			return entries.filter((p) -> p.toString().endsWith(".tgz")).findFirst().map(Path::toFile);
		}
	}

}
