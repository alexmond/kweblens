package org.alexmond.kweblens.web.ai;

import java.util.List;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a Service with no endpoints is told to do about it.
 *
 * <p>
 * Every case used to get the same sentence — "check the Service's selector against the
 * pod labels it is meant to match". That is the wrong advice for the commonest cause:
 * when one workload carries the selector and sits at zero replicas, the selector is the
 * one thing that is right. On a real cluster this check produced 15 of 22 critical
 * findings, so its advice being wrong was not a small matter.
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
@EnableKubernetesMockClient(crud = true)
class ServiceAdviceTest {

	private static final String NS = "shop";

	KubernetesClient client;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private DiagnoseService diagnose;

	@BeforeEach
	void setUp() {
		this.registry.register("test", "Test cluster", this.client);
		this.client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName(NS).endMetadata().build())
			.create();
	}

	@Test
	void tellsYouToScaleUpWhenTheSelectorIsRightAndNothingIsRunning() {
		// The case the old wording got wrong. Sending someone to inspect a correct
		// selector is worse than saying nothing: it is a specific instruction to look in
		// the wrong place.
		deployment("api", 0);
		service("api-svc", "api");

		Finding finding = onlyServiceFinding();
		assertThat(finding.suggestedFix()).contains("Deployment/api")
			.contains("scaled to zero")
			.contains("Scale it up");
		assertThat(finding.suggestedFix()).doesNotContain("Check the Service's selector");
	}

	@Test
	void leavesTheDetailExactlyAsTheHealthCheckWroteIt() {
		// Load-bearing. RemediationService picks the scale-up proposal with
		// NO_ENDPOINTS.equals(finding.detail()), so enriching the detail here — which the
		// first version of this change did — matches nothing and silently stops the fix
		// being offered. Two of its tests caught it; this one says why.
		deployment("api", 0);
		service("api-svc", "api");

		assertThat(onlyServiceFinding().detail()).isEqualTo("no endpoints");
	}

	@Test
	void tellsYouToCheckTheSelectorWhenNothingCarriesIt() {
		// Here the old wording was right, and it stays.
		deployment("api", 1);
		service("typo-svc", "ap1");

		assertThat(onlyServiceFinding().suggestedFix()).contains("No workload's pod template carries this selector")
			.contains("Check the Service's selector");
	}

	@Test
	void doesNotBlameTheSelectorWhenSeveralWorkloadsCarryIt() {
		// Two workloads, same template labels: which one the Service meant is unknowable,
		// and picking would be a guess presented as a diagnosis.
		deployment("api", 1);
		deployment("api-canary", 1, "api");
		service("api-svc", "api");

		assertThat(onlyServiceFinding().suggestedFix()).contains("Several workloads carry this selector");
	}

	@Test
	void reportsADeliberatelyScaledDownWorkloadAsAWarningRatherThanACritical() {
		// #223. One check produced 16 of 22 criticals on a real cluster and buried an
		// ImagePullBackOff and an OOM kill; 11 of those 16 were a workload somebody had
		// deliberately scaled to zero. Nothing is failing there, so it must not compete
		// with the things that are. This is a statement about the CAUSE — the other
		// causes below stay critical — which is what makes it safe.
		deployment("idle", 0);
		service("idle-svc", "idle");

		Finding finding = onlyServiceFinding();
		assertThat(finding.severity()).isEqualTo("warning");
		assertThat(finding.title()).isEqualTo("Service points at a scaled-down workload");
		// The detail is still what the health check said, because RemediationService
		// matches it exactly to offer the scale-up. A warning with a one-click fix.
		assertThat(finding.detail()).isEqualTo("no endpoints");
	}

	@Test
	void keepsAMissingSelectorTargetCriticalBecauseNothingWillEverBackIt() {
		deployment("other", 1, "other-app");
		service("orphan-svc", "nobody");

		Finding finding = onlyServiceFinding();
		assertThat(finding.severity()).isEqualTo("critical");
		assertThat(finding.title()).isEqualTo("Service has nothing behind it");
	}

	@Test
	void pointsAtThePodsWhenAWorkloadIsRunningButProducesNoEndpoints() {
		deployment("api", 3);
		service("api-svc", "api");

		assertThat(onlyServiceFinding().suggestedFix()).contains("not scaled down")
			.contains("why its pods are not becoming endpoints");
	}

	@Test
	void fallsBackToTheGeneralWordingForAServiceWithNoSelector() {
		// No selector means the Endpoints come from somewhere this cannot see, so there
		// is
		// nothing specific to say. Saying something specific anyway would be inventing
		// it.
		this.client.services()
			.resource(new ServiceBuilder().withNewMetadata()
				.withName("managed-elsewhere")
				.withNamespace(NS)
				.endMetadata()
				.withNewSpec()
				.endSpec()
				.build())
			.create();

		assertThat(onlyServiceFinding().suggestedFix())
			.isEqualTo("Check the Service's selector against the pod labels it is meant to match.");
	}

	private Finding onlyServiceFinding() {
		List<Finding> findings = this.diagnose.diagnose("test", NS)
			.findings()
			.stream()
			.filter((f) -> f.object().startsWith("Service/"))
			.toList();
		assertThat(findings).hasSize(1);
		return findings.get(0);
	}

	private void deployment(String name, int replicas) {
		deployment(name, replicas, name);
	}

	private void deployment(String name, int replicas, String appLabel) {
		Deployment deployment = new DeploymentBuilder().withNewMetadata()
			.withName(name)
			.withNamespace(NS)
			.endMetadata()
			.withNewSpec()
			.withReplicas(replicas)
			.withNewTemplate()
			.withNewMetadata()
			.addToLabels("app", appLabel)
			.endMetadata()
			.endTemplate()
			.endSpec()
			.build();
		this.client.apps().deployments().resource(deployment).create();
	}

	private void service(String name, String selects) {
		Service service = new ServiceBuilder().withNewMetadata()
			.withName(name)
			.withNamespace(NS)
			.endMetadata()
			.withNewSpec()
			.addToSelector("app", selects)
			.endSpec()
			.build();
		this.client.services().resource(service).create();
	}

}
