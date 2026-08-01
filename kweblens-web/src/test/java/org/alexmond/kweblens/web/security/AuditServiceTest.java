package org.alexmond.kweblens.web.security;

import java.time.Instant;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory ring is the live view; the {@code kweblens.audit} logger is the copy that
 * survives a restart, so both are asserted here.
 */
class AuditServiceTest {

	private Logger auditLogger;

	private ListAppender<ILoggingEvent> appender;

	private boolean additive;

	@BeforeEach
	void captureAuditLog() {
		this.auditLogger = (Logger) LoggerFactory.getLogger(AuditService.AUDIT_LOGGER);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.additive = this.auditLogger.isAdditive();
		// Keep the captured lines out of the build console, but assert on them here.
		this.auditLogger.setAdditive(false);
		this.auditLogger.addAppender(this.appender);
	}

	@AfterEach
	void releaseAuditLog() {
		this.auditLogger.detachAppender(this.appender);
		this.auditLogger.setAdditive(this.additive);
		this.appender.stop();
	}

	@Test
	void recordsNewestFirstWithTheCurrentUser() {
		AuditService audit = new AuditService();
		audit.record("c1", "apply", "ConfigMap/default/x");
		audit.record("c1", "delete", "Pod/default/y");

		List<AuditEntry> recent = audit.recent();

		assertThat(recent).hasSize(2);
		assertThat(recent.get(0).action()).isEqualTo("delete");
		assertThat(recent.get(1).action()).isEqualTo("apply");
		assertThat(recent.get(0).user()).isEqualTo("anonymous");
		assertThat(recent.get(0).cluster()).isEqualTo("c1");
		assertThat(recent.get(0).timestamp()).isNotNull();
	}

	@Test
	void writesOneStructuredLineToTheDedicatedAuditLogger() {
		AuditService audit = new AuditService();

		audit.record("prod", "delete", "Pod/web/nginx");

		assertThat(this.appender.list).hasSize(1);
		ILoggingEvent event = this.appender.list.get(0);
		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(event.getFormattedMessage()).startsWith("kweblens-audit seq=1 ts=")
			.contains(" user=\"anonymous\"")
			.contains(" cluster=\"prod\"")
			.contains(" action=\"delete\"")
			.endsWith(" target=\"Pod/web/nginx\"");
	}

	@Test
	void sequenceNumbersMakeAGapInShippedLogsVisible() {
		AuditService audit = new AuditService();

		audit.record("c1", "apply", "ConfigMap/default/x");
		audit.record("c1", "restart", "Deployment/default/y");

		assertThat(this.appender.list.get(0).getFormattedMessage()).contains(" seq=1 ");
		assertThat(this.appender.list.get(1).getFormattedMessage()).contains(" seq=2 ");
	}

	@Test
	void aCraftedTargetCannotForgeASecondAuditLine() {
		// A pod file path (#206) or a Helm release name reaches the trail as typed, so a
		// newline in one must not be able to inject a line of its own.
		String crafted = "Pod/web/nginx[app]:/tmp/a\nkweblens-audit seq=999 user=\"root\" action=\"nothing\"";

		String line = AuditService.line(7,
				new AuditEntry(Instant.parse("2026-07-31T10:15:30Z"), "admin", "prod", "file-write=12B", crafted));

		// One line, one target field: the crafted text stays inside the quoted value.
		assertThat(line).doesNotContain("\n").doesNotContain("\r");
		assertThat(line.split("target=\"", -1)).hasSize(2);
		assertThat(line).startsWith("kweblens-audit seq=7 ts=2026-07-31T10:15:30Z user=\"admin\"");
		assertThat(line).contains("/tmp/a kweblens-audit").contains("\\\"root\\\"").endsWith("\"");
	}

	@Test
	void theLiveViewIsCappedButEveryEntryReachesTheLog() {
		AuditService audit = new AuditService();

		for (int i = 0; i < 520; i++) {
			audit.record("c1", "delete", "Pod/default/p" + i);
		}

		assertThat(audit.recent()).hasSize(500);
		assertThat(audit.recent().get(0).target()).isEqualTo("Pod/default/p519");
		assertThat(this.appender.list).hasSize(520);
	}

}
