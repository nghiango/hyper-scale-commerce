package com.hyperscale.commerce.orderquery.observability;

import static org.assertj.core.api.Assertions.assertThat;

import brave.Tracing;
import com.hyperscale.commerce.orderquery.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;

class TraceExportTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(TracingConfiguration.class)
          .withBean(AppProperties.class, () -> new AppProperties("export-test", "test"));

  @Test
  void exporterIsAbsentByDefault() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(AsyncZipkinSpanHandler.class);
        });
  }

  @Test
  void completedSpanReachesZipkinReceiver() throws Exception {
    var received = new LinkedBlockingQueue<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v2/spans",
        exchange -> {
          String payload =
              new String(
                  ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))
                          ? new java.util.zip.GZIPInputStream(exchange.getRequestBody())
                          : exchange.getRequestBody())
                      .readAllBytes(),
                  StandardCharsets.UTF_8);
          if (!"[]".equals(payload)) {
            received.add(payload);
          }
          exchange.sendResponseHeaders(202, -1);
          exchange.close();
        });
    server.start();
    try {
      runner
          .withPropertyValues(
              "app.tracing.zipkin.endpoint=http://127.0.0.1:"
                  + server.getAddress().getPort()
                  + "/api/v2/spans")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                context
                    .getBean(Tracing.class)
                    .tracer()
                    .nextSpan()
                    .name("delivery-smoke")
                    .start()
                    .finish();
                context.getBean(AsyncZipkinSpanHandler.class).flush();
                assertThat(received.poll(5, TimeUnit.SECONDS))
                    .contains("delivery-smoke", "export-test");
              });
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsCredentialBearingEndpoint() {
    runner
        .withPropertyValues("app.tracing.zipkin.endpoint=http://user:password@localhost/spans")
        .run(context -> assertThat(context).hasFailed());
  }
}
