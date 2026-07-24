package org.alexmond.kweblens.web.security;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditServiceTest {

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

}
