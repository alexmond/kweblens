package org.alexmond.kweblens.portforward;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterOrigin;
import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.UnknownClusterException;
import org.alexmond.kweblens.config.KweblensProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@EnableKubernetesMockClient(crud = true)
class PortForwardServiceTest {

	static KubernetesClient client;

	private PortForwardService service(ClusterRegistry registry) {
		return new PortForwardService(registry, new KweblensProperties());
	}

	private PortForwardService withMock() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", client);
		return service(registry);
	}

	private void podinfoPod(String namespace, String name, String portName) {
		Pod pod = new PodBuilder().withNewMetadata()
			.withName(name)
			.withNamespace(namespace)
			.addToLabels("app", "podinfo")
			.endMetadata()
			.withNewSpec()
			.addNewContainer()
			.withName("podinfo")
			.addNewPort()
			.withName(portName)
			.withContainerPort(9898)
			.withProtocol("TCP")
			.endPort()
			.endContainer()
			.endSpec()
			.withNewStatus()
			.withPhase("Running")
			.endStatus()
			.build();
		client.pods().inNamespace(namespace).resource(pod).create();
	}

	private void podinfoService(String namespace, String name, IntOrString targetPort) {
		Service service = new ServiceBuilder().withNewMetadata()
			.withName(name)
			.withNamespace(namespace)
			.endMetadata()
			.withNewSpec()
			.addToSelector("app", "podinfo")
			.addNewPort()
			.withName("http")
			.withPort(80)
			.withTargetPort(targetPort)
			.withProtocol("TCP")
			.endPort()
			.endSpec()
			.build();
		client.services().inNamespace(namespace).resource(service).create();
	}

	@Test
	void startRequiresAKnownCluster() {
		PortForwardService service = service(new ClusterRegistry());
		assertThatThrownBy(() -> service.start("ghost", "Pod", "web", "nginx", 80, null))
			.isInstanceOf(UnknownClusterException.class);
	}

	@Test
	void rejectsUnsupportedKind() {
		PortForwardService service = withMock();
		assertThatThrownBy(() -> service.start("mock", "Deployment", "web", "app", 8080, null))
			.isInstanceOf(PortForwardException.class)
			.hasMessageContaining("Deployment");
	}

	@Test
	void listsNothingForAnUnknownCluster() {
		PortForwardService service = service(new ClusterRegistry());
		assertThat(service.list("mock")).isEmpty();
	}

	@Test
	void stopIsANoOpForUnknownId() {
		PortForwardService service = service(new ClusterRegistry());
		service.stop("nope");
		assertThat(service.list("mock")).isEmpty();
	}

	@Test
	void withStatusReplacesOnlyTheStatus() {
		PortForwardInfo info = new PortForwardInfo("id", "mock", "web", "Pod", "nginx", 80, 8080, "nginx-1", 12345,
				"127.0.0.1", "TCP", "Active");
		PortForwardInfo closed = info.withStatus("Closed");
		assertThat(closed.status()).isEqualTo("Closed");
		assertThat(closed.localPort()).isEqualTo(12345);
		assertThat(closed.name()).isEqualTo("nginx");
		assertThat(closed.podPort()).isEqualTo(8080);
		assertThat(closed.podName()).isEqualTo("nginx-1");
	}

	@Test
	void podTargetIsNotTranslated() {
		PortForwardService service = withMock();
		PortForwardTarget target = service.target("mock", "Pod", "web", "nginx", 8080);
		assertThat(target.namespace()).isEqualTo("web");
		assertThat(target.podName()).isEqualTo("nginx");
		assertThat(target.podPort()).isEqualTo(8080);
	}

	@Test
	void podTargetDefaultsToPodWhenKindIsBlank() {
		PortForwardService service = withMock();
		assertThat(service.target("mock", null, "web", "nginx", 8080).podPort()).isEqualTo(8080);
	}

	@Test
	void serviceTargetUsesANumericTargetPort() {
		podinfoPod("num", "podinfo-1", "http");
		podinfoService("num", "podinfo", new IntOrString(9898));
		PortForwardService service = withMock();
		PortForwardTarget target = service.target("mock", "Service", "num", "podinfo", 80);
		assertThat(target.podName()).isEqualTo("podinfo-1");
		assertThat(target.podPort()).isEqualTo(9898);
	}

	@Test
	void serviceTargetResolvesANamedTargetPortAgainstThePod() {
		podinfoPod("named", "podinfo-1", "http");
		podinfoService("named", "podinfo", new IntOrString("http"));
		PortForwardService service = withMock();
		PortForwardTarget target = service.target("mock", "Service", "named", "podinfo", 80);
		assertThat(target.podName()).isEqualTo("podinfo-1");
		assertThat(target.podPort()).isEqualTo(9898);
	}

	@Test
	void serviceTargetFailsWhenTheNamedPortIsNotOnThePod() {
		podinfoPod("mismatch", "podinfo-1", "metrics");
		podinfoService("mismatch", "podinfo", new IntOrString("http"));
		PortForwardService service = withMock();
		assertThatThrownBy(() -> service.target("mock", "Service", "mismatch", "podinfo", 80))
			.isInstanceOf(PortForwardException.class)
			.hasMessageContaining("named port 'http'");
	}

	@Test
	void serviceTargetFallsBackToThePortWhenTargetPortIsAbsent() {
		podinfoPod("absent", "podinfo-1", "http");
		podinfoService("absent", "podinfo", null);
		PortForwardService service = withMock();
		assertThat(service.target("mock", "Service", "absent", "podinfo", 80).podPort()).isEqualTo(80);
	}

	@Test
	void serviceTargetRejectsAPortTheServiceDoesNotExpose() {
		podinfoPod("nomatch", "podinfo-1", "http");
		podinfoService("nomatch", "podinfo", new IntOrString(9898));
		PortForwardService service = withMock();
		assertThatThrownBy(() -> service.target("mock", "Service", "nomatch", "podinfo", 8080))
			.isInstanceOf(PortForwardException.class)
			.hasMessageContaining("has no port 8080");
	}

	@Test
	void serviceTargetRejectsAnUnknownService() {
		PortForwardService service = withMock();
		assertThatThrownBy(() -> service.target("mock", "Service", "ghost", "podinfo", 80))
			.isInstanceOf(PortForwardException.class)
			.hasMessageContaining("not found");
	}

	@Test
	void serviceTargetRejectsAServiceThatSelectsNoPods() {
		podinfoService("empty", "podinfo", new IntOrString(9898));
		PortForwardService service = withMock();
		assertThatThrownBy(() -> service.target("mock", "Service", "empty", "podinfo", 80))
			.isInstanceOf(PortForwardException.class)
			.hasMessageContaining("selects no pods");
	}

	@Test
	void serviceTargetRejectsAServiceWithoutASelector() {
		Service service = new ServiceBuilder().withNewMetadata()
			.withName("headless")
			.withNamespace("noselector")
			.endMetadata()
			.withNewSpec()
			.addNewPort()
			.withPort(80)
			.withTargetPort(new IntOrString(9898))
			.endPort()
			.endSpec()
			.build();
		client.services().inNamespace("noselector").resource(service).create();
		PortForwardService forwards = withMock();
		assertThatThrownBy(() -> forwards.target("mock", "Service", "noselector", "headless", 80))
			.isInstanceOf(PortForwardException.class)
			.hasMessageContaining("no selector");
	}

	@Test
	void serviceTargetAcceptsANumericStringTargetPort() {
		podinfoPod("strnum", "podinfo-1", "http");
		podinfoService("strnum", "podinfo", new IntOrString("9898"));
		PortForwardService service = withMock();
		assertThat(service.target("mock", "Service", "strnum", "podinfo", 80).podPort()).isEqualTo(9898);
	}

	@Test
	void removingAClusterStopsItsForwards() throws Exception {
		// The listening socket is bound by kweblens on the kweblens host, so closing the
		// cluster's client kills the tunnel and leaves the port bound — and after a
		// removal the port-forward table is gone from the UI with it, so nothing could
		// ever stop it again.
		ClusterRegistry registry = new ClusterRegistry();
		// A stand-in client, not the shared mock-server one: unregistering closes it, and
		// the rest of this class still needs the mock server.
		registry.register("mock", "mock", mock(KubernetesClient.class), ClusterOrigin.RUNTIME);
		PortForwardService service = service(registry);
		LocalPortForward forward = mock(LocalPortForward.class);
		LocalPortForward other = mock(LocalPortForward.class);
		service.track(forward, forwardInfo("mock-1", "mock"));
		service.track(other, forwardInfo("keep-1", "keep"));

		registry.unregister("mock");

		verify(forward).close();
		assertThat(service.list("mock")).isEmpty();
		// Another cluster's forwards are untouched.
		verify(other, never()).close();
		assertThat(service.list("keep")).extracting(PortForwardInfo::id).containsExactly("keep-1");
	}

	@Test
	void rePointingAClusterStopsTheForwardsThatWentThroughTheOldClient() throws Exception {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", mock(KubernetesClient.class), ClusterOrigin.RUNTIME);
		PortForwardService service = service(registry);
		LocalPortForward forward = mock(LocalPortForward.class);
		service.track(forward, forwardInfo("mock-1", "mock"));

		// Editing a cluster re-registers the id against a new client; the old one is
		// closed, so the tunnel behind this forward is already dead. Leaving it listed as
		// "Active" would be a socket that accepts connections and drops them.
		registry.register("mock", "mock", mock(KubernetesClient.class), ClusterOrigin.RUNTIME);

		verify(forward).close();
		assertThat(service.list("mock")).isEmpty();
	}

	@Test
	void stopAllIsAnHonestZeroForAClusterWithNoForwards() {
		assertThat(service(new ClusterRegistry()).stopAll("mock")).isZero();
	}

	private PortForwardInfo forwardInfo(String id, String clusterId) {
		return new PortForwardInfo(id, clusterId, "web", "Pod", "nginx", 80, 80, "nginx-1", 18080, "127.0.0.1", "TCP",
				"Active");
	}

}
