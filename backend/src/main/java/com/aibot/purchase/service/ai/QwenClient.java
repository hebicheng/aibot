package com.aibot.purchase.service.ai;

import com.aibot.purchase.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * 通义千问（OpenAI 兼容模式）客户端。
 * qwen3.7-plus 多模态客户端。PDF 由后端预处理为页面图片。
 *
 * <p>调用结果以 {@link QwenResult} 返回，除文本外附带 {@link QwenUsage}（模型返回的
 * prompt/completion/total tokens），供计费模块消费。
 */
@Component
public class QwenClient {
  private static final Logger log = LoggerFactory.getLogger(QwenClient.class);

  public record QwenUsage(long promptTokens, long completionTokens, long totalTokens) {
    public static final QwenUsage ZERO = new QwenUsage(0, 0, 0);
  }

  public record QwenResult(String content, QwenUsage usage) {
  }

  private final AiProperties properties;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public QwenClient(AiProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    int timeoutMs = Math.max(1, properties.qwen().timeoutSeconds()) * 1000;
    factory.setConnectTimeout(timeoutMs);
    factory.setReadTimeout(timeoutMs);
    this.restClient = builder
        .baseUrl(properties.qwen().baseUrl())
        .requestFactory(factory)
        .build();
  }

  public boolean isConfigured() {
    return StringUtils.hasText(properties.qwen().apiKey());
  }

  /**
   * 纯文本对话（JSON 模式）
   */
  public QwenResult completeJson(String systemPrompt, String userPrompt) {
    List<Map<String, Object>> messages = List.of(
        Map.of("role", "system", "content", systemPrompt),
        Map.of("role", "user", "content", userPrompt)
    );
    return call(properties.qwen().model(), messages, true, "文本");
  }

  /**
   * 多模态对话：传入图片 URL 或 data URI 与文本提示。
   */
  public QwenResult completeMultimodal(String systemPrompt, String textPrompt, List<MediaInput> mediaInputs) {
    List<Map<String, Object>> contentParts = new ArrayList<>();
    for (MediaInput media : mediaInputs) {
      contentParts.add(Map.of("type", "image_url", "image_url", Map.of("url", media.url())));
    }
    // 添加文本提示
    contentParts.add(Map.of("type", "text", "text", textPrompt));

    List<Map<String, Object>> messages = List.of(
        Map.of("role", "system", "content", List.of(Map.of("type", "text", "text", systemPrompt))),
        Map.of("role", "user", "content", contentParts)
    );
    return call(properties.qwen().model(), messages, true, "图片");
  }

  public record MediaInput(String url, String contentType) {
  }

  @SuppressWarnings("unchecked")
  private QwenResult call(
      String model,
      List<Map<String, Object>> messages,
      boolean jsonMode,
      String requestType
  ) {
    long startedAt = System.nanoTime();
    Map<String, Object> body;
    if (jsonMode) {
      body = Map.of(
          "model", model,
          "messages", messages,
          "response_format", Map.of("type", "json_object"),
          "enable_thinking", false,
          "temperature", 0.1,
          "stream", false
      );
    } else {
      body = Map.of(
          "model", model,
          "messages", messages,
          "enable_thinking", false,
          "temperature", 0.1,
          "stream", false
      );
    }

    Map<String, Object> response;
    try {
      byte[] rawBytes = restClient.post()
          .uri("/v1/chat/completions")
          .header("Authorization", "Bearer " + properties.qwen().apiKey())
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .body(body)
          .exchange((request, rawResponse) -> {
            byte[] bytes = rawResponse.getBody().readAllBytes();
            HttpStatusCode status = rawResponse.getStatusCode();
            if (status.isError()) {
              String errorBody = new String(bytes, StandardCharsets.UTF_8);
              throw new IllegalStateException(
                  "千问请求失败（HTTP " + status.value() + "）：" + errorBody);
            }
            return bytes;
          });
      String rawResponse = rawBytes == null ? null : new String(rawBytes, StandardCharsets.UTF_8);
      response = rawResponse == null ? null : objectMapper.readValue(rawResponse, Map.class);
    } catch (Exception ex) {
      Throwable root = ex;
      while (root.getCause() != null && root.getCause() != root) {
        root = root.getCause();
      }
      long elapsedMs = elapsedMillis(startedAt);
      throw new IllegalStateException(
          "千问" + requestType + "调用失败（耗时 " + elapsedMs + "ms）：" + root.getMessage(),
          ex
      );
    }

    if (response == null) {
      throw new IllegalStateException("千问返回空响应");
    }
    List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
    if (choices == null || choices.isEmpty()) {
      throw new IllegalStateException("千问返回无 choices：" + response);
    }
    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
    Object content = message == null ? null : message.get("content");
    if (content == null) {
      throw new IllegalStateException("千问返回无 content");
    }
    QwenUsage usage = parseUsage(response.get("usage"));
    log.info("千问{}调用成功：model={}，耗时={}ms，usage={}",
        requestType, model, elapsedMillis(startedAt), usage);
    return new QwenResult(content.toString(), usage);
  }

  @SuppressWarnings("unchecked")
  private QwenUsage parseUsage(Object usageObj) {
    if (!(usageObj instanceof Map<?, ?> usageMap)) {
      return QwenUsage.ZERO;
    }
    Map<String, Object> usage = (Map<String, Object>) usageMap;
    return new QwenUsage(
        longVal(usage.get("prompt_tokens")),
        longVal(usage.get("completion_tokens")),
        longVal(usage.get("total_tokens"))
    );
  }

  private static long longVal(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (Exception ex) {
      return 0;
    }
  }

  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000;
  }
}
