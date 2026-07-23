package org.alexmond.kweblens.cli;

import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Entry point for the kweblens CLI — a small, dependency-light cluster inspector. Runs as
 * a non-web Spring Boot app that hands its arguments to a picocli command and exits with
 * the command's status.
 */
@SpringBootApplication
@RequiredArgsConstructor
public class KweblensCliApplication implements CommandLineRunner, ExitCodeGenerator {

	private final CommandLine.IFactory factory;

	private final KweblensCommand command;

	private int exitCode;

	public static void main(String[] args) {
		int code = SpringApplication
			.exit(new SpringApplicationBuilder(KweblensCliApplication.class).web(WebApplicationType.NONE).run(args));
		System.exit(code);
	}

	@Override
	public void run(String... args) {
		this.exitCode = new CommandLine(command, factory).execute(args);
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}

}
