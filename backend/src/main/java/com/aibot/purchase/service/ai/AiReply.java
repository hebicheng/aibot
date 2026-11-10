package com.aibot.purchase.service.ai;

import java.util.Map;

/**
 * AI 分析结果，由 ChatAiService 产出、ChatService 落库成 ChatMessage。
 *
 * @param intent       业务意图，同时作为 ChatMessage.businessType
 * @param messageType  CARD / TEXT
 * @param status       pending / done
 * @param content      给用户展示的中文话术
 * @param payload      结构化字段（见各卡片 schema），TEXT 类型可为空
 */
public record AiReply(
    String intent,
    String messageType,
    String status,
    String content,
    Map<String, Object> payload
) {
  public static AiReply card(String intent, String content, Map<String, Object> payload) {
    return new AiReply(intent, "CARD", "pending", content, payload);
  }

  public static AiReply text(String intent, String content) {
    return new AiReply(intent, "TEXT", "done", content, Map.of());
  }
}
