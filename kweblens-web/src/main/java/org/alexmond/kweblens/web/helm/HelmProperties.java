package org.alexmond.kweblens.web.helm;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Helm chart-repository configuration for the charts browser. Releases are read from the
 * cluster (see {@link HelmService}); charts come from these HTTP chart repositories'
 * {@code index.yaml}. Configure under {@code kweblens.helm.*}.
 */
@Data
@ConfigurationProperties(prefix = "kweblens.helm")
public class HelmProperties {

	/**
	 * Chart repositories to browse (classic HTTP repos exposing an {@code index.yaml}).
	 */
	private List<Repository> repositories = new ArrayList<>();

	/** How long a fetched repo index is cached before it is refetched. */
	private long indexCacheSeconds = 300;

	/**
	 * Directory for the reusable values-file library (named {@code *.yaml} files the user
	 * saves and reuses across installs/upgrades). Defaults under the persisted Helm home
	 * so it survives restarts on a mounted volume.
	 */
	private String valuesPath;

	@Data
	public static class Repository {

		/** Short repo name shown in the browser and used to reference charts. */
		private String name;

		/**
		 * Base URL of the chart repository (the directory that holds {@code index.yaml}).
		 */
		private String url;

	}

}
