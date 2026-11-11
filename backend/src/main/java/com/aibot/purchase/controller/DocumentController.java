package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.LedgerRecord;
import com.aibot.purchase.service.ChatAiService;
import com.aibot.purchase.service.DocumentService;
import com.aibot.purchase.service.LedgerService;
import com.aibot.purchase.service.UserService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 单据 & 台账 API
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {
  private final DocumentService documentService;
  private final LedgerService ledgerService;
  private final UserService userService;
  private final com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService;
  private final com.aibot.purchase.service.BatchHintService batchHintService;

  public DocumentController(
      DocumentService documentService,
      LedgerService ledgerService,
      UserService userService,
      com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService,
      com.aibot.purchase.service.BatchHintService batchHintService
  ) {
    this.documentService = documentService;
    this.ledgerService = ledgerService;
    this.userService = userService;
    this.deliveryNetPriceService = deliveryNetPriceService;
    this.batchHintService = batchHintService;
  }

  /**
   * 上传文件 → AI 识别 → 返回识别结果（聊天消息 + 单据）
   */
  @PostMapping("/upload")
  public ApiResponse<Map<String, Object>> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam String projectId,
      @RequestParam String batchId,
      @RequestParam(required = false) String text,
      @RequestParam(required = false) String projectName,
      @RequestParam(required = false) String batchName
  ) {
    String userId = CurrentUser.get().id();
    var batch = documentService.requireBatchContext(userId, projectId, batchId);
    var project = documentService.requireProjectContext(userId, projectId);
    var profile = userService.getProfile(userId);
    var hints = batchHintService.contextFor(batchId);
    var aiContext = new ChatAiService.AiContext(
        profile.companyName(), batch.projectName(), batch.name(), project.customerName(),
        hints.effectiveText(), hints.batchJson(), hints.projectJson());
    var result = documentService.uploadAndRecognize(userId, file, projectId, batchId, text, aiContext);
    return ApiResponse.ok(Map.of(
        "userMessage", result.userMessage(),
        "aiMessage", result.aiMessage(),
        "document", result.document() != null ? result.document() : Map.of()
    ));
  }

  /**
   * 纯文本输入 → AI 识别
   */
  @PostMapping("/send-text")
  public ApiResponse<Map<String, Object>> sendText(@RequestBody Map<String, String> body) {
    String userId = CurrentUser.get().id();
    String text = body.get("text");
    String projectId = body.get("projectId");
    String batchId = body.get("batchId");
    var batch = documentService.requireBatchContext(userId, projectId, batchId);
    var project = documentService.requireProjectContext(userId, projectId);
    var profile = userService.getProfile(userId);
    var hints = batchHintService.contextFor(batchId);
    var aiContext = new ChatAiService.AiContext(
        profile.companyName(), batch.projectName(), batch.name(), project.customerName(),
        hints.effectiveText(), hints.batchJson(), hints.projectJson());
    var result = documentService.sendText(userId, text, projectId, batchId, aiContext);
    return ApiResponse.ok(Map.of(
        "userMessage", result.userMessage(),
        "aiMessage", result.aiMessage(),
        "document", result.document() != null ? result.document() : Map.of()
    ));
  }

  /**
   * 查询批次下的单据列表
   */
  @GetMapping
  public ApiResponse<List<Document>> list(
      @RequestParam(required = false) String batchId,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String docType,
      @RequestParam(required = false) String status
  ) {
    String userId = CurrentUser.get().id();
    if (batchId != null && !batchId.isBlank()) {
      return ApiResponse.ok(documentService.listByBatch(userId, batchId, docType));
    }
    if (projectId != null && !projectId.isBlank()) {
      return ApiResponse.ok(documentService.listByProject(userId, projectId, docType, status));
    }
    return ApiResponse.ok(List.of());
  }

  /**
   * 获取单据详情（含明细行）
   */
  @GetMapping("/{id}")
  public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
    String userId = CurrentUser.get().id();
    Document doc = documentService.getById(userId, id);
    List<DocumentItem> items = documentService.getItems(userId, id);
    return ApiResponse.ok(Map.of("document", doc, "items", items));
  }

  @GetMapping("/data")
  public ApiResponse<List<Map<String, Object>>> data(
      @RequestParam(required = false) String batchId,
      @RequestParam(required = false) String projectId
  ) {
    return ApiResponse.ok(documentService.listBusinessData(
        CurrentUser.get().id(), projectId, batchId));
  }

  /**
   * 确认单据
   */
  @PostMapping("/{id}/confirm")
  public ApiResponse<Document> confirm(@PathVariable String id) {
    return ApiResponse.ok(documentService.confirmDocument(CurrentUser.get().id(), id));
  }

  /**
   * 编辑单据（人工修正）
   */
  @PutMapping("/{id}")
  public ApiResponse<Document> edit(@PathVariable String id, @RequestBody Map<String, Object> edits) {
    return ApiResponse.ok(documentService.editDocument(CurrentUser.get().id(), id, edits));
  }

  /**
   * 删除单据（软删除）：待确认直接删除；已入库先回退台账贡献再删除，
   * 批次/项目金额快照同步重算。底层明细/附件/锁定行情保留可审计。
   */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable String id) {
    documentService.deleteDocument(CurrentUser.get().id(), id);
    return ApiResponse.ok();
  }

  @PostMapping("/{id}/duplicate-decision")
  public ApiResponse<Document> duplicateDecision(
      @PathVariable String id,
      @RequestBody Map<String, String> body
  ) {
    return ApiResponse.ok(documentService.resolveDuplicate(
        CurrentUser.get().id(), id, body.get("decision")));
  }

  /** 台账销售金额明细（送货单 × 明细行，含锁定网价/销售单价/计价数量/金额）。 */
  @GetMapping("/sales-detail")
  public ApiResponse<List<Map<String, Object>>> salesDetail(@RequestParam String batchId) {
    return ApiResponse.ok(deliveryNetPriceService.salesDetail(CurrentUser.get().id(), batchId));
  }

  @GetMapping("/{id}/access-url")
  public ApiResponse<Map<String, String>> accessUrl(@PathVariable String id) {
    Document document = documentService.getById(CurrentUser.get().id(), id);
    return ApiResponse.ok(Map.of(
        "url", documentService.accessUrl(CurrentUser.get().id(), id),
        "fileName", document.getFileName() == null ? "原始附件" : document.getFileName(),
        "source", document.getSource()
    ));
  }

  // ---------------------------------------------------------------------------
  // 台账
  // ---------------------------------------------------------------------------

  /**
   * 生成/重新生成批次台账
   */
  @PostMapping("/ledger/generate")
  public ApiResponse<List<LedgerRecord>> generateLedger(@RequestBody Map<String, String> body) {
    String userId = CurrentUser.get().id();
    String projectId = body.get("projectId");
    String batchId = body.get("batchId");
    return ApiResponse.ok(ledgerService.generateLedger(userId, projectId, batchId));
  }

  /**
   * 查询台账
   */
  @GetMapping("/ledger")
  public ApiResponse<List<LedgerRecord>> getLedger(
      @RequestParam(required = false) String batchId,
      @RequestParam(required = false) String projectId
  ) {
    String userId = CurrentUser.get().id();
    if (batchId != null && !batchId.isBlank()) {
      return ApiResponse.ok(ledgerService.getLedger(userId, batchId));
    }
    if (projectId != null && !projectId.isBlank()) {
      return ApiResponse.ok(ledgerService.getLedgerByProject(userId, projectId));
    }
    return ApiResponse.ok(List.of());
  }

  @PutMapping("/ledger/batch/{batchId}")
  public ApiResponse<List<LedgerRecord>> updateLedgerBatch(
      @PathVariable String batchId,
      @RequestBody Map<String, Object> edits
  ) {
    return ApiResponse.ok(ledgerService.updateBatchSummary(
        CurrentUser.get().id(), batchId, edits));
  }

  /** 人工改运费行的承运单位（按行改：运费行 id → 承运单位，空串表示清空回到「待补充」）。 */
  @PutMapping("/ledger/batch/{batchId}/freight-carriers")
  public ApiResponse<List<LedgerRecord>> updateFreightCarriers(
      @PathVariable String batchId,
      @RequestBody Map<String, String> carriers
  ) {
    return ApiResponse.ok(ledgerService.updateFreightCarriers(
        CurrentUser.get().id(), batchId, carriers));
  }
}
