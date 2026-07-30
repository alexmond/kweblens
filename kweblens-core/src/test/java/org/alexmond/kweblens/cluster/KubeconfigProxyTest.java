package org.alexmond.kweblens.cluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins kweblens's egress-proxy support, which is inherited from fabric8 rather than
 * implemented here — and is therefore easy to lose silently.
 *
 * <p>
 * kubeconfig's per-cluster {@code proxy-url} is the de-facto standard for reaching an
 * apiserver through a proxy, and because {@code ClusterRegistry} builds one fabric8
 * {@code Config} per cluster, honouring it gives kweblens PER-CLUSTER proxying —
 * something the closest server-side analogue (Rancher, whose chart exposes a single
 * global proxy) does not offer. Since nothing in kweblens's own code implements this, a
 * change to the load path could drop it with no visible symptom other than clusters
 * mysteriously timing out. Hence these tests.
 *
 * <p>
 * Building a client does not connect, so none of this reaches a network. See
 * docs/design/proxy-competitive.md.
 */
class KubeconfigProxyTest {

	// RFC 5737 documentation addresses — never real or lab addresses.
	private static final String APISERVER = "https://198.51.100.10:6443";

	private static final String PROXY = "socks5://198.51.100.99:1080";

	private String kubeconfig(String clusterExtras) {
		return """
				apiVersion: v1
				kind: Config
				current-context: ctx
				clusters:
				  - name: c1
				    cluster:
				      server: %s
				      insecure-skip-tls-verify: true
				%s
				contexts:
				  - name: ctx
				    context:
				      cluster: c1
				      user: u1
				users:
				  - name: u1
				    user:
				      token: dummy-not-a-real-token
				""".formatted(APISERVER, clusterExtras);
	}

	@Test
	void honoursThePerClusterProxyUrlFromKubeconfig() {
		try (KubernetesClient client = KubeconfigLoader.clientFor(kubeconfig("      proxy-url: " + PROXY), null,
				null)) {
			assertThat(client.getConfiguration().getMasterUrl()).startsWith(APISERVER);
			// fabric8 maps proxy-url onto httpsProxy; socks5:// is an accepted scheme.
			assertThat(client.getConfiguration().getHttpsProxy()).isEqualTo(PROXY);
		}
	}

	@Test
	void leavesTheProxyUnsetWhenTheKubeconfigDoesNotAskForOne() {
		// Guards against accidentally introducing a default proxy: an unexpected proxy
		// hop is
		// far harder to diagnose than a missing one.
		try (KubernetesClient client = KubeconfigLoader.clientFor(kubeconfig(""), null, null)) {
			assertThat(client.getConfiguration().getHttpsProxy()).isNull();
			assertThat(client.getConfiguration().getHttpProxy()).isNull();
		}
	}

	/**
	 * Documents a genuinely surprising mapping, discovered while researching #22: fabric8
	 * routes {@code proxy-url} by the <b>proxy's own scheme</b>, not by the target
	 * server's.
	 *
	 * <p>
	 * A {@code socks5://} or {@code https://} proxy URL becomes {@code httpsProxy}, but a
	 * plain {@code http://} proxy URL becomes {@code httpProxy} — <em>even though the
	 * apiserver is https</em>. That matters because the most common corporate
	 * configuration in the world is exactly {@code proxy-url: http://proxy:3128} against
	 * an {@code https://} apiserver (the proxy is reached in cleartext and tunnels TLS
	 * via CONNECT), so it is the case most likely to be mis-routed and to fail as an
	 * unexplained timeout.
	 *
	 * <p>
	 * This test asserts the ACTUAL behaviour rather than the desired one, so that if a
	 * fabric8 upgrade changes it the change is caught here and the recommendation in
	 * docs/design/proxy-competitive.md can be revisited.
	 */
	@Test
	void routesTheProxyUrlByTheProxysOwnSchemeNotTheServers() {
		try (KubernetesClient socks = KubeconfigLoader.clientFor(kubeconfig("      proxy-url: " + PROXY), null, null);
				KubernetesClient plain = KubeconfigLoader
					.clientFor(kubeconfig("      proxy-url: http://198.51.100.7:3128"), null, null);
				KubernetesClient tls = KubeconfigLoader
					.clientFor(kubeconfig("      proxy-url: https://198.51.100.7:3129"), null, null)) {
			// socks5 and https proxies land on httpsProxy...
			assertThat(socks.getConfiguration().getHttpsProxy()).isEqualTo(PROXY);
			assertThat(socks.getConfiguration().getHttpProxy()).isNull();
			assertThat(tls.getConfiguration().getHttpsProxy()).isEqualTo("https://198.51.100.7:3129");

			// ...but a cleartext http:// proxy lands on httpProxy, despite the https
			// apiserver.
			assertThat(plain.getConfiguration().getHttpProxy()).isEqualTo("http://198.51.100.7:3128");
			assertThat(plain.getConfiguration().getHttpsProxy()).isNull();
		}
	}

	@Test
	void keepsTlsSettingsAlongsideTheProxy() {
		// A TLS-intercepting proxy is the common corporate case, so the skip-verify / CA
		// fields
		// must survive the same load path the proxy setting does.
		try (KubernetesClient client = KubeconfigLoader.clientFor(kubeconfig("      proxy-url: " + PROXY), null,
				null)) {
			assertThat(client.getConfiguration().isTrustCerts()).isTrue();
			assertThat(client.getConfiguration().getHttpsProxy()).isEqualTo(PROXY);
		}
	}

}
