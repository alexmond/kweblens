package org.alexmond.kweblens.health;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a pod's own spec says it may do — and, more importantly, what it does not say.
 *
 * <p>
 * Every test here has a decoy, because a check that has never rejected anything pins
 * nothing. The two that matter: {@code privileged: false} is not privilege, and a
 * container that sets its own {@code runAsUser} is not running as the pod's — that second
 * one is the shape a careless "does the pod mention runAsUser: 0" check would report, and
 * it would be wrong on the object it names.
 */
class PodSecurityTest {

	private GenericKubernetesResource pod(String yaml) {
		return Serialization.unmarshal(yaml, GenericKubernetesResource.class);
	}

	@Test
	void reportsAPrivilegedContainerAndNamesTheField() {
		List<SecurityFinding> findings = PodSecurity.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: agent, namespace: app}
				spec:
				  containers:
				    - name: node-agent
				      securityContext: {privileged: true}
				"""));

		assertThat(findings).singleElement().satisfies((f) -> {
			assertThat(f.title()).isEqualTo("Container runs privileged");
			assertThat(f.object()).isEqualTo("Pod/agent container node-agent");
			assertThat(f.detail()).contains("securityContext.privileged=true");
			// Not critical: a privileged CNI or CSI container is a choice somebody made,
			// and burying a crashloop under it is the failure #223 fixed elsewhere.
			assertThat(f.severity()).isEqualTo("warning");
		});
	}

	@Test
	void doesNotReportAContainerThatDeclaresItselfUnprivileged() {
		assertThat(PodSecurity.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: web, namespace: app}
				spec:
				  containers:
				    - name: app
				      securityContext: {privileged: false, allowPrivilegeEscalation: true}
				"""))).isEmpty();
	}

	@Test
	void reportsAnExplicitRootUidAndSaysWhereItWasSet() {
		List<SecurityFinding> findings = PodSecurity.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: legacy, namespace: app}
				spec:
				  securityContext: {runAsUser: 0}
				  containers:
				    - name: app
				"""));

		assertThat(findings).singleElement().satisfies((f) -> {
			assertThat(f.title()).isEqualTo("Container runs as root");
			assertThat(f.detail()).contains("runAsUser=0").contains("on the pod");
		});
	}

	@Test
	void doesNotReportAContainerThatOverridesThePodsRootUid() {
		// The decoy: the pod says 0, the container says 1000, and the container wins. A
		// check that looked at the pod alone would report a container running as a user
		// it demonstrably is not.
		assertThat(PodSecurity.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: mixed, namespace: app}
				spec:
				  securityContext: {runAsUser: 0}
				  containers:
				    - name: app
				      securityContext: {runAsUser: 1000}
				"""))).isEmpty();
	}

	@Test
	void saysNothingAboutAContainerWhoseUserIsNotDeclared() {
		// This container may very well run as root — the image's USER decides, and that
		// is not in the API. Reporting it would be a guess dressed as an observation.
		assertThat(PodSecurity.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: plain, namespace: app}
				spec:
				  containers:
				    - name: app
				"""))).isEmpty();
	}

	@Test
	void namesAnInitContainerAsOne() {
		assertThat(PodSecurity.forPod(pod("""
				apiVersion: v1
				kind: Pod
				metadata: {name: setup, namespace: app}
				spec:
				  initContainers:
				    - name: mount
				      securityContext: {privileged: true}
				  containers:
				    - name: app
				"""))).singleElement().extracting(SecurityFinding::object).isEqualTo("Pod/setup init container mount");
	}

}
