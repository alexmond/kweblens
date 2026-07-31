package org.alexmond.kweblens.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The silent-bypass rule.
 *
 * <p>
 * These cases were established against a real proxy, not inferred: with
 * {@code proxy-url: http://…} and an https apiserver, kweblens listed 29 namespaces while
 * the proxy logged <b>zero</b> connections; through a {@code socks5://} proxy-url the
 * same request logged one. The proxy was working — curl through it reached the same
 * apiserver.
 *
 * <p>
 * That is the failure mode worth a test: nothing breaks, so nothing prompts anyone to
 * check whether traffic is taking the egress path it was required to take.
 */
class ProxyStatusTest {

	// RFC 5737 documentation addresses — never real or lab addresses.
	private static final String HTTPS_APISERVER = "https://198.51.100.10:6443";

	private static final String HTTP_APISERVER = "http://198.51.100.10:8080";

	@Test
	void reportsTheProxyThatWillActuallyBeUsed() {
		// socks5:// and https:// proxy-urls land on the HTTPS proxy, which is what an
		// https apiserver consults.
		ProxyStatus status = ProxyStatus.of(HTTPS_APISERVER, null, "socks5://198.51.100.99:1080", null);
		assertThat(status.proxied()).isTrue();
		assertThat(status.bypassed()).isFalse();
		assertThat(status.effective()).isEqualTo("socks5://198.51.100.99:1080");
	}

	@Test
	void catchesAnHttpProxyThatWillNeverBeConsultedForAnHttpsApiserver() {
		// The corporate default, and the one that quietly does nothing.
		ProxyStatus status = ProxyStatus.of(HTTPS_APISERVER, "http://198.51.100.99:3128", null, null);
		assertThat(status.bypassed()).isTrue();
		assertThat(status.proxied()).isFalse();
		assertThat(status.detail()).contains("will NOT be used").contains("goes direct").contains("socks5://");
	}

	@Test
	void catchesTheMirrorImageToo() {
		// Rare, but the same class of mistake: an https proxy against a plain http
		// apiserver is equally never consulted.
		ProxyStatus status = ProxyStatus.of(HTTP_APISERVER, null, "https://198.51.100.99:3128", null);
		assertThat(status.bypassed()).isTrue();
	}

	@Test
	void saysPlainlyWhenThereIsNoProxyAtAll() {
		ProxyStatus status = ProxyStatus.of(HTTPS_APISERVER, null, null, null);
		assertThat(status.bypassed()).isFalse();
		assertThat(status.proxied()).isFalse();
		assertThat(status.detail()).contains("no egress proxy");
	}

	@Test
	void doesNotCallAConfiguredProxyBypassedWhenTheRightOneIsAlsoSet() {
		// Both slots set is normal — the applicable one wins and nothing is being
		// ignored.
		ProxyStatus status = ProxyStatus.of(HTTPS_APISERVER, "http://198.51.100.98:3128", "https://198.51.100.99:3128",
				null);
		assertThat(status.bypassed()).isFalse();
		assertThat(status.effective()).isEqualTo("https://198.51.100.99:3128");
	}

	@Test
	void reportsTheExclusionsAlongsideTheProxy() {
		// In-cluster these matter: without .svc/.cluster.local in NO_PROXY, in-cluster
		// traffic is sent out through the proxy and comes back as a timeout.
		ProxyStatus status = ProxyStatus.of(HTTPS_APISERVER, null, "https://198.51.100.99:3128",
				new String[] { ".svc", ".cluster.local" });
		assertThat(status.detail()).contains("NO_PROXY=.svc,.cluster.local");
	}

	@Test
	void treatsBlankAsUnset() {
		assertThat(ProxyStatus.of(HTTPS_APISERVER, "  ", "", null).proxied()).isFalse();
		assertThat(ProxyStatus.of(HTTPS_APISERVER, "  ", "", null).bypassed()).isFalse();
	}

	@Test
	void toleratesAnUnknownMasterUrl() {
		// A null master URL is treated as not-https, so nothing is claimed about it.
		assertThat(ProxyStatus.of(null, null, null, null).bypassed()).isFalse();
	}

}
