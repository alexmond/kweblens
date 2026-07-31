package org.alexmond.kweblens.cluster;

import java.util.Arrays;
import java.util.Locale;

/**
 * Which egress proxy a cluster's traffic will actually use — and whether a configured one
 * is being silently ignored.
 *
 * <p>
 * <b>The bypass this exists to catch.</b> fabric8 routes a kubeconfig {@code proxy-url}
 * by the <em>proxy's own scheme</em>, not the target's: {@code socks5://} and
 * {@code https://} become the HTTPS proxy, while a plain {@code http://} proxy becomes
 * the HTTP proxy. An apiserver is virtually always {@code https}, so an {@code http://}
 * proxy-url — the most common corporate configuration there is — is simply never
 * consulted.
 *
 * <p>
 * Verified against a real proxy rather than reasoned about: with
 * {@code proxy-url: http://…} and an https apiserver, kweblens listed 29 namespaces while
 * the proxy logged <b>zero</b> connections; the identical request through a
 * {@code socks5://} proxy-url logged one. The proxy itself was fine — curl through it
 * reached the same apiserver.
 *
 * <p>
 * That failure mode is the dangerous kind: everything works, so nothing prompts anyone to
 * look, while traffic that was required to go through an audited egress path quietly does
 * not. Hence reporting it rather than only documenting it.
 */
public record ProxyStatus(String effective, boolean bypassed, String detail) {

	/**
	 * Work out the effective proxy from a cluster's resolved connection settings.
	 * @param masterUrl the apiserver URL, whose scheme decides which proxy applies
	 * @param httpProxy fabric8's resolved HTTP proxy, or null
	 * @param httpsProxy fabric8's resolved HTTPS proxy, or null
	 * @param noProxy hosts excluded from proxying, or null
	 */
	public static ProxyStatus of(String masterUrl, String httpProxy, String httpsProxy, String[] noProxy) {
		boolean secure = masterUrl != null && masterUrl.toLowerCase(Locale.ROOT).startsWith("https");
		String applicable = secure ? httpsProxy : httpProxy;
		String ignored = secure ? httpProxy : httpsProxy;
		String exclusions = (noProxy != null && noProxy.length > 0) ? ", NO_PROXY=" + String.join(",", noProxy) : "";
		if (has(applicable)) {
			return new ProxyStatus(applicable, false, "via " + applicable + exclusions);
		}
		if (has(ignored)) {
			// The whole point of this class.
			return new ProxyStatus(null, true, "a proxy is configured (" + ignored + ") but will NOT be used: it is an "
					+ (secure ? "http:// proxy and the apiserver is https" : "https:// proxy and the apiserver is http")
					+ ". fabric8 routes by the PROXY's scheme, so this traffic goes direct. "
					+ "Use socks5:// or https:// for an https apiserver." + exclusions);
		}
		return new ProxyStatus(null, false, "no egress proxy configured — traffic goes direct" + exclusions);
	}

	/**
	 * Convenience for the common call site, which has the four values on a fabric8
	 * Config.
	 */
	public static ProxyStatus of(String masterUrl, String httpProxy, String httpsProxy) {
		return of(masterUrl, httpProxy, httpsProxy, null);
	}

	private static boolean has(String value) {
		return value != null && !value.isBlank();
	}

	/** True when a proxy is in force for this cluster's traffic. */
	public boolean proxied() {
		return has(this.effective);
	}

	/** The excluded hosts, for callers that want them separately. */
	public static String[] exclusions(String[] noProxy) {
		return (noProxy != null) ? Arrays.copyOf(noProxy, noProxy.length) : new String[0];
	}

}
