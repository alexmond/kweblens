package org.alexmond.kweblens.web.helm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.alexmond.jhelm.core.model.Chart;
import org.alexmond.jhelm.core.service.ChartLoader;
import org.alexmond.jhelm.core.service.RepoManager;

import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StringUtils;

/**
 * Resolves a {@code repo/chart} reference to a loaded jhelm {@link Chart} for install and
 * upgrade. Uses a jhelm {@link RepoManager} — pinned to a kweblens-owned config/cache
 * directory so it never touches the operator's {@code ~/.config/helm} — to pull the chart
 * archive, then {@link ChartLoader} to load it. The configured repositories
 * ({@link HelmProperties}) are added and their indexes refreshed on first use.
 */
@Slf4j
@Component
public class HelmChartResolver {

	private final HelmProperties properties;

	private final RepoManager repoManager;

	private final ChartLoader chartLoader = new ChartLoader();

	private final ReentrantLock lock = new ReentrantLock();

	private boolean reposReady;

	public HelmChartResolver(HelmProperties properties) {
		this.properties = properties;
		Path base = Path.of(System.getProperty("java.io.tmpdir"), "kweblens-helm");
		this.repoManager = new RepoManager(base.resolve("repositories.yaml").toString());
		this.repoManager.setRepositoryCacheOverride(base.resolve("cache").toString());
	}

	/** Pull and load {@code repo/chart} at {@code version} (blank version = latest). */
	public Chart resolve(String repository, String chart, String version) {
		lock.lock();
		Path work = null;
		try {
			ensureRepos();
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

	private void ensureRepos() {
		if (reposReady) {
			return;
		}
		for (HelmProperties.Repository repo : properties.getRepositories()) {
			if (!StringUtils.hasText(repo.getName()) || !StringUtils.hasText(repo.getUrl())) {
				continue;
			}
			try {
				repoManager.addRepo(repo.getName(), repo.getUrl());
				repoManager.updateRepo(repo.getName());
			}
			catch (IOException ex) {
				log.warn("Could not initialise Helm repo '{}': {}", repo.getName(), ex.getMessage());
			}
		}
		reposReady = true;
	}

}
