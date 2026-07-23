package org.alexmond.kweblens;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the whole application context — security, the MCP server, the config-properties
 * scan, and the ambient-kubeconfig bootstrap — to catch wiring regressions. Building the
 * default fabric8 client does not connect, so this needs no live cluster.
 */
@SpringBootTest
class KweblensApplicationTest {

	@Test
	void contextLoads() {
	}

}
