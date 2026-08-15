package org.alexmond.kweblens.tui;

import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Entry point for {@code kweblens-tui} — a terminal cluster browser that talks to the
 * cluster directly, through {@code kweblens-core}, on the operator's own kubeconfig.
 *
 * <p>
 * Runs as a non-web Spring Boot app: {@link WebApplicationType#NONE} is set explicitly
 * rather than left to auto-detection, because auto-detection is a property of what
 * happens to be on the classpath and this app's not-being-a-server is a decision. There
 * is no servlet container in the dependency tree to detect either —
 * {@code TuiDependencyTest} fails the build if one appears.
 *
 * <p>
 * The component scan reaches all of {@code org.alexmond.kweblens} because the access
 * services this module is a thin shell over live in {@code kweblens-core}'s packages.
 * Neither {@code kweblens-web} nor {@code kweblens-cli} is on this classpath, so the wide
 * scan cannot pick either of them up — and that is asserted, not assumed.
 */
@SpringBootApplication(scanBasePackages = "org.alexmond.kweblens")
@ConfigurationPropertiesScan("org.alexmond.kweblens")
@EnableCaching
@RequiredArgsConstructor
public class KweblensTuiApplication implements CommandLineRunner, ExitCodeGenerator {

	private final CommandLine.IFactory factory;

	private final TuiCommand command;

	private int exitCode;

	public static void main(String[] args) {
		int code = SpringApplication
			.exit(new SpringApplicationBuilder(KweblensTuiApplication.class).web(WebApplicationType.NONE).run(args));
		System.exit(code);
	}

	@Override
	public void run(String... args) {
		this.exitCode = new CommandLine(this.command, this.factory).execute(args);
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

}
