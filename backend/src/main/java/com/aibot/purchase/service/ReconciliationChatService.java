package com.aibot.purchase.service;

import com.aibot.purchase.entity.ChatMessage;
import com.aibot.purchase.entity.ReconciliationReminder;
import com.aibot.purchase.mapper.ChatMessageMapper;
import com.aibot.purchase.mapper.ReconciliationReminderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 对账单导出的聊天识别（两层）：
 *
 * <ol>
 *   <li>规则：去掉标点后是「纯对账单/台账短句」（如「对账单」「我要对账单」，不含任何日期字眼）
 *       → 立即插统一 {@code reconciliation_export} 卡片（默认当月 1 日~今天），零延迟零成本；</li>
 *   <li>AI 兜底：其余任意说法由 {@link ChatAiService} 识别出 reconciliation_export 意图
 *       （数据日期范围 start/end、预约时间 triggerAt），{@link #finalizeAiIntent} 落地——
 *       有 triggerAt 建一次性预约（文字确认，到点由调度器推卡片），否则立即出卡片。</li>
 * </ol>
 * 日期解析完全交给大模型（提示词注入今天日期），不在代码里维护日期正则。
 */
@Service
public class ReconciliationChatService {

  /** 统一导出卡片 */
  public record CardOutcome(ChatMessage userMessage, ChatMessage aiMessage) {}

  private static final Pattern KEYWORD = Pattern.compile("对账单|台账");
  /** 关键词之外的日期字眼：出现即说明用户在指定范围/时间，交给 AI 解析 */
  private static final Pattern DATE_HINT = Pattern.compile("[0-9一二三四五六七八九十周月天日号年]");

  private final ChatMessageMapper chatMessageMapper;
  private final ReconciliationReminderMapper reminderMapper;
  private final ReconciliationExportService exportService;
  private final com.aibot.purchase.mapper.ProjectMapper projectMapper;
  private final ObjectMapper objectMapper;

  public ReconciliationChatService(
      ChatMessageMapper chatMessageMapper,
      ReconciliationReminderMapper reminderMapper,
      ReconciliationExportService exportService,
      com.aibot.purchase.mapper.ProjectMapper projectMapper,
      ObjectMapper objectMapper
  ) {
    this.chatMessageMapper = chatMessageMapper;
    this.reminderMapper = reminderMapper;
    this.exportService = exportService;
    this.projectMapper = projectMapper;
    this.objectMapper = objectMapper;
  }

  /**
   * 规则层：仅接管「裸关键词短句」（如「对账单」「我要对账单」）；
   * 含任何日期字眼的说法一律返回 null 交给 AI 兜底解析。
   */
  public CardOutcome tryHandle(String userId, String projectId, String batchId, String text) {
    if (!StringUtils.hasText(text) || !isBareKeyword(text)) {
      return null;
    }
    ChatMessage userMessage = insertMessage(userId, projectId, batchId, "USER", "TEXT",
        "reconciliation_export", text.trim(), "done", null);
    LocalDate today = LocalDate.now();
    ChatMessage aiMessage = insertMessage(userId, projectId, batchId, "AI", "CARD",
        "reconciliation_export", "对账单导出", "done",
        buildCardPayload(userId, projectId, today.withDayOfMonth(1), today, "chat"));
    return new CardOutcome(userMessage, aiMessage);
  }

  /**
   * AI 兜底：analyzeText 识别出 reconciliation_export 意图后落地——
   * 有 triggerAt 建一次性预约（文字确认），否则立即插统一卡片（start/end 缺省当月 1 日~今天）。
   */
  public ChatMessage finalizeAiIntent(
      String userId, String projectId, String batchId,
      Map<String, Object> intentPayload, String replyText
  ) {
    LocalDate rawStart = parseDate(intentPayload == null ? null : intentPayload.get("start"));
    LocalDate rawEnd = parseDate(intentPayload == null ? null : intentPayload.get("end"));
    // 区分「AI 没识别到预约意图」与「识别到了但格式没解析出来」：后者不能静默降级成
    // 立即出卡片（用户以为预约成功，到点却没推），必须明确告知预约未生效。
    Object triggerRaw = intentPayload == null ? null : intentPayload.get("triggerAt");
    String triggerText = triggerRaw == null ? null : String.valueOf(triggerRaw).trim();
    boolean hasTriggerIntent = StringUtils.hasText(triggerText)
        && !"null".equalsIgnoreCase(triggerText);
    LocalDateTime trigger = hasTriggerIntent ? parseDateTime(triggerText) : null;

    if (trigger != null) {
      ReconciliationReminder reminder = new ReconciliationReminder();
      reminder.setUserId(userId);
      reminder.setProjectId(projectId);
      reminder.setBatchId(batchId);
      reminder.setTriggerAt(trigger);
      reminder.setDataStart(rawStart);
      reminder.setDataEnd(rawEnd);
      reminder.setStatus("pending");
      reminder.setSource("chat");
      reminder.setCreatedAt(LocalDateTime.now());
      reminder.setUpdatedAt(LocalDateTime.now());
      reminderMapper.insert(reminder);
      String rangeText = rawStart != null && rawEnd != null
          ? "数据范围 " + rawStart + " 至 " + rawEnd
          : "到点卡片里可改日期范围";
      // 回显系统认定的触发时间：AI 换算错相对时间时用户一眼能看出来
      String timeText = "，系统将于 " + trigger.toLocalDate() + " "
          + trigger.toLocalTime().withSecond(0).withNano(0) + " 推送";
      String content = StringUtils.hasText(replyText)
          ? replyText + "（" + rangeText + timeText + "）"
          : "已预约，到点把对账单卡片发到这里，" + rangeText + timeText + "。";
      return insertMessage(userId, projectId, batchId, "AI", "TEXT",
          "reconciliation_export", content, "done", null);
    }

    if (hasTriggerIntent) {
      // 说了预约时间但格式没解析出来：明确告知未生效，请用户换说法重试，绝不静默出卡片
      String content = StringUtils.hasText(replyText) ? replyText : "";
      return insertMessage(userId, projectId, batchId, "AI", "TEXT",
          "reconciliation_export",
          content + "抱歉，没认出您说的推送时间（系统读到了「" + triggerText + "」），"
              + "这次预约没有生效。请换个写法再发一次，比如「明天 15:00 推对账单」；"
              + "或直接说「现在要对账单」立即生成。",
          "done", null);
    }

    LocalDate start = rawStart != null ? rawStart : LocalDate.now().withDayOfMonth(1);
    LocalDate end = rawEnd != null ? rawEnd : LocalDate.now();
    if (start.isAfter(end)) {
      LocalDate swap = start;
      start = end;
      end = swap;
    }
    return insertMessage(userId, projectId, batchId, "AI", "CARD",
        "reconciliation_export",
        StringUtils.hasText(replyText) ? replyText : "对账单导出", "done",
        buildCardPayload(userId, projectId, start, end, "chat"));
  }

  /** 周提醒/预约到点共用的统一卡片 payload（summary 由前端按当前日期实时拉取，此处仅初始值） */
  public Map<String, Object> buildCardPayload(
      String userId, String projectId, LocalDate start, LocalDate end, String source
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "1.0");
    payload.put("cardType", "reconciliation_export");
    payload.put("source", source);
    payload.put("projectId", projectId);
    payload.put("start", start == null ? null : start.toString());
    payload.put("end", end == null ? null : end.toString());
    com.aibot.purchase.entity.Project project = projectMapper.selectById(projectId);
    if (project != null) {
      payload.put("projectName", project.getName());
      payload.put("customerName", project.getCustomerName());
    }
    if (start != null && end != null) {
      ReconciliationExportService.RangeSummary summary =
          exportService.rangeSummary(userId, projectId, start, end);
      payload.put("summary", Map.of(
          "deliveries", summary.deliveries(),
          "salesAmount", summary.salesAmount()
      ));
    }
    return payload;
  }

  /**
   * 裸关键词短句：去掉标点后仍很短（≤5 字）、含「对账单/台账」，且关键词之外
   * 不含任何日期字眼（我/要/帮我 等可以）。「上周对账单」「9.12对账单」等一律放行给 AI。
   */
  private boolean isBareKeyword(String text) {
    String stripped = text.replaceAll("[\\s，,。．.!！?？、～~]", "");
    if (!KEYWORD.matcher(stripped).find() || stripped.length() > 5) {
      return false;
    }
    String withoutKeyword = KEYWORD.matcher(stripped).replaceAll("");
    return !DATE_HINT.matcher(withoutKeyword).find();
  }

  private ChatMessage insertMessage(String userId, String projectId, String batchId,
                                    String sender, String messageType, String businessType,
                                    String content, String status, Map<String, Object> payload) {
    ChatMessage msg = new ChatMessage();
    msg.setUserId(userId);
    msg.setProjectId(projectId);
    msg.setBatchId(batchId);
    msg.setSender(sender);
    msg.setMessageType(messageType);
    msg.setBusinessType(businessType);
    msg.setContent(content);
    msg.setStatus(status);
    if (payload != null && !payload.isEmpty()) {
      try {
        msg.setPayload(objectMapper.writeValueAsString(payload));
      } catch (Exception ignored) {
        // payload 序列化失败时留空，卡片降级显示
      }
    }
    msg.setCreatedAt(LocalDateTime.now());
    chatMessageMapper.insert(msg);
    return msg;
  }

  private static LocalDate parseDate(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(String.valueOf(value).trim().substring(0, 10));
    } catch (Exception ex) {
      return null;
    }
  }

  /**
   * 兼容 LLM 常见输出形态：ISO（T 分隔）、带/不带秒、带毫秒尾巴、只精确到小时；
   * 全部失败返回 null（由调用方走「预约未生效」提示，不得静默降级）。
   */
  private static LocalDateTime parseDateTime(Object value) {
    if (value == null) {
      return null;
    }
    String raw = String.valueOf(value).trim();
    int dot = raw.indexOf('.');
    if (dot > 0) {
      raw = raw.substring(0, dot);
    }
    String spaced = raw.replace('T', ' ');
    if (spaced.length() == 13) {
      spaced = spaced + ":00";
    }
    for (String text : new String[] {spaced, raw}) {
      try {
        return LocalDateTime.parse(text);
      } catch (Exception ignored) {
        // 继续尝试显式格式
      }
      for (String pattern : new String[] {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"}) {
        try {
          return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern));
        } catch (Exception ignored) {
          // 继续尝试
        }
      }
    }
    return null;
  }
}
