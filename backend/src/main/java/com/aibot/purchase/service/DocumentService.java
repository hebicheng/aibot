package com.aibot.purchase.service;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.BusinessDtos.BatchDto;
import com.aibot.purchase.dto.BusinessDtos.ProjectDto;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.ChatMessage;
import com.aibot.purchase.entity.LedgerRecord;
import com.aibot.purchase.entity.Material;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.ChatMessageMapper;
import com.aibot.purchase.mapper.MaterialMapper;
import com.aibot.purchase.service.ai.AiReply;
import com.aibot.purchase.service.ai.DocumentMediaPreprocessor;
import com.aibot.purchase.service.BillingService;
import com.aibot.purchase.service.storage.FileStorage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 单据全生命周期服务：上传→AI识别→落库→确认/编辑→归档。
 * 核心流程：文件存 COS → 千问 VL 多模态识别 → 结构化落 documents 表 → 前端卡片确认。
 */
@Service
public class DocumentService {
  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  /** 「按毛利率算」未给数字时的默认毛利率（%）。 */
  static final BigDecimal DEFAULT_MARGIN_RATE = new BigDecimal("2.5");
  /** 补充说明里的毛利率指令：毛利率2.5% / 毛利2.5个点 / 毛利率为3%。 */
  private static final Pattern MARGIN_RATE_HINT =
      Pattern.compile("(?:毛利率|毛利)[^0-9%]{0,6}(\\d+(?:\\.\\d+)?)\\s*(?:%|％|个点|点)");
  /** 补充说明里的默认毛利率指令：按毛利率算（未给数字）。 */
  private static final Pattern MARGIN_DEFAULT_HINT = Pattern.compile("按毛利率");
  /** 补充说明里的上浮指令：网价加30 / 网价+40 / 上浮35 / 加价40。 */
  private static final Pattern UPLIFT_HINT =
      Pattern.compile("(?:网价|上浮|加价)[^0-9%]{0,2}(\\d+(?:\\.\\d+)?)");

  /** 默认销售上浮（与 DeliveryNetPriceService 同源，卡片定价说明用）。 */
  @Value("${purchase.sales-uplift:30}")
  private BigDecimal defaultSalesUplift;

  private final DocumentMapper documentMapper;
  private final DocumentItemMapper documentItemMapper;
  private final ChatMessageMapper chatMessageMapper;
  private final ChatAiService chatAiService;
  private final FileStorage fileStorage;
  private final DocumentMediaPreprocessor mediaPreprocessor;
  private final BusinessQueryService businessQueryService;
  private final LedgerService ledgerService;
  private final MaterialMapper materialMapper;
  private final BatchMapper batchMapper;
  private final ObjectMapper objectMapper;
  private final com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService;
  private final com.aibot.purchase.service.market.DeliveryNetPriceTrigger deliveryNetPriceTrigger;
  private final com.aibot.purchase.service.market.MaterialLayerTrigger materialLayerTrigger;
  private final com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService;
  /** 「说明文字是否提到运费」：附带信息优先，提到就不套用批次运费默认值（与批次口径声明同族句式）。 */
  private static final Pattern FREIGHT_MENTION = BatchHintService.FREIGHT_MENTION_PATTERN;

  private final BillingService billingService;
  private final ReconciliationChatService reconciliationChatService;
  private final BatchHintService batchHintService;
  private final com.aibot.purchase.service.market.PurchaseSalePriceService purchaseSalePriceService;
  private final com.aibot.purchase.mapper.DocumentItemMarketMappingMapper itemNetMappingMapper;

  public DocumentService(
      DocumentMapper documentMapper,
      DocumentItemMapper documentItemMapper,
      ChatMessageMapper chatMessageMapper,
      ChatAiService chatAiService,
      FileStorage fileStorage,
      DocumentMediaPreprocessor mediaPreprocessor,
      BusinessQueryService businessQueryService,
      LedgerService ledgerService,
      MaterialMapper materialMapper,
      BatchMapper batchMapper,
      ObjectMapper objectMapper,
      com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService,
      com.aibot.purchase.service.market.DeliveryNetPriceTrigger deliveryNetPriceTrigger,
      com.aibot.purchase.service.market.MaterialLayerTrigger materialLayerTrigger,
      com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService,
      BillingService billingService,
      ReconciliationChatService reconciliationChatService,
      BatchHintService batchHintService,
      com.aibot.purchase.service.market.PurchaseSalePriceService purchaseSalePriceService,
      com.aibot.purchase.mapper.DocumentItemMarketMappingMapper itemNetMappingMapper
  ) {
    this.documentMapper = documentMapper;
    this.documentItemMapper = documentItemMapper;
    this.chatMessageMapper = chatMessageMapper;
    this.chatAiService = chatAiService;
    this.fileStorage = fileStorage;
    this.mediaPreprocessor = mediaPreprocessor;
    this.businessQueryService = businessQueryService;
    this.ledgerService = ledgerService;
    this.materialMapper = materialMapper;
    this.batchMapper = batchMapper;
    this.objectMapper = objectMapper;
    this.deliveryNetPriceService = deliveryNetPriceService;
    this.deliveryNetPriceTrigger = deliveryNetPriceTrigger;
    this.materialLayerTrigger = materialLayerTrigger;
    this.marketQuoteImportService = marketQuoteImportService;
    this.billingService = billingService;
    this.reconciliationChatService = reconciliationChatService;
    this.batchHintService = batchHintService;
    this.purchaseSalePriceService = purchaseSalePriceService;
    this.itemNetMappingMapper = itemNetMappingMapper;
  }

  /**
   * 上传文件 → AI 识别 → 创建待确认单据 + 聊天消息
   */
  public UploadResult uploadAndRecognize(String userId, MultipartFile file, String projectId,
                                         String batchId, String userText, ChatAiService.AiContext aiContext) {
    requireContext(userId, projectId, batchId);
    if (file == null || file.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "文件为空");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "读取文件失败");
    }
    String originalName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "上传文件";
    var prepared = mediaPreprocessor.prepare(bytes, originalName, file.getContentType());
    String fileHash = sha256(bytes);

    // 1. 存储到 COS
    FileStorage.StoredFile stored = fileStorage.store(bytes, originalName, file.getContentType());

    // 2. AI 多模态识别
    ChatAiService.AiAnalysisResult analysis = chatAiService.analyzeFile(
        prepared.mediaInputs(),
        prepared.extractedText(),
        userText,
        aiContext
    );
    AiReply reply = analysis.reply();

    // 3. 落库：chat message + document
    Map<String, Object> uploadPayload = new LinkedHashMap<>();
    uploadPayload.put("fileName", originalName);
    if (StringUtils.hasText(userText)) {
      uploadPayload.put("guidanceText", userText.trim());
    }
    ChatMessage userMsg = insertChatMessage(userId, projectId, batchId, "USER", "FILE",
        "upload", "上传单据：" + originalName, "done", uploadPayload);
    ChatMessage aiMsg = insertChatMessage(userId, projectId, batchId, "AI", "CARD",
        reply.intent(), reply.content(), "pending", reply.payload());
    java.math.BigDecimal credits = billingService.charge(userId, projectId, batchId, "chat", null,
        analysis.usage(), aiMsg.getId());
    aiMsg.setCreditsUsed(credits);
    chatMessageMapper.updateById(aiMsg);

    Document doc = null;
    if ("market_quote_card".equals(reply.intent())
        && reply.payload() != null && !reply.payload().isEmpty()) {
      // 行情截图：不建单据、不进台账。卡片挂「确认导入行情/放弃」选项，
      // 用户确认后由 MarketQuoteImportService 落库并刷新受影响批次
      Map<String, Object> cardPayload = new LinkedHashMap<>(reply.payload());
      cardPayload.put("options", List.of(
          marketQuoteImportService.OPTION_CONFIRM, marketQuoteImportService.OPTION_CANCEL));
      aiMsg.setPayload(toJson(cardPayload));
      chatMessageMapper.updateById(aiMsg);
      return new UploadResult(userMsg, aiMsg, null);
    }
    if (reply.payload() != null && !reply.payload().isEmpty() && !"non_business".equals(reply.intent())
        && !"batch_hint".equals(reply.intent())) {
      if ("clarify_card".equals(reply.intent())) {
        // 仍然无法判定归属时也要保住原始附件：建立待澄清记录，用户补充后无需重新上传
        doc = createClarificationDocument(
            userId, projectId, batchId, reply, stored, originalName, aiMsg.getId(),
            prepared.extractedText(), userText, fileHash
        );
      } else {
        doc = createDocumentFromPayload(
            userId, projectId, batchId, reply, stored, originalName, aiMsg.getId(),
            prepared.extractedText(), userText, fileHash
        );
        detectDuplicate(doc);
        resolveAutomaticTarget(doc);
      }
      updateAiMessageCard(aiMsg, doc);
    }

    // 用户已经给出处理说明，但模型仍判定为「追问」：立即用同一份文件按说明再识别一次。
    // 这样既能拿到完整明细，也不需要用户重新上传或再点一次选项。
    if (doc != null && "needs_clarification".equals(doc.getStatus())
        && StringUtils.hasText(userText) && prepared != null) {
      doc = rerunWithGuidance(doc, userText.trim(), aiContext, prepared, aiMsg);
    }

    return new UploadResult(userMsg, aiMsg, doc);
  }

  /**
   * 用已上传的原始文件按用户说明重新识别一次（追问场景的自动续接）。
   */
  private Document rerunWithGuidance(
      Document doc,
      String guidance,
      ChatAiService.AiContext aiContext,
      DocumentMediaPreprocessor.PreparedDocument prepared,
      ChatMessage aiMsg
  ) {
    Map<String, Object> prior = structuredData(doc);
    ChatAiService.AiAnalysisResult analysis = chatAiService.analyzeFile(
        prepared.mediaInputs(),
        StringUtils.hasText(prepared.extractedText()) ? prepared.extractedText() : doc.getSourceText(),
        guidance, aiContext, prior);
    AiReply reply = analysis.reply();
    java.math.BigDecimal credits = billingService.charge(doc.getUserId(), doc.getProjectId(),
        doc.getBatchId(), "chat", null, analysis.usage(), aiMsg.getId());
    aiMsg.setCreditsUsed(nz(aiMsg.getCreditsUsed()).add(nz(credits)));
    aiMsg.setContent(reply.content());
    aiMsg.setBusinessType(reply.intent());
    if (!"non_business".equals(reply.intent()) && !"batch_hint".equals(reply.intent())
        && reply.payload() != null && !reply.payload().isEmpty()) {
      if ("clarify_card".equals(reply.intent())) {
        applyClarificationResult(doc, reply, guidance, aiMsg.getId());
      } else {
        applyAnalysisToDocument(doc, reply, guidance);
        detectDuplicate(doc);
        resolveAutomaticTarget(doc);
      }
    }
    updateAiMessageCard(aiMsg, doc);
    return doc;
  }

  /**
   * 纯文本输入 → AI 识别（短信、手动输入等）
   */
  public UploadResult sendText(String userId, String text, String projectId,
                               String batchId, ChatAiService.AiContext aiContext) {
    return sendText(userId, text, projectId, batchId, aiContext, null);
  }

  /**
   * 纯文本输入 → AI 识别（短信、手动输入等）
   * @param priorClarification 上一轮追问内容，存在时把本条输入视为对该追问的回答。
   */
  public UploadResult sendText(String userId, String text, String projectId,
                               String batchId, ChatAiService.AiContext aiContext,
                               Map<String, Object> priorClarification) {
    requireContext(userId, projectId, batchId);
    // 对账单导出意图规则前置拦截（不进 AI、不扣积分）；追问续接上下文中不接管
    if (priorClarification == null) {
      ReconciliationChatService.CardOutcome outcome =
          reconciliationChatService.tryHandle(userId, projectId, batchId, text);
      if (outcome != null) {
        return new UploadResult(outcome.userMessage(), outcome.aiMessage(), null);
      }
    }
    ChatAiService.AiAnalysisResult analysis =
        chatAiService.analyzeText(text, aiContext, priorClarification);
    AiReply reply = analysis.reply();

    ChatMessage userMsg = insertChatMessage(userId, projectId, batchId, "USER", "TEXT",
        "free_text", text, "done", null);

    // AI 兜底：规则没拦住的导出意图由大模型识别（抽出日期范围/触发时间），不创建 Document
    if ("reconciliation_export".equals(reply.intent())) {
      ChatMessage aiMsg = reconciliationChatService.finalizeAiIntent(
          userId, projectId, batchId, reply.payload(), reply.content());
      java.math.BigDecimal credits = billingService.charge(userId, projectId, batchId, "chat",
          null, analysis.usage(), aiMsg.getId());
      aiMsg.setCreditsUsed(credits);
      chatMessageMapper.updateById(aiMsg);
      return new UploadResult(userMsg, aiMsg, null);
    }

    // 批次口径：AI 判定为单独的业务口径声明（历史口径与本次输入已由 AI 合并），不建单据。
    // 注意：即使本批次挂着未回答的追问卡也照常处理——口径声明与追问无关；
    // 保存成功后归档追问卡，避免它继续把后续输入误判成「回答追问」。
    if ("batch_hint".equals(reply.intent())
        && reply.payload() != null && !reply.payload().isEmpty()) {
      BatchHintService.AppliedHint applied =
          batchHintService.applyAiHint(batchId, reply.payload(), text);
      if (applied.saved()) {
        archivePendingClarification(userId, batchId);
        // 口径变了，已锁定的送货单销售价要跟着重算（毛利率口径还依赖采购价，必须整批重锁），
        // 销售运费也要按新口径覆盖到已确认送货单，否则账上销售金额会少一笔运费
        if (applied.projectLevel()) {
          // 项目级口径：本项目下所有批次都可能受影响（没单独设该字段的批次回退项目口径）
          resyncProjectSales(applied.projectId());
        } else {
          resyncBatchSales(batchId, true);
        }
        businessQueryService.addLog(userId, projectId, batchId,
            applied.projectLevel() ? "project_hint_updated" : "batch_hint_updated",
            (applied.projectLevel() ? "更新项目默认口径：" : "更新批次默认口径：") + text.trim());
      }
      ChatMessage aiMsg = insertChatMessage(userId, projectId, batchId, "AI", "TEXT",
          "batch_hint", applied.replyText(reply.content()), "done", applied.payload());
      java.math.BigDecimal credits = billingService.charge(userId, projectId, batchId, "chat",
          null, analysis.usage(), aiMsg.getId());
      aiMsg.setCreditsUsed(credits);
      chatMessageMapper.updateById(aiMsg);
      return new UploadResult(userMsg, aiMsg, null);
    }

    // 行情截图（文本通道少见但同样处理）：不建单据，挂待确认导入卡
    if ("market_quote_card".equals(reply.intent())
        && reply.payload() != null && !reply.payload().isEmpty()) {
      ChatMessage quoteMsg = insertChatMessage(userId, projectId, batchId, "AI", "CARD",
          reply.intent(), reply.content(), "pending", null);
      Map<String, Object> cardPayload = new LinkedHashMap<>(reply.payload());
      cardPayload.put("options", List.of(
          marketQuoteImportService.OPTION_CONFIRM, marketQuoteImportService.OPTION_CANCEL));
      quoteMsg.setPayload(toJson(cardPayload));
      chatMessageMapper.updateById(quoteMsg);
      java.math.BigDecimal quoteCredits = billingService.charge(userId, projectId, batchId,
          "chat", null, analysis.usage(), quoteMsg.getId());
      quoteMsg.setCreditsUsed(quoteCredits);
      chatMessageMapper.updateById(quoteMsg);
      return new UploadResult(userMsg, quoteMsg, null);
    }

    ChatMessage aiMsg = insertChatMessage(userId, projectId, batchId, "AI", "CARD",
        reply.intent(), reply.content(), "pending", reply.payload());
    java.math.BigDecimal credits = billingService.charge(userId, projectId, batchId, "chat", null,
        analysis.usage(), aiMsg.getId());
    aiMsg.setCreditsUsed(credits);
    chatMessageMapper.updateById(aiMsg);

    Document doc = null;
    if (reply.payload() != null && !reply.payload().isEmpty() && !"non_business".equals(reply.intent())
        && !"clarify_card".equals(reply.intent()) && !"batch_hint".equals(reply.intent())) {
      doc = createDocumentFromPayload(
          userId, projectId, batchId, reply, null, null, aiMsg.getId(),
          text, null, null
      );
      detectDuplicate(doc);
      resolveAutomaticTarget(doc);
      updateAiMessageCard(aiMsg, doc);
    }

    return new UploadResult(userMsg, aiMsg, doc);
  }

  /**
   * 追问续接：用户针对「待澄清资料」的补充回答。
   * 复用该资料已保存的原始附件与解析文本，不要求用户重新上传；识别结果回写到同一条资料记录。
   */
  @Transactional
  public UploadResult continueClarification(
      String userId,
      String documentId,
      String answer,
      ChatAiService.AiContext aiContext
  ) {
    Document doc = requireOwnDocument(userId, documentId);
    if (!"needs_clarification".equals(doc.getStatus())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "该资料已完成识别，无需再次补充");
    }
    if (!StringUtils.hasText(answer)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "补充内容为空");
    }
    Map<String, Object> prior = new LinkedHashMap<>(structuredData(doc));
    String combined = StringUtils.hasText(doc.getGuidanceText())
        ? doc.getGuidanceText().trim() + "；" + answer.trim()
        : answer.trim();

    ChatMessage userMsg = insertChatMessage(userId, doc.getProjectId(), doc.getBatchId(),
        "USER", "TEXT", "clarify_answer", answer.trim(), "done", null);

    byte[] bytes = StringUtils.hasText(doc.getFileObjectKey())
        ? fileStorage.read(doc.getFileObjectKey())
        : new byte[0];
    ChatAiService.AiAnalysisResult analysis;
    if (bytes.length > 0) {
      var prepared = mediaPreprocessor.prepare(bytes, doc.getFileName(), null);
      analysis = chatAiService.analyzeFile(
          prepared.mediaInputs(),
          StringUtils.hasText(prepared.extractedText()) ? prepared.extractedText() : doc.getSourceText(),
          combined, aiContext, prior);
    } else {
      String text = StringUtils.hasText(doc.getSourceText())
          ? combined + "\n原始内容：" + doc.getSourceText()
          : combined;
      analysis = chatAiService.analyzeText(text, aiContext, prior);
    }
    AiReply reply = analysis.reply();

    archiveClarificationMessage(doc);

    ChatMessage aiMsg = insertChatMessage(userId, doc.getProjectId(), doc.getBatchId(), "AI", "CARD",
        reply.intent(), reply.content(), "pending", reply.payload());
    java.math.BigDecimal credits = billingService.charge(userId, doc.getProjectId(), doc.getBatchId(),
        "chat", null, analysis.usage(), aiMsg.getId());
    aiMsg.setCreditsUsed(credits);
    chatMessageMapper.updateById(aiMsg);

    if (!"non_business".equals(reply.intent()) && !"batch_hint".equals(reply.intent())
        && reply.payload() != null && !reply.payload().isEmpty()) {
      if ("clarify_card".equals(reply.intent())) {
        applyClarificationResult(doc, reply, combined, aiMsg.getId());
        aiMsg.setPayload(toJson(buildClarificationCard(doc)));
        aiMsg.setStatus("pending");
        chatMessageMapper.updateById(aiMsg);
      } else {
        applyAnalysisToDocument(doc, reply, combined);
        detectDuplicate(doc);
        resolveAutomaticTarget(doc);
        updateAiMessageCard(aiMsg, doc);
      }
    }
    return new UploadResult(userMsg, aiMsg, doc);
  }

  /** 把新一轮 AI 识别结果应用到已有资料（追问续接成功后调用）。 */
  private void applyAnalysisToDocument(Document doc, AiReply reply, String guidanceText) {
    Map<String, Object> payload = reply.payload() == null ? Map.of() : reply.payload();
    String docType = intentToDocType(reply.intent(), payload, reply.content());
    doc.setDocType(docType);
    doc.setDocCode(firstString(payload, "docCode", "invoiceNo"));
    doc.setSupplierName(extractCounterparty(docType, payload));
    if (payload.get("target") != null && doc.getSupplierName() == null) {
      doc.setSupplierName(str(payload.get("target")));
    }
    doc.setAmount(extractAmount(payload, docType));
    doc.setDocDate(extractDate(payload));
    doc.setRemark(firstString(payload, "remark", "purpose"));
    doc.setConfidence(payload.get("confidence") instanceof Number n ? n.intValue() : null);
    doc.setAiSummary(reply.content());
    doc.setStructuredData(toJson(payload));
    doc.setGuidanceText(guidanceText);
    doc.setStatus("pending");
    doc.setUpdatedAt(LocalDateTime.now());
    documentMapper.updateById(doc);

    documentItemMapper.delete(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, doc.getId()));
    Object itemsObj = payload.get("items");
    if (itemsObj instanceof List<?> items && !items.isEmpty()) {
      insertItems(doc.getId(), castList(items));
    }
    doc.setBusinessFingerprint(businessFingerprint(doc));
    applyPricingPolicy(doc, payload, guidanceText);
    // 送货单运费批次默认值（在定价策略之后：payload 已抽到的销售运费优先）
    documentMapper.updateById(doc);
  }

  /** 续接后仍然无法判定：保留附件，更新追问内容，等待用户再次补充。 */
  private void applyClarificationResult(
      Document doc,
      AiReply reply,
      String guidanceText,
      String messageId
  ) {
    Map<String, Object> payload = new LinkedHashMap<>(
        reply.payload() == null ? Map.of() : reply.payload());
    String detected = str(payload.get("detectedType"));
    if (isKnownDocType(detected)) {
      doc.setDocType(detected);
    }
    doc.setGuidanceText(guidanceText);
    doc.setAiSummary(reply.content());
    doc.setSourceMessageId(messageId);
    doc.setStructuredData(toJson(payload));
    doc.setStatus("needs_clarification");
    doc.setUpdatedAt(LocalDateTime.now());
    documentMapper.updateById(doc);
  }

  /** 把已被回答的追问卡片归档，避免同一条追问重复响应。 */
  private void archiveClarificationMessage(Document doc) {
    if (!StringUtils.hasText(doc.getSourceMessageId())) {
      return;
    }
    ChatMessage message = chatMessageMapper.selectById(doc.getSourceMessageId());
    if (message == null) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>(fromJson(message.getPayload()));
    payload.put("answered", true);
    message.setPayload(toJson(payload));
    message.setStatus("archived");
    chatMessageMapper.updateById(message);
  }

  /**
   * 确认单据：pending → matched
   */
  @Transactional
  public Document confirmDocument(String userId, String documentId) {
    Document doc = requireOwnDocument(userId, documentId);
    if ("matched".equals(doc.getStatus()) || "archived".equals(doc.getStatus())) {
      return doc;
    }
    if (!List.of("pending", "needs_clarification").contains(doc.getStatus())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "单据状态不允许确认：" + doc.getStatus());
    }
    if (StringUtils.hasText(doc.getDuplicateOfDocumentId())
        && !StringUtils.hasText(doc.getDuplicateDecision())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请先确认该单据是否重复");
    }
    resolveAutomaticTarget(doc);
    validateForConfirmation(doc);
    if ("supply_order".equals(doc.getDocType())) {
      applySupplyMaterials(doc);
    }
    // 材料层就绪后再锁价（2026-11-04 双跑优化）：同步规则归并（无 AI）让明细行先挂上材料，
    // 已绑定材料的行直接按材料绑定取价；afterCommit 只补 AI 判同/新绑定，减少一次全批重锁
    if (List.of("delivery_note", "supply_order").contains(doc.getDocType())) {
      materialLayerTrigger.rebuildNow(doc.getBatchId());
    }
    // 送货单确认时同步锁定网价（内存匹配，毫秒级；未命中行 AI 异步兜底），
    // 台账入账读取的就是锁定值；
    // 采购单确认时锁定行情价（批次采购毛利率估算依据，不进行情中心）
    com.aibot.purchase.service.market.DeliveryNetPriceService.LockResult priceLock = null;
    if ("delivery_note".equals(doc.getDocType())) {
      priceLock = deliveryNetPriceService.lockPrices(doc);
    } else if ("supply_order".equals(doc.getDocType())) {
      deliveryNetPriceService.lockSupplyPrices(doc);
    }
    boolean archiveOnly = List.of("sales_settlement", "evidence_only").contains(doc.getDocType());
    doc.setStatus(archiveOnly ? "archived" : "matched");
    doc.setUpdatedAt(LocalDateTime.now());
    documentMapper.updateById(doc);
    ledgerService.applyConfirmedDocument(doc);
    updateSourceMessageCard(doc, cardStatusForConfirmedDocument(doc));

    if (priceLock != null && priceLock.hasPending()) {
      deliveryNetPriceTrigger.afterCommitAiFallback(doc.getId());
    }
    if ("supply_order".equals(doc.getDocType())) {
      refreshConfirmedBatchCards(doc);
      // 采购单到位：同批次送货单按采购价补算/重算销售价（毛利率口径的定价基数就是采购价）
      resyncBatchSales(doc.getBatchId());
    }
    appendOperationResult(doc, "confirmed", confirmationSummary(doc));
    businessQueryService.addLog(userId, doc.getProjectId(), doc.getBatchId(),
        "document_confirmed", referenceTitle(doc) + " " + confirmationSummary(doc));
    // 材料层异步刷新（事务提交后）：规则归并 → AI 判同 → 材料级网价绑定 → 重锁取价。
    // 锁价在确认事务内先按行匹配兜底，材料层就绪后这里再按材料绑定重锁一次。
    if (List.of("delivery_note", "supply_order").contains(doc.getDocType())) {
      materialLayerTrigger.afterCommitRefresh(doc.getBatchId());
    }
    return doc;
  }

  /** 收款管理的修改复用单据编辑路径，同步业务金额、指纹和聊天卡片；原始附件不变。 */
  @Transactional
  public void syncReceiptFromManagement(String userId, String projectId, String documentId,
      BigDecimal amount, LocalDate receiptDate, String payer) {
    Document source = requireOwnDocument(userId, documentId);
    if (!projectId.equals(source.getProjectId()) || !"receipt".equals(source.getDocType())
        || !List.of("matched", "archived").contains(source.getStatus())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "来源不是本项目的已确认收款单，无法同步修改");
    }
    Map<String, Object> edits = new LinkedHashMap<>();
    edits.put("amount", amount);
    edits.put("docDate", receiptDate);
    edits.put("supplierName", payer);
    editDocument(userId, documentId, edits);
  }

  /**
   * 编辑单据（人工修正 AI 识别结果）
   */
  @Transactional
  public Document editDocument(String userId, String documentId, Map<String, Object> edits) {
    Document doc = requireOwnDocument(userId, documentId);
    if (!List.of("pending", "needs_clarification", "matched", "archived").contains(doc.getStatus())
        || "skip".equals(doc.getDuplicateDecision())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "当前单据状态不允许修改");
    }
    boolean confirmed = List.of("matched", "archived").contains(doc.getStatus());
    Document before = copyForCorrection(doc);
    List<DocumentItem> beforeItems = getItemsInternal(documentId);
    if (edits.containsKey("docType")) {
      String nextType = str(edits.get("docType"));
      if (confirmed && !doc.getDocType().equals(nextType)) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "已入库数据不能直接修改业务类型");
      }
      doc.setDocType(nextType);
    }
    if (edits.containsKey("supplierName")) doc.setSupplierName(str(edits.get("supplierName")));
    // amount 为 null/空表示「本次不修改金额」，不得写成 0 覆盖原值。
    if (edits.containsKey("amount") && edits.get("amount") != null
        && !"".equals(String.valueOf(edits.get("amount")).trim())) {
      doc.setAmount(decimal(edits.get("amount")));
    }
    if (edits.containsKey("docCode")) doc.setDocCode(str(edits.get("docCode")));
    if (edits.containsKey("docDate")) doc.setDocDate(date(edits.get("docDate")));
    if (edits.containsKey("remark")) doc.setRemark(str(edits.get("remark")));
    mergeFinancialFields(doc, edits);
    mergeDeliveryNoteFields(doc, edits);
    mergeReviewFields(doc, edits);
    if ("receipt".equals(doc.getDocType())) {
      Map<String, Object> receiptFields = new LinkedHashMap<>(structuredData(doc));
      receiptFields.put("amount", doc.getAmount());
      receiptFields.put("docDate", doc.getDocDate() == null ? null : doc.getDocDate().toString());
      receiptFields.put("supplierName", doc.getSupplierName());
      doc.setStructuredData(toJson(receiptFields));
    }
    // 待澄清资料补字段后即可转为待确认，无需再走一次追问
    if ("needs_clarification".equals(doc.getStatus())) {
      doc.setStatus("pending");
    }
    doc.setUpdatedAt(LocalDateTime.now());
    documentMapper.updateById(doc);

    // 更新明细行
    if (edits.containsKey("items") && edits.get("items") instanceof List<?> items) {
      documentItemMapper.delete(Wrappers.<DocumentItem>lambdaQuery()
          .eq(DocumentItem::getDocumentId, documentId));
      insertItems(documentId, castList(items), beforeItems);
    }
    normalizeSupplyAmountDecision(doc);
    resolveAutomaticTarget(doc);
    doc.setBusinessFingerprint(businessFingerprint(doc));
    documentMapper.updateById(doc);
    if ("receipt".equals(doc.getDocType()) && edits.containsKey("supplierName")) {
      // updateById 默认跳过 null；清空付款单位也必须同步到数据库。
      documentMapper.update(null, Wrappers.<Document>lambdaUpdate()
          .eq(Document::getId, documentId)
          .set(Document::getSupplierName, doc.getSupplierName()));
    }
    if (confirmed) {
      validateForConfirmation(doc);
      if ("supply_order".equals(doc.getDocType())) {
        applySupplyMaterials(doc);
      }
      // 材料层就绪后再锁价（明细可能被改，行重插后 material_id 清空，规则归并会按已登记别名回填）
      if (List.of("delivery_note", "supply_order").contains(doc.getDocType())) {
        materialLayerTrigger.rebuildNow(doc.getBatchId());
      }
      // 编辑前先算旧销售贡献（含运费），重锁后旧锁定记录会被清理，事后再也算不回来
      boolean isDelivery = "delivery_note".equals(doc.getDocType());
      java.math.BigDecimal contributedBefore = isDelivery
          ? ledgerService.deliveryContributedSales(before, beforeItems) : null;
      com.aibot.purchase.service.market.DeliveryNetPriceService.LockResult priceLock = null;
      if (isDelivery) {
        priceLock = deliveryNetPriceService.lockPrices(doc);
      } else if ("supply_order".equals(doc.getDocType())) {
        deliveryNetPriceService.lockSupplyPrices(doc);
      }
      // 送货单：销售额按「旧贡献 → 新贡献」一次性换算（内部逐行重锁已更新锁定值），
      // 不再额外叠加 adjustDeliverySales 差额，避免同一次编辑被算两遍
      ledgerService.correctConfirmedDocument(before, beforeItems, doc, contributedBefore);
      if (isDelivery && priceLock.hasPending()) {
        deliveryNetPriceTrigger.afterCommitAiFallback(doc.getId());
      } else if ("supply_order".equals(doc.getDocType())) {
        // 采购价被改 → 同批次送货单销售价跟着重算（毛利率口径以采购价为成本）
        resyncBatchSales(doc.getBatchId());
      }
      updateSourceMessageCard(doc, cardStatusForConfirmedDocument(doc));
      refreshConfirmedBatchCards(doc);
      appendOperationResult(doc, "updated", "入库数据已修正，相关汇总已同步更新");
      businessQueryService.addLog(userId, doc.getProjectId(), doc.getBatchId(),
          "business_data_updated", referenceTitle(doc) + " 入库数据已修正");
      // 明细可能被改（行重插后 material_id 清空）：事务提交后重建材料层并按材料绑定重锁
      if (List.of("delivery_note", "supply_order").contains(doc.getDocType())) {
        materialLayerTrigger.afterCommitRefresh(doc.getBatchId());
      }
    } else {
      updateSourceMessageCard(doc, "needs_review");
      appendOperationResult(doc, "draft_updated", "识别数据已修改，请继续确认");
    }
    return doc;
  }

  @Transactional
  public Document resolveDuplicate(String userId, String documentId, String decision) {
    Document doc = requireOwnDocument(userId, documentId);
    if (!StringUtils.hasText(doc.getDuplicateOfDocumentId())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "该单据没有重复风险");
    }
    if ("skip".equals(decision)) {
      doc.setDuplicateDecision("skip");
      doc.setStatus("archived");
      documentMapper.updateById(doc);
      updateSourceMessageCard(doc, "duplicate_skipped");
      appendOperationResult(doc, "duplicate_skipped", "已作为重复资料归档");
      return doc;
    }
    if (!"continue".equals(decision)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "无效的重复处理决定");
    }
    doc.setDuplicateDecision("continue");
    doc.setStatus("pending");
    documentMapper.updateById(doc);
    updateSourceMessageCard(doc, "needs_review");
    appendOperationResult(doc, "duplicate_continued", "已保留为新的待确认数据");
    return doc;
  }

  /**
   * 删除单据（软删除，对齐项目/批次的软删模式）：底层明细行/锁定行情映射/附件/聊天消息
   * 物理保留可审计，所有业务查询经 @TableLogic 自动过滤已删单据。
   * 待确认单据直接标记删除；已入库单据在标记删除后回退台账贡献（必须后回退：
   * 批次内采购单重算与批次清空判断都依赖软删过滤排除本单），并同步重算批次/项目快照。
   */
  @Transactional
  public void deleteDocument(String userId, String documentId) {
    Document doc = requireOwnDocument(userId, documentId);
    boolean confirmed = List.of("matched", "archived").contains(doc.getStatus());
    List<DocumentItem> items = getItemsInternal(documentId);
    documentMapper.deleteById(documentId); // @TableLogic → UPDATE deleted = 1
    if ("supply_order".equals(doc.getDocType())) {
      // 采购单确认时按明细生成的材料表数据随单删除（与 applySupplyMaterials 同口径）
      materialMapper.delete(Wrappers.<Material>lambdaQuery()
          .eq(Material::getSourceDocumentId, documentId));
    }
    if (confirmed) {
      ledgerService.removeConfirmedDocument(doc, items);
      if ("supply_order".equals(doc.getDocType())) {
        // 先重建材料层（同步、无 AI）：材料行对已删采购行的引用必须清掉，
        // 后面的重锁读材料捷径时才不会取到已删除行的价格
        materialLayerTrigger.rebuildNow(doc.getBatchId());
        // 采购单删了 → 送货单失去采购价基数，销售价退回「待采购价」并扣减台账销售额
        resyncBatchSales(doc.getBatchId());
      } else if ("delivery_note".equals(doc.getDocType())) {
        materialLayerTrigger.rebuildNow(doc.getBatchId());
      }
      appendOperationResult(doc, "deleted", "入库数据已删除，相关台账与汇总已同步回退");
      businessQueryService.addLog(userId, doc.getProjectId(), doc.getBatchId(),
          "business_data_deleted", referenceTitle(doc) + " 已删除");
    } else {
      appendOperationResult(doc, "deleted", "已删除该条待确认数据");
    }
    updateSourceMessageCard(doc, "deleted");
  }

  public String accessUrl(String userId, String documentId) {
    Document doc = requireOwnDocument(userId, documentId);
    if (!StringUtils.hasText(doc.getFileObjectKey())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "该记录没有原始附件");
    }
    return fileStorage.accessUrl(doc.getFileObjectKey());
  }

  /**
   * 查询批次下的所有单据
   */
  public List<Document> listByBatch(String userId, String batchId, String docType) {
    requireContext(userId, null, batchId);
    return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getUserId, userId)
        .eq(Document::getBatchId, batchId)
        .eq(StringUtils.hasText(docType), Document::getDocType, docType)
        .orderByDesc(Document::getCreatedAt));
  }

  /**
   * 查询项目下的所有单据
   */
  public List<Document> listByProject(String userId, String projectId, String docType, String status) {
    businessQueryService.getProject(userId, projectId);
    // 仅返回项目下有效（未删除）批次的单据，避免已删批次数据从项目维度漏出
    List<Batch> batches = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
        .eq(Batch::getProjectId, projectId));
    if (batches.isEmpty()) {
      return List.of();
    }
    return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getUserId, userId)
        .eq(Document::getProjectId, projectId)
        .in(Document::getBatchId, batches.stream().map(Batch::getId).toList())
        .eq(StringUtils.hasText(docType), Document::getDocType, docType)
        .eq(StringUtils.hasText(status), Document::getStatus, status)
        .orderByDesc(Document::getCreatedAt));
  }

  public Document getById(String userId, String id) {
    return requireOwnDocument(userId, id);
  }

  public List<DocumentItem> getItems(String userId, String documentId) {
    requireOwnDocument(userId, documentId);
    return documentItemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, documentId)
        .orderByAsc(DocumentItem::getSortOrder));
  }

  public List<Map<String, Object>> listBusinessData(
      String userId,
      String projectId,
      String batchId
  ) {
    List<Document> documents;
    if (StringUtils.hasText(batchId)) {
      documents = listByBatch(userId, batchId, null);
    } else if (StringUtils.hasText(projectId)) {
      documents = listByProject(userId, projectId, null, null);
    } else {
      return List.of();
    }
    // 同一批次共用一份口径（避免逐单重复查批次口径）
    java.util.Map<String, com.aibot.purchase.service.market.DeliveryNetPriceService.PricingPolicy>
        policyCache = new java.util.HashMap<>();
    return documents.stream().map(document -> {
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("document", document);
      List<DocumentItem> items = getItemsInternal(document.getId());
      if ("supply_order".equals(document.getDocType())) {
        // 采购单：附带销售价（人工优先，否则按批次口径现算），数据中心可直接展示与修改
        String policyKey = document.getBatchId() == null ? "" : document.getBatchId();
        com.aibot.purchase.service.market.DeliveryNetPriceService.PricingPolicy policy =
            policyCache.computeIfAbsent(policyKey, key -> purchaseSalePriceService.policyFor(document));
        java.util.Map<String, com.aibot.purchase.service.market.PurchaseSalePriceService.Price> loaded =
            purchaseSalePriceService.pricesFor(items, policy);
        final java.util.Map<String, com.aibot.purchase.service.market.PurchaseSalePriceService.Price> prices =
            loaded == null ? java.util.Map.of() : loaded;
        record.put("items", items.stream().map(item -> {
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> row = objectMapper.convertValue(item, java.util.Map.class);
          com.aibot.purchase.service.market.PurchaseSalePriceService.Price price =
              prices.get(item.getId());
          row.put("salesUnitPrice", price == null ? null : price.effective());
          // 占位提示展示「当前生效价」：材料级人工价也算生效价，避免输入框占位与台账销价不一致
          row.put("salesUnitPriceAuto", price == null ? null
              : (price.effective() != null ? price.effective() : price.auto()));
          // 「手动」只认行级人工价（document_items.sales_unit_price）：材料级人工价由材料行管理，
          // 若标记为手动，编辑保存会把材料价固化到行上，之后清掉材料价也不生效（2026-11-06）
          row.put("salesUnitPriceManual", item.getSalesUnitPrice() != null);
          return row;
        }).toList());
      } else if ("delivery_note".equals(document.getDocType())) {
        // 送货单的销售价在锁定记录里（document_item_market_mappings）；
        // 行上 document_items.sales_unit_price 只有人工改过才有值（2026-11-11）
        java.util.Map<String, com.aibot.purchase.entity.DocumentItemMarketMapping> byItem =
            new java.util.HashMap<>();
        for (com.aibot.purchase.entity.DocumentItemMarketMapping mapping
            : itemNetMappingMapper.selectList(
                Wrappers.<com.aibot.purchase.entity.DocumentItemMarketMapping>lambdaQuery()
                    .eq(com.aibot.purchase.entity.DocumentItemMarketMapping::getDocumentId,
                        document.getId()))) {
          byItem.put(mapping.getDocumentItemId(), mapping);
        }
        record.put("items", items.stream().map(item -> {
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> row = objectMapper.convertValue(item, java.util.Map.class);
          com.aibot.purchase.entity.DocumentItemMarketMapping mapping = byItem.get(item.getId());
          BigDecimal locked = mapping == null ? null : mapping.getSalesUnitPrice();
          boolean manual = item.getSalesUnitPrice() != null;
          row.put("salesUnitPrice", manual ? item.getSalesUnitPrice() : locked);
          row.put("salesUnitPriceAuto", locked);
          row.put("salesUnitPriceManual", manual);
          return row;
        }).toList());
      } else {
        record.put("items", items);
      }
      return record;
    }).toList();
  }

  // ---------------------------------------------------------------------------
  // 内部方法
  // ---------------------------------------------------------------------------

  private Document createDocumentFromPayload(String userId, String projectId, String batchId,
                                             AiReply reply, FileStorage.StoredFile stored,
                                             String originalName, String messageId,
                                             String sourceText, String guidanceText,
                                             String fileHash) {
    Map<String, Object> payload = reply.payload();
    String docType = intentToDocType(reply.intent(), payload, reply.content());

    Document doc = new Document();
    doc.setUserId(userId);
    doc.setProjectId(projectId);
    doc.setBatchId(batchId);
    doc.setDocType(docType);
    doc.setDocCode(firstString(payload, "docCode", "invoiceNo"));
    doc.setSupplierName(extractCounterparty(docType, payload));
    if (payload.get("target") != null && doc.getSupplierName() == null) {
      doc.setSupplierName(str(payload.get("target")));
    }
    doc.setAmount(extractAmount(payload, docType));
    doc.setDocDate(extractDate(payload));
    doc.setRemark(firstString(payload, "remark", "purpose"));
    doc.setStatus("pending");
    doc.setConfidence(payload.get("confidence") instanceof Number n ? n.intValue() : null);
    doc.setSource(stored != null ? sourceFromFileName(originalName) : "text");
    doc.setFileName(originalName);
    doc.setFileUrl(stored != null ? stored.url() : null);
    doc.setFileObjectKey(stored != null ? stored.objectKey() : null);
    doc.setSourceText(sourceText);
    doc.setGuidanceText(guidanceText);
    doc.setFileHash(fileHash);
    doc.setAiSummary(reply.content());
    doc.setStructuredData(toJson(payload));
    doc.setSourceMessageId(messageId);
    doc.setCreatedAt(LocalDateTime.now());
    doc.setUpdatedAt(LocalDateTime.now());
    applyPricingPolicy(doc, payload, guidanceText);
    documentMapper.insert(doc);

    // 插入明细行
    Object itemsObj = payload.get("items");
    if (itemsObj instanceof List<?> items && !items.isEmpty()) {
      insertItems(doc.getId(), castList(items));
    }
    // 送货单运费批次默认值（依赖明细重量，须在明细插入后）
    doc.setBusinessFingerprint(businessFingerprint(doc));
    documentMapper.updateById(doc);
    return doc;
  }

  private void insertItems(String documentId, List<Map<String, Object>> items) {
    insertItems(documentId, items, null);
  }

  /**
   * 插入明细行（编辑时整表重建）。fallbackItems = 修改前的行：前端未携带 salesUnitPrice 字段
   * （旧版小程序/卡片）时，按「行序 + 材料 + 规格」沿用原行的人工销售价，
   * 避免改一个无关字段把人工定价清空。
   */
  private void insertItems(String documentId, List<Map<String, Object>> items,
                          List<DocumentItem> fallbackItems) {
    int order = 0;
    for (Map<String, Object> item : items) {
      BigDecimal amount = decimal(item.get("amount"));
      BigDecimal unitPrice = decimal(item.get("unitPrice"));
      BigDecimal legacyQuantity = decimal(item.get("quantity"));
      BigDecimal packageQuantity = firstDecimal(item, "packageQuantity", "pieceCount");
      String packageUnit = firstString(item, "packageUnit", "pieceUnit");
      BigDecimal pricingQuantity = firstDecimal(item, "pricingQuantity", "billingQuantity");
      String pricingUnit = firstString(item, "pricingUnit", "billingUnit");
      BigDecimal totalWeight = decimalOrNull(item.get("totalWeight"));
      String weightUnit = str(item.get("weightUnit"));

      if (pricingQuantity == null) {
        pricingQuantity = totalWeight != null ? totalWeight : legacyQuantity;
      }
      if (!StringUtils.hasText(pricingUnit)) {
        pricingUnit = StringUtils.hasText(weightUnit) ? weightUnit : str(item.get("unit"));
      }

      // 兼容旧协议：当 quantity × unitPrice 与金额明显不符时，quantity 多半是件数。
      if (packageQuantity == null && legacyQuantity.compareTo(BigDecimal.ZERO) > 0
          && amount.compareTo(BigDecimal.ZERO) > 0 && unitPrice.compareTo(BigDecimal.ZERO) > 0
          && !amountMatches(legacyQuantity, unitPrice, amount)) {
        packageQuantity = legacyQuantity;
        packageUnit = StringUtils.hasText(packageUnit) ? packageUnit : "件";
        pricingQuantity = amount.divide(unitPrice, 3, RoundingMode.HALF_UP);
        if (isWeightUnit(pricingUnit) && totalWeight == null) {
          totalWeight = pricingQuantity;
          weightUnit = pricingUnit;
        }
      }

      DocumentItem di = new DocumentItem();
      di.setDocumentId(documentId);
      di.setMaterialName(str(item.get("materialName")));
      di.setSpec(str(item.get("spec")));
      di.setOrigin(str(item.get("origin")));
      di.setPackageQuantity(packageQuantity);
      di.setPackageUnit(packageUnit);
      di.setUnitWeight(decimalOrNull(item.get("unitWeight")));
      di.setTotalWeight(totalWeight);
      di.setWeightUnit(weightUnit);
      di.setPricingQuantity(pricingQuantity);
      di.setPricingUnit(pricingUnit);
      // legacy fields remain the normalized pricing measurement for existing material queries.
      di.setUnit(pricingUnit);
      di.setQuantity(pricingQuantity);
      di.setUnitPrice(unitPrice);
      di.setNetPrice(decimalOrNull(item.get("netPrice")));
      // 人工销售价（采购单）：显式传 null/空 = 清除人工值（恢复按批次口径自动推算）；
      // 未传该字段时沿用原行，避免旧客户端编辑其它字段时丢失人工定价。
      BigDecimal salesUnitPrice = decimalOrNull(item.get("salesUnitPrice"));
      if (!item.containsKey("salesUnitPrice")) {
        salesUnitPrice = inheritedSalesUnitPrice(fallbackItems, order,
            di.getMaterialName(), di.getSpec());
      }
      di.setSalesUnitPrice(salesUnitPrice);
      di.setAmount(amount);
      di.setSortOrder(order++);
      documentItemMapper.insert(di);
    }
  }

  /** 编辑重建明细时沿用原行人工销售价：仅当同一行序的材料与规格都对得上才算同一行。 */
  private static BigDecimal inheritedSalesUnitPrice(List<DocumentItem> fallbackItems, int index,
                                                    String materialName, String spec) {
    if (fallbackItems == null || index < 0 || index >= fallbackItems.size()) {
      return null;
    }
    DocumentItem old = fallbackItems.get(index);
    boolean sameMaterial = java.util.Objects.equals(
        old.getMaterialName() == null ? "" : old.getMaterialName().trim(),
        materialName == null ? "" : materialName.trim());
    boolean sameSpec = java.util.Objects.equals(
        old.getSpec() == null ? "" : old.getSpec().trim(),
        spec == null ? "" : spec.trim());
    return sameMaterial && sameSpec ? old.getSalesUnitPrice() : null;
  }

  /**
   * 解析销售定价方式（送货单）：AI payload 抽取优先，正则兜底解析补充说明/备注。
   * 「网价加X」→ uplift+X；「毛利率X%/X个点」→ margin+X；「按毛利率算」→ margin+默认2.5；
   * 无指令 → 全部留空（确认时按默认 网价+全局上浮）。
   */
  private void applyPricingPolicy(Document doc, Map<String, Object> payload, String guidanceText) {
    // 销售运费（送货单）：AI 抽取「销售运费/客户承担运费」；普通运费字样走 transportFee（structured）
    doc.setSalesFreight(decimalOrNull(payload.get("salesFreight")));
    String mode = str(payload.get("pricingMode"));
    BigDecimal uplift = decimalOrNull(payload.get("upliftAmount"));
    BigDecimal margin = decimalOrNull(payload.get("marginRate"));
    String hint = valueOrEmpty(guidanceText) + " " + valueOrEmpty(doc.getRemark());

    // 正则兜底：AI 漏抽时再从补充说明里解析一次
    if (margin == null && !"margin".equals(mode)) {
      Matcher rateHint = MARGIN_RATE_HINT.matcher(hint);
      if (rateHint.find()) {
        mode = "margin";
        margin = new BigDecimal(rateHint.group(1));
      } else if (MARGIN_DEFAULT_HINT.matcher(hint).find()) {
        mode = "margin";
        margin = DEFAULT_MARGIN_RATE;
      }
    }
    if (uplift == null && !"margin".equals(mode)) {
      Matcher upliftHint = UPLIFT_HINT.matcher(hint);
      if (upliftHint.find()) {
        mode = "uplift";
        uplift = new BigDecimal(upliftHint.group(1));
      }
    }

    if ("margin".equals(mode)) {
      doc.setPricingMode("margin");
      doc.setMarginRate(margin != null ? margin : DEFAULT_MARGIN_RATE);
      doc.setUpliftAmount(null);
    } else if (uplift != null || "uplift".equals(mode)) {
      // 给了上浮方式但金额缺失时（LLM 偶发 pricingMode=uplift、upliftAmount=null），
      // 批次口径优先，其次留空走全局默认（DeliveryNetPriceService）
      BigDecimal effectiveUplift = uplift != null ? uplift
          : batchHintUplift(doc.getBatchId());
      doc.setPricingMode("uplift");
      doc.setUpliftAmount(effectiveUplift);
      doc.setMarginRate(null);
    } else {
      // 第三级兜底：批次默认口径（批次提示词）——payload 与附带说明都没提定价时套用
      Map<String, Object> hints = batchHintService.deliveryNoteHints(
          batchMapper.selectById(doc.getBatchId()));
      BigDecimal hintUplift = BatchHintService.decimal(hints.get(BatchHintService.KEY_UPLIFT_AMOUNT));
      BigDecimal hintMargin = BatchHintService.decimal(hints.get(BatchHintService.KEY_MARGIN_RATE));
      if ("uplift".equals(hints.get(BatchHintService.KEY_PRICING_MODE)) && hintUplift != null) {
        doc.setPricingMode("uplift");
        doc.setUpliftAmount(hintUplift);
        doc.setMarginRate(null);
      } else if ("margin".equals(hints.get(BatchHintService.KEY_PRICING_MODE)) && hintMargin != null) {
        doc.setPricingMode("margin");
        doc.setMarginRate(hintMargin);
        doc.setUpliftAmount(null);
      } else {
        doc.setPricingMode(null);
        doc.setUpliftAmount(null);
        doc.setMarginRate(null);
      }
    }
  }

  /** 批次口径里的上浮金额（仅当批次定价方式为 uplift 时）；无则 null。 */
  private BigDecimal batchHintUplift(String batchId) {
    Map<String, Object> hints = batchHintService.deliveryNoteHints(batchMapper.selectById(batchId));
    return "uplift".equals(hints.get(BatchHintService.KEY_PRICING_MODE))
        ? BatchHintService.decimal(hints.get(BatchHintService.KEY_UPLIFT_AMOUNT))
        : null;
  }

  /** 明细计价重量合计（吨）：totalWeight 换算优先，无重量时用计价数量（重量单位才计）。任一行都没有重量返回 null。 */
  private BigDecimal pricingTons(String documentId) {
    BigDecimal total = BigDecimal.ZERO;
    boolean any = false;
    for (DocumentItem item : getItemsInternal(documentId)) {
      BigDecimal tons = null;
      if (item.getTotalWeight() != null && item.getTotalWeight().signum() > 0) {
        tons = toTons(item.getTotalWeight(), item.getWeightUnit());
      } else if (item.getPricingQuantity() != null && item.getPricingQuantity().signum() > 0
          && isWeightUnit(item.getPricingUnit())) {
        tons = toTons(item.getPricingQuantity(), item.getPricingUnit());
      }
      if (tons != null && tons.signum() > 0) {
        total = total.add(tons);
        any = true;
      }
    }
    return any ? total : null;
  }

  /** 说明文字是否提到运费（附带信息优先：提到就不套用批次运费默认值）。 */
  private static boolean mentionsFreight(String text) {
    return StringUtils.hasText(text) && FREIGHT_MENTION.matcher(text).find();
  }

  /** 重量单位换算成吨；缺省按吨处理（与 DeliveryNetPriceService 同口径）。 */
  private static BigDecimal toTons(BigDecimal weight, String unit) {
    if (!StringUtils.hasText(unit)) {
      return weight;
    }
    String u = unit.trim().toLowerCase();
    if (u.contains("公斤") || u.contains("千克") || u.equals("kg")) {
      return weight.divide(new BigDecimal("1000"));
    }
    if (u.equals("克") || u.equals("g")) {
      return weight.divide(new BigDecimal("1000000"));
    }
    return weight;
  }

  /** 确认卡片上的销售定价说明（送货单）。pricingMode 为空时也给出默认口径。 */
  public String pricingNote(Document document) {
    if ("margin".equals(document.getPricingMode())) {
      BigDecimal rate = document.getMarginRate() != null
          ? document.getMarginRate() : DEFAULT_MARGIN_RATE;
      boolean isDefault = document.getMarginRate() == null;
      String net = plainText(new BigDecimal("100").subtract(rate));
      // 毛利率口径的成本是采购价（同批次采购单的吨价），不再用网价当基数
      return "销售价=采购价÷" + net + "%（" + (isDefault ? "默认毛利率" : "毛利率")
          + plainText(rate) + "%）";
    }
    if ("uplift".equals(document.getPricingMode()) && document.getUpliftAmount() != null) {
      return "销售价=网价+" + plainText(document.getUpliftAmount()) + "元/吨";
    }
    return "销售价=网价+" + plainText(defaultSalesUplift) + "元/吨（默认）";
  }

  private static String plainText(BigDecimal value) {
    return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString();
  }

  private ChatMessage insertChatMessage(String userId, String projectId, String batchId,
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
    msg.setPayload(toJson(payload));
    msg.setCreatedAt(LocalDateTime.now());
    chatMessageMapper.insert(msg);
    return msg;
  }

  private void updateAiMessageCard(ChatMessage aiMessage, Document document) {
    aiMessage.setPayload(toJson("needs_clarification".equals(document.getStatus())
        ? buildClarificationCard(document)
        : buildDocumentCard(document, "needs_review")));
    aiMessage.setStatus("pending");
    chatMessageMapper.updateById(aiMessage);
  }

  /**
   * 待澄清卡片：既保留 AI 的追问与选项，也把已识别到的字段随卡片下发，
   * 使用户可以直接在卡片上补字段确认，而不必重新上传原文件。
   */
  private Map<String, Object> buildClarificationCard(Document document) {
    Map<String, Object> structured = structuredData(document);
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("schemaVersion", "1.0");
    card.put("cardType", "clarification");
    card.put("cardId", document.getId());
    card.put("status", "needs_review");
    card.put("documentId", document.getId());
    card.put("question", hasTextValue(structured.get("question"))
        ? structured.get("question")
        : "这份资料应该按哪种业务类型处理？");
    card.put("options", localizeOptions(structured.get("options")));
    card.put("requestedType", structured.get("requestedType"));
    card.put("detectedType", localizeDocType(structured.get("detectedType")));
    card.put("missingFields", requiredMissingFields(document));
    card.put("document", documentPayload(document));
    card.put("actions", List.of(
        Map.of("id", "edit", "actionKey", "document.edit",
            "label", "直接补充字段并确认", "style", "secondary", "enabled", true)
    ));
    return card;
  }

  /** 追问选项是 AI 给的 intent 名（如 supply_order_card），转中文标签显示，避免卡片直接出现英文。 */
  private Object localizeOptions(Object options) {
    if (!(options instanceof List<?> list)) {
      return options;
    }
    return list.stream().map(item -> {
      String option = String.valueOf(item);
      String docType = option.endsWith("_card")
          ? option.substring(0, option.length() - "_card".length()) : option;
      return isKnownDocType(docType) ? docTypeLabel(docType) : option;
    }).toList();
  }

  /** detectedType 是 docType 值（如 supply_order），显示时转中文；非已知类型原样返回。 */
  private Object localizeDocType(Object value) {
    if (!(value instanceof String docType) || !isKnownDocType(docType)) {
      return value;
    }
    return docTypeLabel(docType);
  }

  /** 追问阶段也要落库一条记录，用于保存原始附件与解析文本，避免补充时要求用户重传。 */
  private Document createClarificationDocument(String userId, String projectId, String batchId,
                                               AiReply reply, FileStorage.StoredFile stored,
                                               String originalName, String messageId,
                                               String sourceText, String guidanceText,
                                               String fileHash) {
    Map<String, Object> payload = reply.payload() == null ? Map.of() : reply.payload();
    String detected = str(payload.get("detectedType"));
    Document doc = new Document();
    doc.setUserId(userId);
    doc.setProjectId(projectId);
    doc.setBatchId(batchId);
    doc.setDocType(isKnownDocType(detected) ? detected : "unknown");
    doc.setDocCode(firstString(payload, "docCode", "invoiceNo"));
    doc.setSupplierName(firstString(payload, "supplierName", "target", "customerName"));
    doc.setAmount(extractAmount(payload, doc.getDocType()));
    doc.setDocDate(extractDate(payload));
    doc.setRemark(firstString(payload, "remark", "purpose"));
    doc.setStatus("needs_clarification");
    doc.setConfidence(payload.get("confidence") instanceof Number n ? n.intValue() : null);
    doc.setSource(stored != null ? sourceFromFileName(originalName) : "text");
    doc.setFileName(originalName);
    doc.setFileUrl(stored != null ? stored.url() : null);
    doc.setFileObjectKey(stored != null ? stored.objectKey() : null);
    doc.setSourceText(sourceText);
    doc.setGuidanceText(guidanceText);
    doc.setFileHash(fileHash);
    doc.setAiSummary(reply.content());
    doc.setStructuredData(toJson(payload));
    doc.setSourceMessageId(messageId);
    doc.setCreatedAt(LocalDateTime.now());
    doc.setUpdatedAt(LocalDateTime.now());
    // 追问场景也解析定价指令（payload 无定价字段，靠补充说明正则兜底）
    applyPricingPolicy(doc, payload, guidanceText);
    documentMapper.insert(doc);
    return doc;
  }

  /** AI 未给出 detectedType 时为 null；Set.contains(null) 不抛异常（List.of 会抛 NPE）。 */
  private static final Set<String> KNOWN_DOC_TYPES = Set.of(
      "supply_order", "delivery_note", "transport_note", "payment_request", "payment",
      "receipt", "purchase_invoice", "sales_invoice", "sales_settlement");

  private static boolean isKnownDocType(String docType) {
    return docType != null && KNOWN_DOC_TYPES.contains(docType);
  }

  private void updateSourceMessageCard(Document document, String status) {
    if (!StringUtils.hasText(document.getSourceMessageId())) return;
    ChatMessage message = chatMessageMapper.selectById(document.getSourceMessageId());
    if (message == null) return;
    if ("deleted".equals(status)) {
      // 删除态卡片：不走 review 卡构建（避免重复卡片等分支覆盖状态），
      // web/小程序按 status=deleted 终态渲染、禁用全部操作
      Map<String, Object> card = new LinkedHashMap<>();
      card.put("schemaVersion", "1.0");
      card.put("cardType", "document_review");
      card.put("cardId", document.getId());
      card.put("status", "deleted");
      card.put("document", documentPayload(document));
      card.put("missingFields", List.of());
      card.put("actions", List.of());
      message.setPayload(toJson(card));
      message.setStatus("archived");
      chatMessageMapper.updateById(message);
      return;
    }
    message.setPayload(toJson("needs_clarification".equals(document.getStatus())
        ? buildClarificationCard(document)
        : buildDocumentCard(document, status)));
    message.setStatus(List.of("applied", "queued", "duplicate_skipped").contains(status)
        ? "archived" : "pending");
    chatMessageMapper.updateById(message);
  }

  private void appendOperationResult(Document document, String operation, String summary) {
    Map<String, Object> reference = new LinkedHashMap<>();
    reference.put("documentId", document.getId());
    reference.put("docType", document.getDocType());
    reference.put("docTypeLabel", docTypeLabel(document.getDocType()));
    reference.put("docCode", valueOrEmpty(document.getDocCode()));
    reference.put("fileName", valueOrEmpty(document.getFileName()));
    reference.put("supplierName", valueOrEmpty(document.getSupplierName()));
    reference.put("docDate", document.getDocDate() == null ? "" : document.getDocDate().toString());
    reference.put("amount", nz(document.getAmount()));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", "1.0");
    payload.put("cardType", "operation_result");
    payload.put("operation", operation);
    payload.put("status", "success");
    payload.put("summary", summary);
    payload.put("reference", reference);

    insertChatMessage(
        document.getUserId(),
        document.getProjectId(),
        document.getBatchId(),
        "AI",
        "CARD",
        "operation_result",
        summary,
        "done",
        payload
    );
  }

  /**
   * 归档批次里最新一条未回答的追问卡片。口径声明与追问无关，用户已转向其他输入；
   * 不归档的话它会一直把后续消息当成「回答追问」传给 AI（实测导致口径声明被反问、落库被跳过）。
   */
  private void archivePendingClarification(String userId, String batchId) {
    ChatMessage pending = chatMessageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
            .eq(ChatMessage::getUserId, userId)
            .eq(ChatMessage::getBatchId, batchId)
            .eq(ChatMessage::getBusinessType, "clarify_card")
            .eq(ChatMessage::getStatus, "pending")
            .orderByDesc(ChatMessage::getCreatedAt)
            .last("LIMIT 1"))
        .stream().findFirst().orElse(null);
    if (pending == null) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>(fromJson(pending.getPayload()));
    payload.put("answered", true);
    pending.setPayload(toJson(payload));
    pending.setStatus("archived");
    chatMessageMapper.updateById(pending);
  }

  private void refreshConfirmedBatchCards(Document trigger) {
    documentMapper.selectList(Wrappers.<Document>lambdaQuery()
            .eq(Document::getUserId, trigger.getUserId())
            .eq(Document::getProjectId, trigger.getProjectId())
            .eq(Document::getBatchId, trigger.getBatchId())
            .eq(Document::getStatus, "matched"))
        .forEach(document ->
            updateSourceMessageCard(document, cardStatusForConfirmedDocument(document)));
  }

  /**
   * 采购侧变化（采购单确认/修正/删除、批次口径修改）后重算同批次送货单的销售价与台账。
   *
   * <p>毛利率口径的销售价 = 采购吨价 ÷ (1−毛利率)，采购价变了销售价就得变；
   * 之前没采购价空着的行，采购单到位后由这里补出来。人工改价行由锁定逻辑自行保留。
   */
  private void resyncBatchSales(String batchId) {
    resyncBatchSales(batchId, false);
  }

  /**
   * 同上，可指定是否按当前口径强制刷新送货单的销售运费。
   * 批次口径本身变了（用户刚声明「销售运费30元/吨」）必须强刷，否则已确认送货单的
   * sales_freight 仍是空/旧值，账上销售金额会少一笔运费（2026-10-28 修）。
   */
  private void resyncBatchSales(String batchId, boolean forceFreight) {
    if (!StringUtils.hasText(batchId)) {
      return;
    }
    deliveryNetPriceService.resyncDeliverySales(batchId, forceFreight);
  }

  /**
   * 项目级口径变更后的重算：本项目下**所有**批次都要按新口径走一遍，
   * 因为「自己没设该字段」的批次读的就是项目口径。单批失败不阻断其它批次。
   */
  private void resyncProjectSales(String projectId) {
    if (!StringUtils.hasText(projectId)) {
      return;
    }
    List<Batch> batches = batchMapper.selectList(
        Wrappers.<Batch>lambdaQuery().eq(Batch::getProjectId, projectId));
    for (Batch batch : batches) {
      try {
        deliveryNetPriceService.resyncDeliverySales(batch.getId(), true);
      } catch (RuntimeException ex) {
        log.warn("项目口径变更后重算批次失败 batchId={}：{}", batch.getId(), ex.getMessage());
      }
    }
    log.info("项目 {} 口径变更：已重算 {} 个批次", projectId, batches.size());
  }

  private static String cardStatusForConfirmedDocument(Document document) {
    return Boolean.TRUE.equals(document.getLedgerApplied()) ? "applied" : "queued";
  }

  private Map<String, Object> buildDocumentCard(Document document, String cardStatus) {
    if (StringUtils.hasText(document.getDuplicateOfDocumentId())
        && (!StringUtils.hasText(document.getDuplicateDecision())
        || "skip".equals(document.getDuplicateDecision()))) {
      return buildDuplicateCard(document);
    }
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("schemaVersion", "1.0");
    card.put("cardType", "document_review");
    card.put("cardId", document.getId());
    card.put("status", cardStatus);
    card.put("document", documentPayload(document));
    List<String> missingFields = requiredMissingFields(document);
    card.put("missingFields", missingFields);
    // 费用关联已改为软建议：不再下发候选列表、不再把卡片置为 needs_match 阻断确认，
    // 关联由后端 resolveAutomaticTarget 自动匹配（单号/金额精确命中、单候选）。
    supplyAmountDiscrepancy(document).ifPresent(discrepancy ->
        card.put("amountDiscrepancy", discrepancy));
    card.put("actions", List.of(
        Map.of(
            "id", "confirm",
            "actionKey", "document.confirm",
            "label", List.of("sales_settlement", "evidence_only").contains(document.getDocType())
                ? "确认并归档" : "确认并写入台账",
            "style", "primary",
            "enabled", !"applied".equals(cardStatus)
                && missingFields.isEmpty()
        ),
        Map.of(
            "id", "edit",
            "actionKey", "document.edit",
            "label", "修改识别字段",
            "style", "secondary",
            "enabled", !"applied".equals(cardStatus)
        )
    ));
    return card;
  }

  private Map<String, Object> buildDuplicateCard(Document document) {
    Document existing = documentMapper.selectById(document.getDuplicateOfDocumentId());
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("schemaVersion", "1.0");
    card.put("cardType", "duplicate_review");
    card.put("cardId", document.getId());
    boolean skipped = "skip".equals(document.getDuplicateDecision());
    card.put("status", skipped ? "duplicate_skipped" : "needs_review");
    card.put("reason", document.getDuplicateReason());
    card.put("document", documentPayload(document));
    if (existing != null) {
      card.put("existingDocument", Map.of(
          "id", existing.getId(),
          "docTypeLabel", docTypeLabel(existing.getDocType()),
          "docCode", valueOrEmpty(existing.getDocCode()),
          "fileName", valueOrEmpty(existing.getFileName()),
          "supplierName", valueOrEmpty(existing.getSupplierName()),
          "docDate", existing.getDocDate() == null ? "" : existing.getDocDate().toString(),
          "amount", nz(existing.getAmount())
      ));
    }
    card.put("actions", List.of(
        Map.of("id", "skip", "actionKey", "duplicate.skip",
            "label", skipped ? "已作为重复归档" : "视为重复，不再导入",
            "style", "secondary", "enabled", !skipped),
        Map.of("id", "continue", "actionKey", "duplicate.continue", "label", "仍然作为新单据导入",
            "style", "primary", "enabled", !skipped)
    ));
    return card;
  }

  private Map<String, Object> documentPayload(Document document) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", document.getId());
    payload.put("docType", document.getDocType());
    payload.put("docTypeLabel", docTypeLabel(document.getDocType()));
    payload.put("docCode", document.getDocCode());
    payload.put("supplierName", document.getSupplierName());
    payload.put("docDate", document.getDocDate());
    payload.put("amount", document.getAmount());
    payload.put("confidence", document.getConfidence());
    payload.put("fileName", document.getFileName());
    payload.put("source", document.getSource());
    payload.put("guidanceText", valueOrEmpty(document.getGuidanceText()));
    payload.put("remark", valueOrEmpty(document.getRemark()));
    payload.put("targetLedgerId", valueOrEmpty(document.getTargetLedgerId()));
    payload.put("targetDocumentId", valueOrEmpty(document.getTargetDocumentId()));
    Map<String, Object> structured = structuredData(document);
    payload.put("processingMode", structured.get("processingMode"));
    payload.put("detectedDocType", structured.get("detectedSourceType"));
    payload.put("detectedDocTypeLabel", detectedDocTypeLabel(structured.get("detectedSourceType")));
    payload.put("requestedDocType", structured.get("requestedDocType"));
    payload.put("typeOverride", Boolean.TRUE.equals(structured.get("typeOverride")));
    payload.put("requiredFields", requiredFieldsFor(document.getDocType()));
    if (List.of(
        "payment_request", "payment", "purchase_invoice", "sales_invoice",
        "invoice_unknown", "sales_settlement", "settlement_unknown").contains(document.getDocType())) {
      payload.put("expenseCategory", structured.get("expenseCategory"));
      payload.put("invoiceDirection", structured.get("invoiceDirection"));
      payload.put("netAmount", structured.get("netAmount"));
      payload.put("taxAmount", structured.get("taxAmount"));
      payload.put("requiredReceiptDate", structured.get("requiredReceiptDate"));
      payload.put("sellerName", valueOrEmpty(str(structured.get("sellerName"))));
      payload.put("buyerName", valueOrEmpty(str(structured.get("buyerName"))));
      if (List.of("invoice_unknown", "invoice").contains(document.getDocType())) {
        payload.put("invoiceDirection", structured.get("invoiceDirection"));
      }
      if (List.of("purchase_invoice", "sales_invoice", "invoice_unknown").contains(document.getDocType())) {
        payload.put("taxRate", structured.get("taxRate"));
      }
    }
    if ("delivery_note".equals(document.getDocType())) {
      payload.put("customerName", valueOrEmpty(str(structured.get("customerName"))));
      // 采购运费（原运输费用，进台账运输费用行）；销售运费（进台账销售总金额）
      payload.put("transportFee", structured.get("transportFee"));
      payload.put("carrierName", valueOrEmpty(str(structured.get("carrierName"))));
      payload.put("salesFreight", document.getSalesFreight());
      // 销售定价说明（2026-09-21）：卡片/小程序按 document.pricingNote 展示当前计价方式
      payload.put("pricingMode", document.getPricingMode());
      payload.put("upliftAmount", document.getUpliftAmount());
      payload.put("marginRate", document.getMarginRate());
      payload.put("pricingNote", pricingNote(document));
      payload.put("vehicleNo", valueOrEmpty(str(structured.get("vehicleNo"))));
    }
    if ("supply_order".equals(document.getDocType())) {
      payload.put("vehicleNo", valueOrEmpty(str(structured.get("vehicleNo"))));
    }
    List<DocumentItem> documentItems = getItemsInternal(document.getId());
    if ("supply_order".equals(document.getDocType())) {
      // 采购单：销售价随明细下发（人工值优先，否则按批次口径现算），卡片/数据中心直接展示与修改
      com.aibot.purchase.service.market.DeliveryNetPriceService.PricingPolicy policy =
          purchaseSalePriceService.policyFor(document);
      java.util.Map<String, com.aibot.purchase.service.market.PurchaseSalePriceService.Price> loaded =
          purchaseSalePriceService.pricesFor(documentItems, policy);
      final java.util.Map<String, com.aibot.purchase.service.market.PurchaseSalePriceService.Price> prices =
          loaded == null ? java.util.Map.of() : loaded;
      payload.put("items", documentItems.stream()
          .map(item -> documentItemPayload(item, prices.get(item.getId()))).toList());
    } else {
      payload.put("items", documentItems.stream()
          .map(item -> documentItemPayload(item, null)).toList());
    }
    return payload;
  }

  private Map<String, Object> documentItemPayload(DocumentItem item) {
    return documentItemPayload(item, null);
  }

  private Map<String, Object> documentItemPayload(
      DocumentItem item,
      com.aibot.purchase.service.market.PurchaseSalePriceService.Price price
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", item.getId());
    payload.put("materialName", valueOrEmpty(item.getMaterialName()));
    payload.put("spec", valueOrEmpty(item.getSpec()));
    payload.put("origin", valueOrEmpty(item.getOrigin()));
    // 数量/单价类字段缺失时下发 null（前端显示为空），不要下发 0 造成「被填成 0」的误解。
    payload.put("packageQuantity", item.getPackageQuantity());
    payload.put("packageUnit", valueOrEmpty(item.getPackageUnit()));
    payload.put("unitWeight", item.getUnitWeight());
    payload.put("totalWeight", item.getTotalWeight());
    payload.put("weightUnit", valueOrEmpty(item.getWeightUnit()));
    payload.put("pricingQuantity", item.getPricingQuantity());
    payload.put("pricingUnit", valueOrEmpty(item.getPricingUnit()));
    payload.put("unit", valueOrEmpty(item.getUnit()));
    payload.put("quantity", item.getQuantity());
    payload.put("unitPrice", item.getUnitPrice());
    payload.put("netPrice", item.getNetPrice());
    if (price != null) {
      // 采购单销售价：effective=最终取用值（材料人工价/行人工价/自动），auto=占位提示用的生效价，
      // manual=行级是否存在人工值（材料级人工价不算，避免编辑保存后固化到行上）
      payload.put("salesUnitPrice", price.effective());
      payload.put("salesUnitPriceAuto", price.effective() != null ? price.effective() : price.auto());
      payload.put("salesUnitPriceManual", item.getSalesUnitPrice() != null);
    }
    payload.put("amount", nz(item.getAmount()));
    return payload;
  }

  private void validateForConfirmation(Document document) {
    if (!StringUtils.hasText(document.getDocType()) || "unknown".equals(document.getDocType())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请先确认单据类型");
    }
    if ("evidence_only".equals(document.getDocType())) {
      return;
    }
    if (isAmountMissing(document)) {
      throw new ApiException(HttpStatus.BAD_REQUEST,
          "delivery_note".equals(document.getDocType())
              ? "请先补充送货单销售金额（无销售金额可填 0）"
              : "请先补充有效的单据金额");
    }
    if (document.getDocDate() == null && !"transport_note".equals(document.getDocType())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请先补充单据日期");
    }
    if ("supply_order".equals(document.getDocType()) && getItemsInternal(document.getId()).isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "采购单缺少材料明细，无法建立采购台账");
    }
    // 采购单不强制每行都有计价数量：数量/单价/金额缺失不阻塞确认，缺失即留空（按 0 计入台账金额）。
    // 一个批次可导入多张采购单/送货单（2026-09-21 起），原「批次=一张采购单」强约束已废除。
    if ("delivery_note".equals(document.getDocType())) {
      List<DocumentItem> items = getItemsInternal(document.getId());
      boolean hasDeliveredQuantity = items.stream().anyMatch(item ->
          nz(item.getPackageQuantity()).compareTo(BigDecimal.ZERO) > 0
              || nz(item.getTotalWeight()).compareTo(BigDecimal.ZERO) > 0
              || nz(item.getPricingQuantity()).compareTo(BigDecimal.ZERO) > 0);
      if (!hasDeliveredQuantity && nz(document.getAmount()).compareTo(BigDecimal.ZERO) <= 0) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "送货单缺少有效的发货数量、重量或金额");
      }
    }
    if ("transport_note".equals(document.getDocType())
        && !StringUtils.hasText(document.getSupplierName())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "运输费用缺少承运单位");
    }
    if ("payment".equals(document.getDocType())
        && !StringUtils.hasText(document.getSupplierName())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "付款记录缺少收款单位");
    }
    List<String> overrideMissingFields = typeOverrideMissingFields(document);
    if (!overrideMissingFields.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST,
          "请先补充转换后的业务字段：" + String.join("、", overrideMissingFields.stream()
              .map(DocumentService::fieldLabel)
              .toList()));
    }
    if ("invoice_unknown".equals(document.getDocType())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请先确认这张发票是进项发票还是销项发票");
    }
    if ("settlement_unknown".equals(document.getDocType())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "请先确认结算单的业务归属");
    }
    if (List.of("purchase_invoice", "sales_invoice").contains(document.getDocType())
        && !hasInvoiceEvidence(document)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "缺少发票关键字段，请补充发票信息或明确说明这是发票");
    }
    // 费用关联（请款/付款/进项发票 → 采购单/运输单/带运费送货单）是软建议：自动匹配照旧，
    // 不再强制选择，也不因额度耗尽隐藏候选——关联只作参考信息，台账金额按「类别+批次」归集。
    if ("supply_order".equals(document.getDocType())
        && supplyAmountDiscrepancy(document).isPresent()
        && !hasTextValue(structuredData(document).get("amountMismatchDecision"))) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "单据总额与材料明细合计不一致，请先确认差异处理方式");
    }
  }

  private void mergeFinancialFields(Document document, Map<String, Object> edits) {
    if (!List.of(
        "payment_request", "payment", "purchase_invoice", "sales_invoice",
        "invoice_unknown", "sales_settlement", "settlement_unknown").contains(document.getDocType())) {
      return;
    }
    Map<String, Object> structured = new LinkedHashMap<>(structuredData(document));
    for (String field : List.of(
        "expenseCategory", "taxRate", "invoiceDirection", "netAmount", "taxAmount",
        "requiredReceiptDate", "settlementDirection", "relatedDocCodes")) {
      if (List.of("payment", "payment_request").contains(document.getDocType())
          && List.of("taxRate", "invoiceDirection", "netAmount", "taxAmount").contains(field)) {
        continue;
      }
      if (edits.containsKey(field)) {
        Object value = edits.get(field);
        structured.put(field, value == null || String.valueOf(value).isBlank() ? null : value);
      }
    }
    String category = str(structured.get("expenseCategory"));
    if (!"material".equals(category) && !"transport".equals(category)) {
      category = looksLikeTransport(document, structured) ? "transport" : "material";
      structured.put("expenseCategory", category);
    }
    if (List.of("purchase_invoice", "sales_invoice", "invoice_unknown").contains(document.getDocType())) {
      structured.put("docDate", document.getDocDate() == null ? null : document.getDocDate().toString());
      structured.put("invoiceDate", document.getDocDate() == null ? null : document.getDocDate().toString());
      structured.put("amount", nz(document.getAmount()));
      structured.put("invoiceAmount", nz(document.getAmount()));
      if (!hasTextValue(structured.get("taxRate"))) {
        structured.put("taxRate", "transport".equals(category) ? "9%" : "13%");
      }
      structured.put("invoiceDirection", switch (document.getDocType()) {
        case "purchase_invoice" -> "purchase";
        case "sales_invoice" -> "sales";
        default -> "unknown";
      });
    }
    document.setStructuredData(toJson(structured));
  }

  /**
   * 送货单人工补充字段：承运单位（carrierName）与运输费用（transportFee）写入 structuredData。
   * 两者均选填：运输单上的承运单位/金额才是必填口径；送货单只提供人工补录入口。
   * transportFee 是台账运费归集的唯一权威字段，编辑时清掉 AI 兜底的 freight，避免双取。
   */
  private void mergeDeliveryNoteFields(Document document, Map<String, Object> edits) {
    if (!"delivery_note".equals(document.getDocType())) {
      return;
    }
    Map<String, Object> structured = new LinkedHashMap<>(structuredData(document));
    if (edits.containsKey("carrierName")) {
      Object value = edits.get("carrierName");
      structured.put("carrierName",
          value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim());
    }
    if (edits.containsKey("transportFee")) {
      Object value = edits.get("transportFee");
      if (value == null || String.valueOf(value).isBlank()) {
        structured.put("transportFee", null);
        structured.put("freight", null);
      } else {
        structured.put("transportFee", value);
        structured.put("freight", null);
      }
    }
    // 销售运费存实体列：进台账销售总金额（锁定货款+销售运费），编辑后经差额重算同步
    if (edits.containsKey("salesFreight")) {
      Object value = edits.get("salesFreight");
      document.setSalesFreight(value == null || String.valueOf(value).isBlank()
          ? null : decimal(value));
    }
    document.setStructuredData(toJson(structured));
  }

  private List<String> requiredMissingFields(Document document) {
    List<String> missing = new ArrayList<>();
    if ("evidence_only".equals(document.getDocType())) {
      return missing;
    }
    if (document.getDocDate() == null && !"transport_note".equals(document.getDocType())) {
      missing.add("docDate");
    }
    // 送货单（销售侧发货单）允许销售金额填 0（无价送货单，金额以明细合计参与台账）；其余类型要求正数。
    if (isAmountMissing(document)) {
      missing.add("amount");
    }
    if ("invoice_unknown".equals(document.getDocType())) {
      missing.add("invoiceDirection");
    }
    if ("settlement_unknown".equals(document.getDocType())) {
      missing.add("settlementDirection");
    }
    if ("payment".equals(document.getDocType())
        && !StringUtils.hasText(document.getSupplierName())) {
      missing.add("supplierName");
    }
    typeOverrideMissingFields(document).stream()
        .filter(field -> !missing.contains(field))
        .forEach(missing::add);
    if (List.of("purchase_invoice", "sales_invoice").contains(document.getDocType())
        && !hasInvoiceEvidence(document)) {
      missing.add("invoiceEvidence");
    }
    if ("supply_order".equals(document.getDocType())
        && supplyAmountDiscrepancy(document).isPresent()
        && !hasTextValue(structuredData(document).get("amountMismatchDecision"))) {
      missing.add("amountMismatchDecision");
    }
    return missing;
  }

  /**
   * 金额是否算缺失。送货单（销售侧发货单）允许销售金额填 0（表示无价送货单）；
   * 其他类型仍要求正数。
   */
  private static boolean isAmountMissing(Document document) {
    if ("delivery_note".equals(document.getDocType())) {
      return document.getAmount() == null;
    }
    return nz(document.getAmount()).compareTo(BigDecimal.ZERO) <= 0;
  }

  private boolean looksLikeTransport(Document document, Map<String, Object> structured) {
    String text = String.join(" ",
        valueOrEmpty(document.getSupplierName()),
        valueOrEmpty(document.getRemark()),
        valueOrEmpty(document.getGuidanceText()),
        structured.values().toString());
    return text.matches(".*(运输|运费|物流|承运|车费).*");
  }

  /** 供外部服务（如台账导出）读取单据的结构化字段，只读。 */
  public Map<String, Object> structuredDataOf(Document document) {
    return structuredData(document);
  }

  private Map<String, Object> structuredData(Document document) {
    if (!StringUtils.hasText(document.getStructuredData())) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(document.getStructuredData(), new TypeReference<>() {
      });
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private void mergeReviewFields(Document document, Map<String, Object> edits) {
    Map<String, Object> structured = new LinkedHashMap<>(structuredData(document));
    if (edits.containsKey("amountMismatchDecision")) {
      String decision = str(edits.get("amountMismatchDecision"));
      if (!"sync_items".equals(decision) && !"keep_source".equals(decision)) {
        structured.remove("amountMismatchDecision");
      } else {
        structured.put("amountMismatchDecision", decision);
      }
    }
    List<String> overrideMissing = typeOverrideMissingFields(structured);
    overrideMissing.removeIf(field -> switch (field) {
      case "docDate" -> edits.containsKey("docDate") && document.getDocDate() != null;
      case "supplierName" -> edits.containsKey("supplierName")
          && StringUtils.hasText(document.getSupplierName());
      case "amount" -> edits.containsKey("amount")
          && nz(document.getAmount()).compareTo(BigDecimal.ZERO) > 0;
      default -> false;
    });
    if (Boolean.TRUE.equals(structured.get("typeOverride"))) {
      structured.put("targetMissingFields", overrideMissing);
    }
    document.setStructuredData(toJson(structured));
  }

  private List<String> typeOverrideMissingFields(Document document) {
    return typeOverrideMissingFields(structuredData(document));
  }

  private static List<String> typeOverrideMissingFields(Map<String, Object> structured) {
    if (!Boolean.TRUE.equals(structured.get("typeOverride"))) {
      return new ArrayList<>();
    }
    Object value = structured.get("targetMissingFields");
    if (!(value instanceof List<?> fields)) {
      return new ArrayList<>();
    }
    return fields.stream()
        .filter(java.util.Objects::nonNull)
        .map(String::valueOf)
        .filter(field -> List.of("docDate", "supplierName", "amount").contains(field))
        .distinct()
        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
  }

  /**
   * 各业务类型的必填字段清单，供前端渲染必填标志。
   * 必须与 {@link #validateForConfirmation(Document)} 的校验规则保持一致。
   */
  private static List<String> requiredFieldsFor(String docType) {
    if (!StringUtils.hasText(docType) || "unknown".equals(docType)
        || "evidence_only".equals(docType)) {
      return List.of();
    }
    List<String> fields = new ArrayList<>();
    if (!"transport_note".equals(docType)) {
      fields.add("docDate");
    }
    fields.add("amount");
    if (List.of("payment", "transport_note").contains(docType)) {
      fields.add("supplierName");
    }
    if ("supply_order".equals(docType)) {
      fields.add("items");
    }
    if ("invoice_unknown".equals(docType)) {
      fields.add("invoiceDirection");
    }
    if ("settlement_unknown".equals(docType)) {
      fields.add("settlementDirection");
    }
    if (List.of("purchase_invoice", "sales_invoice").contains(docType)) {
      fields.add("invoiceEvidence");
    }
    return List.copyOf(fields);
  }

  private static String fieldLabel(String field) {
    return switch (field) {
      case "docDate" -> "日期";
      case "supplierName" -> "往来单位";
      case "amount" -> "金额";
      case "items" -> "材料明细";
      case "invoiceEvidence" -> "发票关键字段";
      case "invoiceDirection" -> "发票方向";
      case "settlementDirection" -> "结算归属";
      case "amountMismatchDecision" -> "金额差异处理方式";
      default -> field;
    };
  }

  private void normalizeSupplyAmountDecision(Document document) {
    if (!"supply_order".equals(document.getDocType())) {
      return;
    }
    Map<String, Object> structured = new LinkedHashMap<>(structuredData(document));
    String decision = str(structured.get("amountMismatchDecision"));
    BigDecimal itemTotal = itemAmountTotal(document.getId());
    if ("sync_items".equals(decision)) {
      document.setAmount(itemTotal);
    }
    if (document.getAmount() != null
        && document.getAmount().subtract(itemTotal).abs().compareTo(new BigDecimal("0.01")) <= 0) {
      structured.remove("amountMismatchDecision");
    }
    document.setStructuredData(toJson(structured));
  }

  private Optional<Map<String, Object>> supplyAmountDiscrepancy(Document document) {
    if (!"supply_order".equals(document.getDocType())) {
      return Optional.empty();
    }
    List<DocumentItem> items = getItemsInternal(document.getId());
    if (items.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal itemTotal = items.stream()
        .map(DocumentItem::getAmount)
        .map(DocumentService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sourceAmount = nz(document.getAmount());
    BigDecimal difference = sourceAmount.subtract(itemTotal);
    if (difference.abs().compareTo(new BigDecimal("0.01")) <= 0) {
      return Optional.empty();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("sourceAmount", sourceAmount);
    result.put("itemTotal", itemTotal);
    result.put("difference", difference);
    result.put("decision", valueOrEmpty(str(structuredData(document).get("amountMismatchDecision"))));
    return Optional.of(result);
  }

  private BigDecimal itemAmountTotal(String documentId) {
    return getItemsInternal(documentId).stream()
        .map(DocumentItem::getAmount)
        .map(DocumentService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private boolean hasInvoiceEvidence(Document document) {
    if ((StringUtils.hasText(document.getGuidanceText())
        && document.getGuidanceText().contains("发票"))
        || (StringUtils.hasText(document.getSourceText())
        && document.getSourceText().contains("发票"))) {
      return true;
    }
    Map<String, Object> structured = structuredData(document);
    boolean hasParties = hasTextValue(structured.get("sellerName"))
        && hasTextValue(structured.get("buyerName"));
    boolean hasFinancialFields = hasTextValue(structured.get("taxAmount"))
        || hasTextValue(structured.get("taxRate"))
        || hasTextValue(structured.get("netAmount"));
    return StringUtils.hasText(document.getDocCode())
        && document.getDocDate() != null
        && hasParties
        && hasFinancialFields
        && nz(document.getAmount()).compareTo(BigDecimal.ZERO) > 0;
  }

  private void resolveAutomaticTarget(Document document) {
    if (!requiresExpenseTarget(document) || StringUtils.hasText(document.getTargetDocumentId())) {
      return;
    }
    List<Document> candidates = expenseCandidates(document);
    if (candidates.isEmpty()) {
      return;
    }
    List<String> relatedCodes = relatedDocumentCodes(document);
    List<Document> codeMatches = candidates.stream()
        .filter(candidate -> relatedCodes.stream()
            .anyMatch(code -> sameReference(code, candidate.getDocCode())))
        .toList();
    Document selected = codeMatches.size() == 1 ? codeMatches.get(0) : null;

    if (selected == null) {
      List<Document> exactMatches = candidates.stream()
          .filter(candidate -> sameParty(candidate.getSupplierName(), document.getSupplierName()))
          .filter(candidate -> remainingAmount(
              candidate, document.getDocType(), document.getId())
              .subtract(nz(document.getAmount())).abs()
              .compareTo(new BigDecimal("0.01")) <= 0)
          .toList();
      if (exactMatches.size() == 1) {
        selected = exactMatches.get(0);
      }
    }
    if (selected == null && candidates.size() == 1) {
      selected = candidates.get(0);
    }
    if (selected != null) {
      document.setTargetDocumentId(selected.getId());
      document.setTargetLedgerId(resolveTargetLedgerId(selected));
      documentMapper.updateById(document);
    }
  }

  private List<Document> expenseCandidates(Document document) {
    if (!requiresExpenseTarget(document)) {
      return List.of();
    }
    String category = str(structuredData(document).get("expenseCategory"));
    // 运输类别的付款/发票可以关联到运输单，也可以关联到带运费的送货单（送货单上的 transportFee）
    List<String> sourceTypes = "transport".equals(category)
        ? List.of("transport_note", "delivery_note")
        : List.of("supply_order");
    return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
            .eq(Document::getUserId, document.getUserId())
            .eq(Document::getProjectId, document.getProjectId())
            .eq(Document::getBatchId, document.getBatchId())
            .in(Document::getDocType, sourceTypes)
            .in(Document::getStatus, "matched", "archived")
            .orderByAsc(Document::getCreatedAt))
        .stream()
        .filter(candidate -> !"skip".equals(candidate.getDuplicateDecision()))
        // 送货单只有标注过运输费用（transportFee）才构成一笔可关联的运费单据；amount 是销售额，不参与
        .filter(candidate -> !"delivery_note".equals(candidate.getDocType())
            || deliveryFreightAmount(candidate).compareTo(BigDecimal.ZERO) > 0)
        .toList();
    // 注意：不再按剩余额度过滤候选——额度耗尽的单据仍列出（软关联），避免第二笔请款时候选凭空消失
  }

  private BigDecimal remainingAmount(
      Document target,
      String contributionType,
      String currentDocumentId
  ) {
    BigDecimal used = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
            .eq(Document::getUserId, target.getUserId())
            .eq(Document::getBatchId, target.getBatchId())
            .eq(Document::getTargetDocumentId, target.getId())
            .eq(Document::getDocType, contributionType)
            .ne(StringUtils.hasText(currentDocumentId), Document::getId, currentDocumentId)
            .in(Document::getStatus, "pending", "matched", "archived"))
        .stream()
        .filter(item -> !"skip".equals(item.getDuplicateDecision()))
        .map(Document::getAmount)
        .map(DocumentService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return expenseBaseAmount(target).subtract(used).max(BigDecimal.ZERO);
  }

  private BigDecimal expenseBaseAmount(Document document) {
    if ("supply_order".equals(document.getDocType())) {
      BigDecimal itemTotal = itemAmountTotal(document.getId());
      if (itemTotal.compareTo(BigDecimal.ZERO) > 0) {
        return itemTotal;
      }
    }
    // 送货单作为运费单据被关联时，可关联额度 = 单据上标注的运输费用（不是销售额）
    if ("delivery_note".equals(document.getDocType())) {
      return deliveryFreightAmount(document);
    }
    return nz(document.getAmount());
  }

  /** 送货单上标注为运输费用的金额（structuredData.transportFee/freight）；amount 字段是销售额。 */
  private BigDecimal deliveryFreightAmount(Document document) {
    Map<String, Object> structured = structuredData(document);
    Object fee = structured.get("transportFee");
    if (fee == null) {
      fee = structured.get("freight");
    }
    if (fee == null) {
      return BigDecimal.ZERO;
    }
    if (fee instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue());
    }
    try {
      return new BigDecimal(String.valueOf(fee).replaceAll("[^\\d.-]", ""));
    } catch (Exception ex) {
      return BigDecimal.ZERO;
    }
  }

  private boolean requiresExpenseTarget(Document document) {
    return List.of("payment_request", "payment", "purchase_invoice")
        .contains(document.getDocType());
  }

  private List<String> relatedDocumentCodes(Document document) {
    Map<String, Object> structured = structuredData(document);
    List<String> result = new ArrayList<>();
    Object codes = structured.get("relatedDocCodes");
    if (codes instanceof List<?> list) {
      list.stream().filter(java.util.Objects::nonNull)
          .map(String::valueOf).filter(StringUtils::hasText).forEach(result::add);
    } else if (codes != null && StringUtils.hasText(String.valueOf(codes))) {
      result.add(String.valueOf(codes));
    }
    for (String field : List.of(
        "relatedDocumentNo", "transportNo", "purchaseOrderNo", "supplyOrderNo", "contractNo")) {
      if (hasTextValue(structured.get(field))) {
        result.add(String.valueOf(structured.get(field)));
      }
    }
    return result;
  }

  private String resolveTargetLedgerId(Document target) {
    if ("transport_note".equals(target.getDocType())) {
      return target.getTargetLedgerId();
    }
    return null;
  }

  private static boolean sameReference(String left, String right) {
    if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) return false;
    return normalize(left).equals(normalize(right));
  }

  private static boolean sameParty(String left, String right) {
    if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) return false;
    return normalize(left).equals(normalize(right));
  }

  private static boolean hasTextValue(Object value) {
    return value != null
        && StringUtils.hasText(String.valueOf(value))
        && !"null".equalsIgnoreCase(String.valueOf(value));
  }

  private void applySupplyMaterials(Document document) {
    materialMapper.delete(Wrappers.<Material>lambdaQuery()
        .eq(Material::getSourceDocumentId, document.getId()));
    for (DocumentItem item : getItemsInternal(document.getId())) {
      Material material = new Material();
      material.setBatchId(document.getBatchId());
      material.setSourceDocumentId(document.getId());
      material.setName(item.getMaterialName());
      material.setSpec(StringUtils.hasText(item.getSpec()) ? item.getSpec() : "未标注");
      String pricingUnit = StringUtils.hasText(item.getPricingUnit()) ? item.getPricingUnit() : item.getUnit();
      BigDecimal pricingQuantity = item.getPricingQuantity() != null ? item.getPricingQuantity() : item.getQuantity();
      material.setUnit(StringUtils.hasText(pricingUnit) ? pricingUnit : "项");
      material.setQuantity(nz(pricingQuantity));
      material.setBudgetUnitPrice(item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice());
      material.setSelectedSupplier(document.getSupplierName());
      material.setSelectedQuote(item.getUnitPrice());
      materialMapper.insert(material);
    }
  }

  private Document copyForCorrection(Document source) {
    Document copy = new Document();
    copy.setId(source.getId());
    copy.setUserId(source.getUserId());
    copy.setProjectId(source.getProjectId());
    copy.setBatchId(source.getBatchId());
    copy.setDocCode(source.getDocCode());
    copy.setDocType(source.getDocType());
    copy.setSupplierName(source.getSupplierName());
    copy.setAmount(source.getAmount());
    copy.setSalesFreight(source.getSalesFreight());
    copy.setDocDate(source.getDocDate());
    copy.setStatus(source.getStatus());
    copy.setStructuredData(source.getStructuredData());
    copy.setTargetLedgerId(source.getTargetLedgerId());
    copy.setTargetDocumentId(source.getTargetDocumentId());
    copy.setLedgerApplied(source.getLedgerApplied());
    return copy;
  }

  private String referenceTitle(Document document) {
    String identity = StringUtils.hasText(document.getDocCode())
        ? document.getDocCode()
        : StringUtils.hasText(document.getFileName()) ? document.getFileName() : document.getId();
    return docTypeLabel(document.getDocType()) + " " + identity;
  }

  private String confirmationSummary(Document document) {
    if (List.of("sales_settlement", "evidence_only").contains(document.getDocType())) {
      return "已确认归档，不影响收付款、发票和销售金额";
    }
    return "已确认并写入当前批次";
  }

  private List<DocumentItem> getItemsInternal(String documentId) {
    return documentItemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, documentId)
        .orderByAsc(DocumentItem::getSortOrder));
  }

  private void requireContext(String userId, String projectId, String batchId) {
    var batch = requireBatchContext(userId, projectId, batchId);
  }

  public BatchDto requireBatchContext(String userId, String projectId, String batchId) {
    var batch = businessQueryService.getBatch(userId, batchId);
    if (StringUtils.hasText(projectId) && !projectId.equals(batch.projectId())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "项目与批次不匹配");
    }
    return batch;
  }

  public ProjectDto requireProjectContext(String userId, String projectId) {
    return businessQueryService.getProject(userId, projectId);
  }

  private Document requireOwnDocument(String userId, String documentId) {
    Document doc = documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
        .eq(Document::getId, documentId)
        .eq(Document::getUserId, userId));
    if (doc == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "单据不存在");
    }
    return doc;
  }

  private void detectDuplicate(Document document) {
    List<Document> existing = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getUserId, document.getUserId())
        .eq(Document::getProjectId, document.getProjectId())
        .eq(Document::getBatchId, document.getBatchId())
        .ne(Document::getId, document.getId())
        .orderByDesc(Document::getCreatedAt));
    Document duplicate = null;
    String reason = null;
    if (StringUtils.hasText(document.getFileHash())) {
      duplicate = existing.stream()
          .filter(item -> document.getFileHash().equals(item.getFileHash()))
          .findFirst().orElse(null);
      if (duplicate != null) reason = "same_hash";
    }
    if (duplicate == null && StringUtils.hasText(document.getBusinessFingerprint())) {
      duplicate = existing.stream()
          .filter(item -> document.getBusinessFingerprint().equals(item.getBusinessFingerprint()))
          .findFirst().orElse(null);
      if (duplicate != null) reason = "same_business";
    }
    if (duplicate == null && StringUtils.hasText(document.getFileName())) {
      duplicate = existing.stream()
          .filter(item -> document.getFileName().equalsIgnoreCase(item.getFileName()))
          .findFirst().orElse(null);
      if (duplicate != null) reason = "same_filename";
    }
    if (duplicate != null) {
      document.setDuplicateOfDocumentId(duplicate.getId());
      document.setDuplicateReason(reason);
      documentMapper.updateById(document);
    }
  }

  private String businessFingerprint(Document document) {
    StringBuilder canonical = new StringBuilder()
        .append(valueOrEmpty(document.getDocType())).append('|')
        .append(normalize(document.getSupplierName())).append('|')
        .append(nz(document.getAmount()).stripTrailingZeros().toPlainString());
    for (DocumentItem item : getItemsInternal(document.getId())) {
      canonical.append('|').append(normalize(item.getMaterialName()))
          .append(':').append(normalize(item.getSpec()))
          .append(':').append(nz(item.getPricingQuantity()).stripTrailingZeros().toPlainString())
          .append(':').append(nz(item.getAmount()).stripTrailingZeros().toPlainString());
    }
    return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String intentToDocType(
      String intent,
      Map<String, Object> payload,
      String reply
  ) {
    return switch (intent) {
      case "supply_order_card" -> "supply_order";
      case "delivery_note_card" -> "delivery_note";
      case "transport_note_card" -> "transport_note";
      case "payment_request_card" -> "payment_request";
      case "payment_card" -> "payment";
      case "receipt_card" -> "receipt";
      case "purchase_invoice_card" -> "purchase_invoice";
      case "sales_invoice_card" -> "sales_invoice";
      case "invoice_card" -> invoiceDocType(payload, reply);
      case "sales_settlement_card" -> "sales_settlement";
      case "settlement_card" -> "settlement_unknown";
      case "evidence_only_card" -> "evidence_only";
      default -> "unknown";
    };
  }

  private static String detectedDocTypeLabel(Object detectedType) {
    String value = str(detectedType);
    return StringUtils.hasText(value) && !"unknown".equals(value) ? docTypeLabel(value) : null;
  }

  private static String invoiceDocType(Map<String, Object> payload, String reply) {
    String direction = str(payload.get("invoiceDirection"));
    if ("sales".equals(direction) || (reply != null && reply.contains("销项"))) {
      return "sales_invoice";
    }
    if ("purchase".equals(direction) || (reply != null && reply.contains("进项"))) {
      return "purchase_invoice";
    }
    return "invoice_unknown";
  }

  private static String extractCounterparty(String docType, Map<String, Object> payload) {
    return switch (docType) {
      case "purchase_invoice" -> firstString(payload, "supplierName", "sellerName");
      case "sales_invoice", "sales_settlement", "delivery_note" ->
          firstString(payload, "customerName", "buyerName", "target");
      default -> firstString(payload, "supplierName", "target", "customerName");
    };
  }

  private static String docTypeLabel(String docType) {
    return switch (docType) {
      case "supply_order" -> "采购单";
      case "delivery_note" -> "送货单";
      case "transport_note" -> "运输费用";
      case "payment_request" -> "请款单";
      case "payment" -> "付款记录";
      case "receipt" -> "收款记录";
      case "purchase_invoice", "invoice" -> "进项发票";
      case "sales_invoice" -> "销项发票";
      case "invoice_unknown" -> "待确认发票";
      case "sales_settlement" -> "销售结算单";
      case "settlement_unknown" -> "待确认结算单";
      case "evidence_only" -> "原始凭证";
      default -> "业务单据";
    };
  }

  private static BigDecimal extractAmount(Map<String, Object> payload, String docType) {
    // 送货单语义改造后是销售侧发货单：amount = 销售总金额（与单据总额同口径）。
    // 单据上标注的运输费用走 structuredData.transportFee，由台账运输费用行单独归集，不再混入 amount。
    Object amount = payload.get("totalAmount");
    if (amount == null) amount = payload.get("amount");
    return decimal(amount);
  }

  private static BigDecimal extractAmount(Map<String, Object> payload) {
    return extractAmount(payload, null);
  }

  private static LocalDate extractDate(Map<String, Object> payload) {
    Object date = payload.get("docDate");
    if (date == null) date = payload.get("occurredDate");
    return date(date);
  }

  private static String sourceFromFileName(String fileName) {
    if (fileName == null) return "manual";
    String lower = fileName.toLowerCase();
    if (lower.endsWith(".pdf")) return "pdf";
    if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "excel";
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
        || lower.endsWith(".webp") || lower.endsWith(".bmp")) return "image";
    return "file";
  }

  // -------- 工具方法 --------

  private String toJson(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) return null;
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception ex) {
      return null;
    }
  }

  private Map<String, Object> fromJson(String json) {
    if (!StringUtils.hasText(json)) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {
      });
    } catch (Exception ex) {
      return new LinkedHashMap<>();
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castList(List<?> list) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        result.add((Map<String, Object>) map);
      }
    }
    return result;
  }

  private static String str(Object value) {
    if (value == null) return null;
    String s = String.valueOf(value).trim();
    return s.isEmpty() || "null".equals(s) ? null : s;
  }

  private static BigDecimal decimal(Object value) {
    if (value == null) return BigDecimal.ZERO;
    if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
    try {
      return new BigDecimal(String.valueOf(value).replaceAll("[^\\d.-]", ""));
    } catch (Exception ex) {
      return BigDecimal.ZERO;
    }
  }

  private static BigDecimal decimalOrNull(Object value) {
    if (value == null || "null".equals(String.valueOf(value))) return null;
    return decimal(value);
  }

  private static BigDecimal firstDecimal(Map<String, Object> source, String... keys) {
    for (String key : keys) {
      BigDecimal value = decimalOrNull(source.get(key));
      if (value != null) return value;
    }
    return null;
  }

  private static String firstString(Map<String, Object> source, String... keys) {
    for (String key : keys) {
      String value = str(source.get(key));
      if (StringUtils.hasText(value)) return value;
    }
    return null;
  }

  private static boolean amountMatches(BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    if (quantity == null || unitPrice == null || amount == null
        || quantity.compareTo(BigDecimal.ZERO) <= 0
        || unitPrice.compareTo(BigDecimal.ZERO) <= 0
        || amount.compareTo(BigDecimal.ZERO) <= 0) {
      return false;
    }
    BigDecimal tolerance = amount.multiply(new BigDecimal("0.001")).max(BigDecimal.ONE);
    return quantity.multiply(unitPrice).subtract(amount).abs().compareTo(tolerance) <= 0;
  }

  private static boolean isWeightUnit(String unit) {
    if (!StringUtils.hasText(unit)) return false;
    String normalized = unit.trim().toLowerCase();
    return List.of("吨", "t", "ton", "千克", "kg", "公斤").contains(normalized);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(String.format("%02x", value));
      }
      return hex.toString();
    } catch (Exception ex) {
      throw new IllegalStateException("计算文件指纹失败", ex);
    }
  }

  private static LocalDate date(Object value) {
    if (value == null) return null;
    try {
      return LocalDate.parse(String.valueOf(value).trim().substring(0, 10));
    } catch (Exception ex) {
      return null;
    }
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

  // -------- 结果记录 --------

  public record UploadResult(ChatMessage userMessage, ChatMessage aiMessage, Document document) {
  }
}
