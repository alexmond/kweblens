package org.alexmond.kweblens.exec;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.UnknownClusterException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecServiceTest {

	@Test
	void execRequiresAKnownCluster() {
		ExecService exec = new ExecService(new ClusterRegistry());
		assertThatThrownBy(() -> exec.exec("ghost", "web", "nginx", null, new ByteArrayOutputStream(), null))
			.isInstanceOf(UnknownClusterException.class);
	}

}
