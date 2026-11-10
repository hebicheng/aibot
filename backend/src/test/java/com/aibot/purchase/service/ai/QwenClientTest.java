package com.aibot.purchase.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.aibot.purchase.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class QwenClientTest {
  private HttpServer server;

  @Test
  void usesLongerDefaultTimeoutForComplexDocuments() {
    AiProperties properties = new AiProperties(false, null);

    assertThat(properties.qwen().timeoutSeconds()).isEqualTo(120);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void readsJsonBodyEvenWhenGatewayUsesOctetStreamContentType() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      byte[] response = """
          {"choices":[{"message":{"content":"{\\"intent\\":\\"non_business\\"}"}}]}
          """.getBytes(StandardCharsets.UTF_8);
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();

    AiProperties properties = new AiProperties(
        false,
        new AiProperties.Qwen(
            "test-key",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "qwen3.7-plus",
            5
        )
    );
    QwenClient client = new QwenClient(
        properties,
        RestClient.builder(),
        new ObjectMapper()
    );

    assertThat(client.completeJson("system", "user").content())
        .isEqualTo("{\"intent\":\"non_business\"}");
    assertThat(requestBody.get()).contains("\"enable_thinking\":false");
  }
}
