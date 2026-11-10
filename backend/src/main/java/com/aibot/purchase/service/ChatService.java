package com.aibot.purchase.service;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.common.InsufficientCreditsException;
import com.aibot.purchase.dto.BusinessDtos.ChatAppendResponse;
import com.aibot.purchase.dto.BusinessDtos.ChatMessageDto;
import com.aibot.purchase.dto.BusinessDtos.ChatSendRequest;
import com.aibot.purchase.entity.ChatMessage;
import com.aibot.purchase.mapper.ChatMessageMapper;
import org.springframework.http.HttpStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 聊天消息服务（精简版）：保留消息查询能力，send/upload 委托给 DocumentService。
 */
@Service
public class ChatService {
  private final ChatMessageMapper chatMessageMapper;
  private final BusinessQueryService businessQueryService;
  private final DocumentService documentService;
  private final UserService userService;
  private final ObjectMapper objectMapper;
  private final ReconciliationChatService reconciliationChatService;
  private final BatchHintService batchHintService;
  private final com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService;

  public ChatService(
      ChatMessageMapper chatMessageMapper,
      BusinessQueryService businessQueryService,
      DocumentService documentService,
      UserService userService,
      ObjectMapper objectMapper,
      ReconciliationChatService reconciliationChatService,
      BatchHintService batchHintService,
      com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService
  ) {
    this.chatMessageMapper = chatMessageMapper;
    this.businessQueryService = businessQueryService;
    this.documentService = documentService;
    this.userService = userService;
    this.objectMapper = objectMapper;
    this.reconciliationChatService = reconciliationChatService;
    this.batchHintService = batchHintService;
    this.marketQuoteImportService = marketQuoteImportService;
  }

  public List<ChatMessageDto> messages(String userId, String projectId, String batchId) {
    return chatMessageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
            .eq(ChatMessage::getUserId, userId)
            .eq(projectId != null && !projectId.isBlank(), ChatMessage::getProjectId, projectId)
            // 批次过滤同时包含项目级消息（batchId 为空，如周提醒/预约到点卡片）
            .and(batchId != null && !batchId.isBlank(),
                w -> w.eq(ChatMessage::getBatchId, batchId).or().isNull(ChatMessage::getBatchId))
            .orderByAsc(ChatMessage::getCreatedAt))
        .stream()
        .map(this::toDto)
        .toList();
  }

  /** 组装 AI 上下文（含生效口径文本 + 批次/项目两层原始 JSON，供 batch_hint 判断范围与合并）。 */
  private ChatAiService.AiContext aiContext(String userId, String projectId, String batchId) {
    var batch = businessQueryService.getBatch(userId, batchId);
    var project = businessQueryService.getProject(userId, projectId);
    var profile = userService.getProfile(userId);
    var hints = batchHintService.contextFor(batchId);
    return new ChatAiService.AiContext(
        profile.companyName(), batch.projectName(), batch.name(), project.customerName(),
        hints.effectiveText(), hints.batchJson(), hints.projectJson());
  }

  @Transactional
  public ChatAppendResponse send(String userId, ChatSendRequest request) {
    var aiContext = aiContext(userId, request.projectId(), request.batchId());
    // 对账单导出意图最先识别：即使在追问续接状态，「导出对账单」也不可能是追问的答案
    // 权威行情导入：按钮走专用接口（confirm/discardQuoteCard），文本发送不再拦截
    var recon = reconciliationChatService.tryHandle(
        userId, request.projectId(), request.batchId(), request.text());
    if (recon != null) {
      return new ChatAppendResponse(List.of(toDto(recon.userMessage()), toDto(recon.aiMessage())));
    }
    // 本轮输入可能是针对上一轮追问的补充：优先续接，避免用户重新上传原文件
    ChatMessage pendingClarification = findPendingClarification(userId, request.batchId());
    Map<String, Object> prior = null;
    String pendingDocumentId = null;
    if (pendingClarification != null) {
      Map<String, Object> payload = fromJson(pendingClarification.getPayload());
      prior = payload;
      // 只有明确选择了追问选项时才复用原文件，避免把全新的业务输入误当作追问回答
      if (payload.get("documentId") != null
          && matchesOption(payload.get("options"), request.text())) {
        pendingDocumentId = String.valueOf(payload.get("documentId"));
      }
    }
    try {
      var result = pendingDocumentId != null
          ? documentService.continueClarification(userId, pendingDocumentId, request.text(), aiContext)
          : documentService.sendText(
              userId, request.text(), request.projectId(), request.batchId(), aiContext, prior);
      return new ChatAppendResponse(List.of(toDto(result.userMessage()), toDto(result.aiMessage())));
    } catch (InsufficientCreditsException ex) {
      throw new ApiException(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
    }
  }

  /**
   * 追问续接：用户选择或输入答案后，基于已保存的原始附件继续处理。
   */
  @Transactional
  public ChatAppendResponse clarify(String userId, String documentId, String answer) {
    var document = documentService.getById(userId, documentId);
    var aiContext = aiContext(userId, document.getProjectId(), document.getBatchId());
    try {
      var result = documentService.continueClarification(userId, documentId, answer, aiContext);
      return new ChatAppendResponse(List.of(toDto(result.userMessage()), toDto(result.aiMessage())));
    } catch (InsufficientCreditsException ex) {
      throw new ApiException(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
    }
  }

  /**
   * 行情待确认卡编辑（2026-11-04）：用户在卡上核对/修正 AI 抽取的行情行后保存；
   * 只放行 rows / city / quoteDate / source 四个键，options 与确认状态不受影响。
   */
  @Transactional
  public void updateQuoteCard(String userId, String messageId, Map<String, Object> edits) {
    ChatMessage msg = chatMessageMapper.selectById(messageId);
    if (msg == null || !userId.equals(msg.getUserId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "行情卡片不存在");
    }
    if (!"market_quote_card".equals(msg.getBusinessType())
        || !"pending".equals(msg.getStatus())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "该行情卡片不在待确认状态，不能编辑");
    }
    Map<String, Object> payload = fromJson(msg.getPayload());
    if (edits.containsKey("rows") && edits.get("rows") instanceof List<?>) {
      payload.put("rows", edits.get("rows"));
    }
    if (edits.containsKey("city")) {
      payload.put("city", edits.get("city"));
    }
    if (edits.containsKey("quoteDate")) {
      payload.put("quoteDate", edits.get("quoteDate"));
    }
    if (edits.containsKey("source")) {
      payload.put("source", edits.get("source"));
    }
    msg.setPayload(toJson(payload));
    chatMessageMapper.updateById(msg);
  }

  /** 校验并取回待确认行情卡（本人 + market_quote_card + pending）。 */
  private ChatMessage requirePendingQuoteCard(String userId, String messageId) {
    ChatMessage msg = chatMessageMapper.selectById(messageId);
    if (msg == null || !userId.equals(msg.getUserId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "行情卡片不存在");
    }
    if (!"market_quote_card".equals(msg.getBusinessType())
        || !"pending".equals(msg.getStatus())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "该行情卡片不在待确认状态");
    }
    return msg;
  }

  /**
   * 确认导入行情卡（卡片按钮专用，2026-11-04）：归档卡片 → 落库 + 刷新受影响批次 → 追加结果消息。
   * 不合成用户文本消息，与其他卡片按钮行为一致。
   */
  @Transactional
  public ChatAppendResponse confirmQuoteCard(String userId, String messageId) {
    ChatMessage pendingQuote = requirePendingQuoteCard(userId, messageId);
    // 年份护栏：截图只显示「月-日」时模型可能臆造年份（把 2026-09-16 补成 2024），
    // 落到任何批次都取不到的死日期。年份与当前不符就不归档、不落库，让用户在卡上改完日期再确认。
    Map<String, Object> payload = fromJson(pendingQuote.getPayload());
    String quoteDateText = payload.get("quoteDate") == null ? "" : String.valueOf(payload.get("quoteDate")).trim();
    if (StringUtils.hasText(quoteDateText)) {
      try {
        int year = java.time.LocalDate.parse(quoteDateText).getYear();
        int currentYear = java.time.LocalDate.now().getYear();
        if (year != currentYear) {
          ChatMessage warn = insertMessage(userId, pendingQuote.getProjectId(), pendingQuote.getBatchId(),
              "AI", "TEXT", "market_quote_card",
              "识别到的行情日期是 " + quoteDateText + "，年份与当前年份（" + currentYear
                  + "）不符——截图只显示月-日时 AI 可能补错了年份，这样的行情任何批次都取不到。"
                  + "请点卡片上的「编辑」把日期改成正确年份后再确认导入（卡片仍保留，未写入行情库）。",
              "done", null);
          return new ChatAppendResponse(List.of(toDto(warn)));
        }
      } catch (java.time.format.DateTimeParseException ignored) {
        // 非法日期由导入逻辑的兜底（按今天）处理
      }
    }
    // 先导入后归档：confirm 内部会按 status=pending 重查这张卡读取行情行，
    // 先归档它就查不到了（2026-11-04 修：导入 0 条的根因）
    var outcome = marketQuoteImportService.confirm(userId, pendingQuote.getBatchId());
    marketQuoteImportService.archivePending(pendingQuote, "confirmed");
    StringBuilder content = new StringBuilder();
    if (outcome.imported() > 0) {
      content.append("已导入 ").append(outcome.imported()).append(" 条权威行情（")
          .append(outcome.city()).append(" ").append(outcome.quoteDate())
          .append("，来自你核对的截图），行情库里与爬取数据并存、取价时优先用权威价。");
      content.append(outcome.refreshedBatches() > 0
          ? "当前批次已按新价重锁重算"
            + (outcome.backgroundBatches() > 0
              ? "，另有 " + outcome.backgroundBatches() + " 个批次正在后台刷新（完成后自动生效）"
              : "")
            + "；人工绑定过的价格保持不动。"
          : "目前没有批次用到这批行情：常见原因是该日期不在任何材料单据的取价日上，或还没有材料绑定这些规格。"
            + "请到「市场行情 · 批次材料」核对绑定与材料日期；后续绑定/日期匹配上的批次会用到这批价。");
      if (outcome.skipped() > 0) {
        content.append("另有 ").append(outcome.skipped())
            .append(" 行认不出品类或没有价格，已跳过。");
      }
    } else {
      content.append("没有可导入的行情行：识别不到有效的品类/规格/价格，行情库未变更。")
          .append("请确认截图内容后重新发送。");
    }
    ChatMessage aiMsg = insertMessage(userId, pendingQuote.getProjectId(), pendingQuote.getBatchId(),
        "AI", "TEXT", "market_quote_card", content.toString(), "done", null);
    return new ChatAppendResponse(List.of(toDto(aiMsg)));
  }

  /** 放弃行情卡（卡片按钮专用）：归档卡片，行情库不变。 */
  @Transactional
  public ChatAppendResponse discardQuoteCard(String userId, String messageId) {
    ChatMessage pendingQuote = requirePendingQuoteCard(userId, messageId);
    marketQuoteImportService.archivePending(pendingQuote, "cancelled");
    ChatMessage aiMsg = insertMessage(userId, pendingQuote.getProjectId(), pendingQuote.getBatchId(),
        "AI", "TEXT", "market_quote_card", "已放弃本次行情导入，行情库未变更。", "done", null);
    return new ChatAppendResponse(List.of(toDto(aiMsg)));
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
    msg.setPayload(payload == null ? null : toJson(payload));
    msg.setCreatedAt(java.time.LocalDateTime.now());
    chatMessageMapper.insert(msg);
    return msg;
  }

  private String toJson(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception ex) {
      return null;
    }
  }

  /** 用户输入是否恰好是追问给出的某个选项。 */
  private boolean matchesOption(Object options, String text) {
    if (!(options instanceof List<?> list) || !StringUtils.hasText(text)) {
      return false;
    }
    String trimmed = text.trim();
    return list.stream().anyMatch(option -> trimmed.equals(String.valueOf(option).trim()));
  }

  /** 当前批次最近一条尚未回答的追问消息。 */
  private ChatMessage findPendingClarification(String userId, String batchId) {
    return chatMessageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
            .eq(ChatMessage::getUserId, userId)
            .eq(ChatMessage::getBatchId, batchId)
            .eq(ChatMessage::getBusinessType, "clarify_card")
            .eq(ChatMessage::getStatus, "pending")
            .orderByDesc(ChatMessage::getCreatedAt))
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Transactional
  public ChatAppendResponse upload(
      String userId,
      MultipartFile file,
      String projectId,
      String batchId,
      String guidance
  ) {
    var aiContext = aiContext(userId, projectId, batchId);
    try {
      var result = documentService.uploadAndRecognize(userId, file, projectId, batchId, guidance, aiContext);
      return new ChatAppendResponse(List.of(toDto(result.userMessage()), toDto(result.aiMessage())));
    } catch (InsufficientCreditsException ex) {
      throw new ApiException(HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private ChatMessageDto toDto(ChatMessage message) {
    var project = message.getProjectId() == null ? null : businessQueryService.getProject(message.getUserId(), message.getProjectId());
    var batch = message.getBatchId() == null ? null : businessQueryService.getBatch(message.getUserId(), message.getBatchId());
    return new ChatMessageDto(
        message.getId(),
        message.getSender(),
        message.getMessageType(),
        message.getBusinessType(),
        message.getContent(),
        message.getFileName(),
        message.getStatus(),
        message.getProjectId(),
        project == null ? null : project.name(),
        message.getBatchId(),
        batch == null ? null : batch.name(),
        fromJson(message.getPayload()),
        message.getCreatedAt() == null ? null : message.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
        message.getCreditsUsed()
    );
  }

  private Map<String, Object> fromJson(String payload) {
    if (payload == null || payload.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(payload, new TypeReference<>() {
      });
    } catch (Exception ex) {
      return new LinkedHashMap<>();
    }
  }
}
