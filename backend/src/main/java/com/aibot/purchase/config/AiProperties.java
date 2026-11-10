package com.aibot.purchase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通义千问大模型配置。mock=true 时不调用外部接口，返回结构化兜底结果。
 * 图片直接送入模型，PDF 由后端渲染为页面图片后送入模型。
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(boolean mock, Qwen qwen) {

  public AiProperties {
    if (qwen == null) {
      qwen = new Qwen(null, "https://dashscope.aliyuncs.com/compatible-mode", "qwen3.7-plus", 120);
    }
  }

  public record Qwen(String apiKey, String baseUrl, String model, int timeoutSeconds) {
  }
}
