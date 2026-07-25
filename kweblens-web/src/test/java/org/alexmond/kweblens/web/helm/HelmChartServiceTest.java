package org.alexmond.kweblens.web.helm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HelmChartServiceTest {

	private static final String INDEX = """
			apiVersion: v1
			entries:
			  nginx:
			    - name: nginx
			      version: 2.0.0
			      appVersion: "1.27"
			      description: A web server
			    - name: nginx
			      version: 1.0.0
			  redis:
			    - name: redis
			      version: 7.0.0
			      appVersion: "7.2"
			      description: A key-value store
			generated: "2024-01-01T00:00:00Z"
			""";

	private HttpServer server;

	private HelmProperties properties;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/index.yaml", (exchange) -> {
			byte[] body = INDEX.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		properties = new HelmProperties();
		HelmProperties.Repository repo = new HelmProperties.Repository();
		repo.setName("test");
		repo.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
		properties.setRepositories(List.of(repo));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void listsLatestVersionOfEachChart() {
		List<HelmChartSummary> charts = new HelmChartService(properties).listCharts(null);

		assertThat(charts).extracting(HelmChartSummary::name).containsExactly("nginx", "redis");
		assertThat(charts).anySatisfy((c) -> {
			assertThat(c.name()).isEqualTo("nginx");
			assertThat(c.version()).isEqualTo("2.0.0");
			assertThat(c.appVersion()).isEqualTo("1.27");
			assertThat(c.repository()).isEqualTo("test");
		});
	}

	@Test
	void filtersByNameOrDescription() {
		HelmChartService service = new HelmChartService(properties);
		assertThat(service.listCharts("redis")).extracting(HelmChartSummary::name).containsExactly("redis");
		assertThat(service.listCharts("web server")).extracting(HelmChartSummary::name).containsExactly("nginx");
	}

	@Test
	void unreachableRepoYieldsNoChartsRatherThanFailing() {
		HelmProperties.Repository dead = new HelmProperties.Repository();
		dead.setName("dead");
		dead.setUrl("http://127.0.0.1:1");
		properties.setRepositories(List.of(dead));

		assertThat(new HelmChartService(properties).listCharts(null)).isEmpty();
	}

}
