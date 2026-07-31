package org.alexmond.kweblens.web.files;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;

import org.alexmond.kweblens.exec.ExecFailedException;
import org.alexmond.kweblens.exec.ExecResult;
import org.alexmond.kweblens.exec.ExecService;
import org.alexmond.kweblens.web.security.AuditEntry;
import org.alexmond.kweblens.web.security.AuditService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

/**
 * The file browser's behaviour with the container faked out: {@link ExecService} is
 * mocked so every branch that matters — the off-by-default gate, the size caps, the
 * refusal to read a directory, root confinement against the path the container actually
 * resolved, the audit entries, and the "this image has no shell" message — is exercised
 * without a cluster.
 */
class PodFileServiceTest {

	private final ExecService exec = mock(ExecService.class);

	private final Map<String, ExecResult> responses = new HashMap<>();

	private final List<List<String>> commands = new ArrayList<>();

	private final List<byte[]> stdins = new ArrayList<>();

	private final AuditService audit = new AuditService();

	private FilesProperties properties;

	private PodFileService service;

	private static byte[] nul(String... fields) {
		StringBuilder text = new StringBuilder();
		for (String field : fields) {
			text.append(field).append('\0');
		}
		return text.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	@BeforeEach
	void setUp() {
		properties = new FilesProperties();
		properties.setEnabled(true);
		service = new PodFileService(exec, properties, audit);
		given(exec.run(any(), any(), any(), any(), any(), any(), anyLong(), any())).willAnswer((invocation) -> {
			List<String> command = invocation.getArgument(4);
			commands.add(command);
			stdins.add(invocation.getArgument(5));
			return responses.getOrDefault(scriptName(command.get(2)),
					new ExecResult(0, new byte[0], new byte[0], false));
		});
	}

	private String scriptName(String script) {
		if (PodFileScripts.LIST.equals(script)) {
			return "list";
		}
		if (PodFileScripts.STAT.equals(script)) {
			return "stat";
		}
		if (PodFileScripts.READ.equals(script)) {
			return "read";
		}
		if (PodFileScripts.WRITE.equals(script)) {
			return "write";
		}
		return "delete";
	}

	private void respond(String script, int exitCode, byte[] stdout, String stderr) {
		responses.put(script, new ExecResult(exitCode, stdout, utf8(stderr), false));
	}

	private void statResponds(String type, String size, String realPath) {
		respond("stat", 0, nul(type, size, "644", "1700000000", "root", "root", "", realPath), "");
	}

	@Test
	void isOffUntilExplicitlyEnabled() {
		properties.setEnabled(false);

		assertThatThrownBy(() -> service.list("c", "ns", "pod", null, "/"))
			.isInstanceOfSatisfying(PodFileException.class, (ex) -> {
				assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
				assertThat(ex.getCode()).isEqualTo("files-disabled");
			})
			.hasMessageContaining("kweblens.files.enabled");
	}

	@Test
	void listsADirectoryAndPassesThePathAsAnArgumentNotShellText() {
		respond("list", 0, nul("/etc", "nginx.conf", "f", "10|644|1|root|root", "", ""), "");

		PodDirectoryListing listing = service.list("c", "ns", "pod", "app", "/etc/./");

		assertThat(listing.entries()).extracting(PodFileEntry::name).containsExactly("nginx.conf");
		List<String> command = commands.get(0);
		assertThat(command.get(0)).isEqualTo("sh");
		assertThat(command.get(1)).isEqualTo("-c");
		assertThat(command.get(3)).isEqualTo(PodFileScripts.ARGV0);
		// The normalised path is its own argv element; nothing is spliced into the
		// script.
		assertThat(command.get(4)).isEqualTo("/etc");
	}

	@Test
	void readsAFileAndRecordsTheReadInTheAuditTrail() {
		statResponds("f", "5", "/etc/motd");
		respond("read", 0, utf8("hello"), "");

		PodFileContent content = service.read("c", "ns", "pod", "app", "/etc/motd");

		assertThat(content.content()).isEqualTo("hello");
		assertThat(content.binary()).isFalse();
		assertThat(content.editable()).isTrue();
		assertThat(audit.recent()).extracting(AuditEntry::action).contains("file-read");
		assertThat(audit.recent()).extracting(AuditEntry::target).contains("Pod/ns/pod[app]:/etc/motd");
	}

	@Test
	void refusesAFileLargerThanTheReadCapInsteadOfTruncatingIt() {
		properties.setMaxReadBytes(1024);
		statResponds("f", "999999", "/var/log/huge.log");

		assertThatThrownBy(() -> service.read("c", "ns", "pod", null, "/var/log/huge.log"))
			.isInstanceOfSatisfying(PodFileException.class,
					(ex) -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE))
			.hasMessageContaining("max-read-bytes");
	}

	@Test
	void refusesToReadADirectoryOrAMissingFile() {
		statResponds("d", "4096", "/etc");
		assertThatThrownBy(() -> service.read("c", "ns", "pod", null, "/etc")).isInstanceOfSatisfying(
				PodFileException.class, (ex) -> assertThat(ex.getCode()).isEqualTo("is-a-directory"));

		statResponds("x", "", "/nope");
		assertThatThrownBy(() -> service.read("c", "ns", "pod", null, "/nope")).isInstanceOfSatisfying(
				PodFileException.class, (ex) -> assertThat(ex.getCode()).isEqualTo("file-not-found"));
	}

	@Test
	void writesTheExactBytesAndTellsTheContainerHowManyToExpect() {
		statResponds("f", "3", "/app/config.yaml");

		service.write("c", "ns", "pod", "app", "/app/config.yaml",
				new PodFileWrite(null, Base64.getEncoder().encodeToString(new byte[] { 0x00, 0x01, 0x02 })));

		byte[] sent = stdins.get(stdins.size() - 1);
		assertThat(sent).isEqualTo(new byte[] { 0x00, 0x01, 0x02 });
		List<String> command = commands.get(commands.size() - 1);
		assertThat(command.get(4)).isEqualTo("/app/config.yaml");
		assertThat(command.get(5)).isEqualTo("3");
		assertThat(audit.recent()).extracting(AuditEntry::action).contains("file-write=3B");
	}

	@Test
	void leavesTheTargetAloneWhenTheContainerCannotVerifyTheUpload() {
		statResponds("f", "3", "/app/config.yaml");
		respond("write", 6, new byte[0], "size-mismatch\n");

		assertThatThrownBy(
				() -> service.write("c", "ns", "pod", "app", "/app/config.yaml", new PodFileWrite("abc", null)))
			.isInstanceOfSatisfying(PodFileException.class,
					(ex) -> assertThat(ex.getCode()).isEqualTo("write-unverified"))
			.hasMessageContaining("left unchanged");
	}

	@Test
	void reportsAReadOnlyMountRatherThanAGenericFailure() {
		statResponds("f", "3", "/etc/config/app.yaml");
		respond("write", 5, new byte[0], "cannot-write\n");

		assertThatThrownBy(
				() -> service.write("c", "ns", "pod", "app", "/etc/config/app.yaml", new PodFileWrite("abc", null)))
			.isInstanceOfSatisfying(PodFileException.class,
					(ex) -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN))
			.hasMessageContaining("read-only");
	}

	@Test
	void refusesEveryMutationWhenTheBrowserIsReadOnly() {
		properties.setWritable(false);

		assertThatThrownBy(() -> service.write("c", "ns", "pod", null, "/x", new PodFileWrite("a", null)))
			.isInstanceOfSatisfying(PodFileException.class,
					(ex) -> assertThat(ex.getCode()).isEqualTo("files-read-only"));
		assertThatThrownBy(() -> service.delete("c", "ns", "pod", null, "/x")).isInstanceOfSatisfying(
				PodFileException.class, (ex) -> assertThat(ex.getCode()).isEqualTo("files-read-only"));
	}

	@Test
	void deletesAFileAndAuditsIt() {
		statResponds("f", "3", "/tmp/scratch");

		service.delete("c", "ns", "pod", "app", "/tmp/scratch");

		assertThat(audit.recent()).extracting(AuditEntry::action).contains("file-delete");
	}

	@Test
	void refusesToDeleteADirectory() {
		statResponds("d", "4096", "/tmp/dir");
		respond("delete", 4, new byte[0], "is-a-directory\n");

		assertThatThrownBy(() -> service.delete("c", "ns", "pod", null, "/tmp/dir")).isInstanceOfSatisfying(
				PodFileException.class, (ex) -> assertThat(ex.getCode()).isEqualTo("is-a-directory"));
	}

	@Test
	void enforcesAllowedRootsAgainstThePathTheContainerResolved() {
		properties.setAllowedRoots(List.of("/data"));
		// The request looks confined, but the symlink lands on the service-account token.
		statResponds("f", "10", "/var/run/secrets/kubernetes.io/serviceaccount/token");

		assertThatThrownBy(() -> service.read("c", "ns", "pod", null, "/data/innocent")).isInstanceOfSatisfying(
				PodFileException.class, (ex) -> assertThat(ex.getCode()).isEqualTo("path-outside-roots"));
	}

	@Test
	void failsClosedWhenTheResolvedPathCannotBeDetermined() {
		properties.setAllowedRoots(List.of("/data"));
		statResponds("f", "10", "");

		assertThatThrownBy(() -> service.read("c", "ns", "pod", null, "/data/file")).isInstanceOfSatisfying(
				PodFileException.class, (ex) -> assertThat(ex.getCode()).isEqualTo("unresolvable-path"));
	}

	@Test
	void rejectsAPathOutsideTheRootsBeforeTouchingTheCluster() {
		properties.setAllowedRoots(List.of("/data"));

		assertThatThrownBy(() -> service.list("c", "ns", "pod", null, "/var/run/secrets")).isInstanceOfSatisfying(
				PodFileException.class, (ex) -> assertThat(ex.getCode()).isEqualTo("path-outside-roots"));
		assertThat(commands).isEmpty();
	}

	@Test
	void saysTheImageHasNoShellInsteadOfShowingAnEmptyTree() {
		willThrow(new ExecFailedException("executable file not found in $PATH")).given(exec)
			.run(any(), any(), any(), any(), any(), any(), anyLong(), any());

		assertThatThrownBy(() -> service.list("c", "ns", "pod", null, "/"))
			.isInstanceOfSatisfying(PodFileException.class, (ex) -> {
				assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
				assertThat(ex.getCode()).isEqualTo("no-shell");
			})
			.hasMessageContaining("distroless");
	}

	@Test
	void surfacesATimeoutAsAGatewayTimeout() {
		willThrow(new ExecFailedException("command timed out after 20s in ns/pod")).given(exec)
			.run(any(), any(), any(), any(), any(), any(), anyLong(), any());

		assertThatThrownBy(() -> service.list("c", "ns", "pod", null, "/")).isInstanceOfSatisfying(
				PodFileException.class, (ex) -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
	}

}
