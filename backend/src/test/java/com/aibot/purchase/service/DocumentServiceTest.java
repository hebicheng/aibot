package com.aibot.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.BusinessDtos.BatchDto;
import com.aibot.purchase.entity.ChatMessage;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.Material;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.ChatMessageMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.MaterialMapper;
import com.aibot.purchase.service.ai.AiReply;
import com.aibot.purchase.service.ai.DocumentMediaPreprocessor;
import com.aibot.purchase.service.ai.QwenClient;
import com.aibot.purchase.service.storage.FileStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentItemMapper documentItemMapper;
  @Mock private ChatMessageMapper chatMessageMapper;
  @Mock private ChatAiService chatAiService;
  @Mock private FileStorage fileStorage;
  @Mock private DocumentMediaPreprocessor mediaPreprocessor;
  @Mock private BusinessQueryService businessQueryService;
  @Mock private LedgerService ledgerService;
  @Mock private MaterialMapper materialMapper;
  @Mock private BatchMapper batchMapper;
  @Mock private com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService;
  @Mock private com.aibot.purchase.service.market.DeliveryNetPriceTrigger deliveryNetPriceTrigger;
  @Mock private com.aibot.purchase.service.market.MaterialLayerTrigger materialLayerTrigger;
  @Mock private com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService;
  @Mock private com.aibot.purchase.service.BillingService billingService;
  @Mock private ReconciliationChatService reconciliationChatService;
  @Mock private BatchHintService batchHintService;
  @Mock private com.aibot.purchase.service.market.PurchaseSalePriceService purchaseSalePriceService;
  @Mock private com.aibot.purchase.mapper.DocumentItemMarketMappingMapper itemNetMappingMapper;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private DocumentService service;

  @BeforeEach
  void setUp() {
    service = new DocumentService(
        documentMapper,
        documentItemMapper,
        chatMessageMapper,
        chatAiService,
        fileStorage,
        mediaPreprocessor,
        businessQueryService,
        ledgerService,
        materialMapper,
        batchMapper,
        objectMapper,
        deliveryNetPriceService,
        deliveryNetPriceTrigger,
        materialLayerTrigger,
        marketQuoteImportService,
        billingService,
        reconciliationChatService,
        batchHintService,
        purchaseSalePriceService,
        itemNetMappingMapper
    );
  }

  @Test
  void uploadBuildsVersionedCardAndNormalizesLegacyPieceCount() throws Exception {
    when(businessQueryService.getBatch("user-1", "batch-1")).thenReturn(batch());
    when(mediaPreprocessor.prepare(any(), any(), any())).thenReturn(
        new DocumentMediaPreprocessor.PreparedDocument(
            List.of(new QwenClient.MediaInput("data:image/png;base64,AA==", "image/png")),
            null,
            "image"
        ));
    when(fileStorage.store(any(), any(), any())).thenReturn(
        new FileStorage.StoredFile("documents/test.png", "https://example.test/test.png", "png", "image/png"));
    when(chatAiService.analyzeFile(any(), any(), any(), any())).thenReturn(new ChatAiService.AiAnalysisResult(
        AiReply.card(
            "supply_order_card",
            "识别到供货单",
            Map.of(
                "docCode", "XS-001",
                "supplierName", "测试供应商",
                "docDate", "2026-07-20",
                "totalAmount", 19920,
                "items", List.of(Map.of(
                    "materialName", "三级抗震盘螺",
                    "spec", "HRB400E 8",
                    "unit", "吨",
                    "quantity", 3,
                    "unitPrice", 3320,
                    "amount", 19920
                )),
                "confidence", 95
            )),
        new QwenClient.QwenUsage(0, 0, 0)));

    AtomicInteger chatIds = new AtomicInteger();
    List<DocumentItem> storedItems = new ArrayList<>();
    when(chatMessageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
      ChatMessage message = invocation.getArgument(0);
      message.setId("message-" + chatIds.incrementAndGet());
      return 1;
    });
    when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
      Document document = invocation.getArgument(0);
      document.setId("document-1");
      return 1;
    });
    when(documentItemMapper.insert(any(DocumentItem.class))).thenAnswer(invocation -> {
      DocumentItem item = invocation.getArgument(0);
      item.setId("item-1");
      storedItems.add(item);
      return 1;
    });
    when(documentItemMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(storedItems));

    var result = service.uploadAndRecognize(
        "user-1",
        new MockMultipartFile("file", "supply.png", "image/png", new byte[] {1, 2, 3}),
        "project-1",
        "batch-1",
        "请优先按送货单核对到货情况",
        new ChatAiService.AiContext("测试项目", "测试批次")
    );

    DocumentItem item = storedItems.get(0);
    assertThat(item.getPackageQuantity()).isEqualByComparingTo("3");
    assertThat(item.getPackageUnit()).isEqualTo("件");
    assertThat(item.getPricingQuantity()).isEqualByComparingTo("6.000");
    assertThat(item.getTotalWeight()).isEqualByComparingTo("6.000");

    JsonNode card = objectMapper.readTree(result.aiMessage().getPayload());
    assertThat(card.path("schemaVersion").asText()).isEqualTo("1.0");
    assertThat(card.path("cardType").asText()).isEqualTo("document_review");
    assertThat(card.path("status").asText()).isEqualTo("needs_review");
    assertThat(card.path("actions").get(0).path("actionKey").asText()).isEqualTo("document.confirm");
    assertThat(card.path("document").path("items").get(0).path("pricingQuantity").decimalValue())
        .isEqualByComparingTo("6.000");
    JsonNode userPayload = objectMapper.readTree(result.userMessage().getPayload());
    assertThat(userPayload.path("fileName").asText()).isEqualTo("supply.png");
    assertThat(userPayload.path("guidanceText").asText()).isEqualTo("请优先按送货单核对到货情况");
    assertThat(result.document().getGuidanceText()).isEqualTo("请优先按送货单核对到货情况");
  }

  @Test
  void aiArchiveDecisionKeepsDetectedTypeAndDoesNotCreateLedgerEntry() throws Exception {
    when(businessQueryService.getBatch("user-1", "batch-1")).thenReturn(batch());
    when(mediaPreprocessor.prepare(any(), any(), any())).thenReturn(
        new DocumentMediaPreprocessor.PreparedDocument(
            List.of(new QwenClient.MediaInput("data:image/png;base64,AA==", "image/png")),
            "供货单号 GH-001，供应商测试供应商，金额19920元",
            "image"
        ));
    when(fileStorage.store(any(), any(), any())).thenReturn(
        new FileStorage.StoredFile("documents/supply.png", "https://example.test/supply.png", "png", "image/png"));
    when(chatAiService.analyzeFile(any(), any(), any(), any())).thenReturn(new ChatAiService.AiAnalysisResult(
        AiReply.card(
            "evidence_only_card",
            "已识别资料内容为供货单，并按你的要求作为原始凭证保存，不会更新台账。",
            Map.of(
                "docCode", "GH-001",
                "supplierName", "测试供应商",
                "docDate", "2026-07-20",
                "totalAmount", 19920,
                "processingMode", "archive_only",
                "detectedSourceType", "supply_order",
                "applyToLedger", false,
                "confidence", 96
            )),
        new QwenClient.QwenUsage(0, 0, 0)));

    AtomicInteger chatIds = new AtomicInteger();
    when(chatMessageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
      ChatMessage message = invocation.getArgument(0);
      message.setId("archive-message-" + chatIds.incrementAndGet());
      return 1;
    });
    when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
      Document document = invocation.getArgument(0);
      document.setId("archive-document-1");
      return 1;
    });
    when(documentMapper.selectList(any())).thenReturn(List.of());
    when(documentItemMapper.selectList(any())).thenReturn(List.of());

    var result = service.uploadAndRecognize(
        "user-1",
        new MockMultipartFile("file", "supply.png", "image/png", new byte[] {1, 2, 3}),
        "project-1",
        "batch-1",
        "作为原始凭证。",
        new ChatAiService.AiContext("测试项目", "测试批次")
    );

    assertThat(result.document().getDocType()).isEqualTo("evidence_only");
    assertThat(result.document().getSourceText()).contains("供货单号 GH-001");
    JsonNode structured = objectMapper.readTree(result.document().getStructuredData());
    assertThat(structured.path("processingMode").asText()).isEqualTo("archive_only");
    assertThat(structured.path("detectedSourceType").asText()).isEqualTo("supply_order");
    assertThat(structured.path("applyToLedger").asBoolean()).isFalse();

    JsonNode card = objectMapper.readTree(result.aiMessage().getPayload());
    assertThat(card.path("document").path("docTypeLabel").asText()).isEqualTo("原始凭证");
    assertThat(card.path("document").path("detectedDocTypeLabel").asText()).isEqualTo("采购单");
    assertThat(card.path("missingFields")).isEmpty();
    assertThat(card.path("actions").get(0).path("label").asText()).isEqualTo("确认并归档");
  }

  @Test
  void paymentTypeOverrideRequiresMissingTargetFactsBeforeConfirmation() throws Exception {
    when(businessQueryService.getBatch("user-1", "batch-1")).thenReturn(batch());
    when(chatAiService.analyzeText(any(), any(), any())).thenReturn(new ChatAiService.AiAnalysisResult(
        AiReply.card(
            "payment_card",
            "按用户要求作为付款记录处理",
            Map.of(
                "amount", 120000,
                "detectedSourceType", "payment_request",
                "requestedDocType", "payment",
                "typeOverride", true,
                "targetMissingFields", List.of("docDate", "supplierName"),
                "confidence", 92
            )),
        new QwenClient.QwenUsage(0, 0, 0)));
    AtomicInteger chatIds = new AtomicInteger();
    when(chatMessageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
      ChatMessage message = invocation.getArgument(0);
      message.setId("override-message-" + chatIds.incrementAndGet());
      return 1;
    });
    when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
      Document document = invocation.getArgument(0);
      document.setId("override-payment-1");
      return 1;
    });
    when(documentMapper.selectList(any())).thenReturn(List.of());
    when(documentItemMapper.selectList(any())).thenReturn(List.of());

    var result = service.sendText(
        "user-1",
        "把这份请款数据按付款记录处理",
        "project-1",
        "batch-1",
        new ChatAiService.AiContext("测试项目", "测试批次")
    );

    JsonNode card = objectMapper.readTree(result.aiMessage().getPayload());
    assertThat(card.path("document").path("typeOverride").asBoolean()).isTrue();
    assertThat(card.path("document").path("detectedDocTypeLabel").asText()).isEqualTo("请款单");
    assertThat(card.path("missingFields")).extracting(JsonNode::asText)
        .contains("docDate", "supplierName");

    when(documentMapper.selectOne(any())).thenReturn(result.document());
    assertThatThrownBy(() -> service.confirmDocument("user-1", "override-payment-1"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("单据日期");

    when(chatMessageMapper.selectById(result.document().getSourceMessageId()))
        .thenReturn(result.aiMessage());
    service.editDocument("user-1", "override-payment-1", Map.of(
        "docDate", "2026-07-23",
        "supplierName", "测试供应商"
    ));

    JsonNode updatedStructured = objectMapper.readTree(result.document().getStructuredData());
    assertThat(updatedStructured.path("targetMissingFields")).isEmpty();
  }

  @Test
  void textRecognitionCreatesIndependentTransportRecord() throws Exception {
    when(businessQueryService.getBatch("user-1", "batch-1")).thenReturn(batch());
    when(chatAiService.analyzeText(any(), any(), any())).thenReturn(new ChatAiService.AiAnalysisResult(
        AiReply.card(
            "transport_note_card",
            "识别到运输费用",
            Map.of(
                "docCode", "YS-001",
                "supplierName", "杭州朋达物流有限公司",
                "docDate", "2026-07-22",
                "totalAmount", 2796.05,
                "driverName", "王大伟",
                "confidence", 94
            )),
        new QwenClient.QwenUsage(0, 0, 0)));
    AtomicInteger chatIds = new AtomicInteger();
    when(chatMessageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
      ChatMessage message = invocation.getArgument(0);
      message.setId("message-" + chatIds.incrementAndGet());
      return 1;
    });
    when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
      Document document = invocation.getArgument(0);
      document.setId("transport-1");
      return 1;
    });
    when(documentMapper.selectList(any())).thenReturn(List.of());
    when(documentItemMapper.selectList(any())).thenReturn(List.of());

    var result = service.sendText(
        "user-1",
        "杭州朋达物流运输费2796.05元",
        "project-1",
        "batch-1",
        new ChatAiService.AiContext("测试项目", "测试批次")
    );

    assertThat(result.document().getDocType()).isEqualTo("transport_note");
    assertThat(result.document().getSupplierName()).isEqualTo("杭州朋达物流有限公司");
    assertThat(result.document().getAmount()).isEqualByComparingTo("2796.05");
    JsonNode card = objectMapper.readTree(result.aiMessage().getPayload());
    assertThat(card.path("document").path("docTypeLabel").asText()).isEqualTo("运输费用");
  }

  @Test
  void invoiceReplyExplicitlyIdentifiedAsSalesNeverFallsBackToPurchaseInvoice() throws Exception {
    when(businessQueryService.getBatch("user-1", "batch-1")).thenReturn(batch());
    when(chatAiService.analyzeText(any(), any(), any())).thenReturn(new ChatAiService.AiAnalysisResult(
        AiReply.card(
            "invoice_card",
            "已识别为销项发票，金额198000元。",
            Map.of(
                "invoiceNo", "OUT-20260716-001",
                "sellerName", "杭州衡建供应链有限公司",
                "buyerName", "杭州市临平区人民医院",
                "docDate", "2026-07-16",
                "amount", 198000,
                "netAmount", 175221.24,
                "taxAmount", 22778.76,
                "taxRate", "13%",
                "invoiceDirection", "unknown",
                "confidence", 98
            )),
        new QwenClient.QwenUsage(0, 0, 0)));
    AtomicInteger chatIds = new AtomicInteger();
    when(chatMessageMapper.insert(any(ChatMessage.class))).thenAnswer(invocation -> {
      ChatMessage message = invocation.getArgument(0);
      message.setId("message-" + chatIds.incrementAndGet());
      return 1;
    });
    when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
      Document document = invocation.getArgument(0);
      document.setId("sales-invoice-1");
      return 1;
    });
    when(documentMapper.selectList(any())).thenReturn(List.of());
    when(documentItemMapper.selectList(any())).thenReturn(List.of());

    var result = service.sendText(
        "user-1",
        "这是销项发票",
        "project-1",
        "batch-1",
        new ChatAiService.AiContext(
            "杭州衡建供应链有限公司", "测试项目", "测试批次", "杭州市临平区人民医院")
    );

    assertThat(result.document().getDocType()).isEqualTo("sales_invoice");
    assertThat(result.document().getSupplierName()).isEqualTo("杭州市临平区人民医院");
    JsonNode card = objectMapper.readTree(result.aiMessage().getPayload());
    assertThat(card.path("document").path("docTypeLabel").asText()).isEqualTo("销项发票");
  }

  @Test
  void confirmIsIdempotentAfterDocumentWasApplied() {
    Document document = supplyDocument("matched");
    when(documentMapper.selectOne(any())).thenReturn(document);

    assertThat(service.confirmDocument("user-1", "document-1")).isSameAs(document);

    verify(documentMapper, never()).updateById(any(Document.class));
    verify(materialMapper, never()).insert(any(Material.class));
    verify(ledgerService, never()).applyConfirmedDocument(any());
  }

  @Test
  void confirmWritesPricingMeasurementToPurchaseMaterial() {
    Document document = supplyDocument("pending");
    DocumentItem item = new DocumentItem();
    item.setMaterialName("三级抗震盘螺");
    item.setSpec("HRB400E 8");
    item.setPackageQuantity(new BigDecimal("3"));
    item.setPackageUnit("件");
    item.setPricingQuantity(new BigDecimal("6.000"));
    item.setPricingUnit("吨");
    item.setUnitPrice(new BigDecimal("3320"));
    item.setAmount(new BigDecimal("19920"));

    when(documentMapper.selectOne(any())).thenReturn(document);
    when(documentItemMapper.selectList(any())).thenReturn(List.of(item));

    service.confirmDocument("user-1", "document-1");

    ArgumentCaptor<Material> materialCaptor = ArgumentCaptor.forClass(Material.class);
    verify(materialMapper).insert(materialCaptor.capture());
    assertThat(materialCaptor.getValue().getQuantity()).isEqualByComparingTo("6.000");
    assertThat(materialCaptor.getValue().getUnit()).isEqualTo("吨");
    verify(ledgerService).applyConfirmedDocument(document);
    assertThat(document.getStatus()).isEqualTo("matched");
    ArgumentCaptor<ChatMessage> resultMessage = ArgumentCaptor.forClass(ChatMessage.class);
    verify(chatMessageMapper).insert(resultMessage.capture());
    assertThat(resultMessage.getValue().getBusinessType()).isEqualTo("operation_result");
    assertThat(resultMessage.getValue().getPayload()).contains(
        "\"cardType\":\"operation_result\"",
        "\"docTypeLabel\":\"采购单\"",
        "\"docCode\":\"");
  }

  @Test
  void paymentCanBeConfirmedWithoutAnyInvoiceFields() {
    Document payment = supplementDocument("pending");
    payment.setStructuredData("{\"method\":\"银行转账\"}");
    when(documentMapper.selectOne(any())).thenReturn(payment);

    service.confirmDocument("user-1", "supplement-1");

    assertThat(payment.getStatus()).isEqualTo("matched");
    verify(ledgerService).applyConfirmedDocument(payment);
  }

  @Test
  void confirmingSupplyRefreshesCardsForAutomaticallyAppliedQueuedDocuments() throws Exception {
    Document supply = supplyDocument("pending");
    supply.setSourceMessageId("supply-message");
    Document queued = supplementDocument("matched");
    queued.setSourceMessageId("queued-message");
    queued.setLedgerApplied(false);

    DocumentItem item = new DocumentItem();
    item.setMaterialName("三级抗震盘螺");
    item.setPricingQuantity(new BigDecimal("6"));
    item.setPricingUnit("吨");
    item.setUnitPrice(new BigDecimal("3320"));
    item.setAmount(new BigDecimal("19920"));

    ChatMessage supplyMessage = new ChatMessage();
    supplyMessage.setId("supply-message");
    ChatMessage queuedMessage = new ChatMessage();
    queuedMessage.setId("queued-message");

    when(documentMapper.selectOne(any())).thenReturn(supply);
    when(documentMapper.selectList(any())).thenReturn(List.of(supply, queued));
    when(documentItemMapper.selectList(any())).thenReturn(List.of(item));
    when(chatMessageMapper.selectById("supply-message")).thenReturn(supplyMessage);
    when(chatMessageMapper.selectById("queued-message")).thenReturn(queuedMessage);
    doAnswer(invocation -> {
      Document confirmed = invocation.getArgument(0);
      confirmed.setLedgerApplied(true);
      queued.setLedgerApplied(true);
      return List.of();
    }).when(ledgerService).applyConfirmedDocument(supply);

    service.confirmDocument("user-1", "document-1");

    assertThat(objectMapper.readTree(supplyMessage.getPayload()).path("status").asText())
        .isEqualTo("applied");
    assertThat(objectMapper.readTree(queuedMessage.getPayload()).path("status").asText())
        .isEqualTo("applied");
    assertThat(queuedMessage.getStatus()).isEqualTo("archived");
  }

  @Test
  void deliveryWithoutAmountCanBeConfirmedWhenMeasurementsExist() {
    Document delivery = supplementDocument("pending");
    delivery.setDocType("delivery_note");
    delivery.setAmount(BigDecimal.ZERO);
    delivery.setSourceMessageId("delivery-message");
    DocumentItem item = new DocumentItem();
    item.setPackageQuantity(new BigDecimal("20"));
    item.setPackageUnit("件");
    item.setTotalWeight(new BigDecimal("50.2"));
    item.setWeightUnit("吨");
    ChatMessage message = new ChatMessage();
    message.setId("delivery-message");

    when(documentMapper.selectOne(any())).thenReturn(delivery);
    when(documentItemMapper.selectList(any())).thenReturn(List.of(item));
    when(chatMessageMapper.selectById("delivery-message")).thenReturn(message);

    service.confirmDocument("user-1", "supplement-1");

    assertThat(delivery.getStatus()).isEqualTo("matched");
    assertThat(objectMapper.createObjectNode().put("payload", message.getPayload())
        .path("payload").asText()).contains("\"status\":\"queued\"");
    verify(ledgerService).applyConfirmedDocument(delivery);
  }

  @Test
  void transportWithoutDateCanBeConfirmedAndCompletedLater() {
    Document transport = supplementDocument("pending");
    transport.setDocType("transport_note");
    transport.setSupplierName("杭州朋达物流有限公司");
    transport.setAmount(new BigDecimal("2796.05"));
    transport.setDocDate(null);
    transport.setSourceMessageId("transport-message");
    ChatMessage message = new ChatMessage();
    message.setId("transport-message");

    when(documentMapper.selectOne(any())).thenReturn(transport);
    when(documentItemMapper.selectList(any())).thenReturn(List.of());
    when(chatMessageMapper.selectById("transport-message")).thenReturn(message);

    service.confirmDocument("user-1", "supplement-1");

    assertThat(transport.getStatus()).isEqualTo("matched");
    assertThat(transport.getDocDate()).isNull();
    verify(ledgerService).applyConfirmedDocument(transport);
  }

  @Test
  void evidenceOnlyCanBeConfirmedWithoutAmountOrDate() {
    Document evidence = supplementDocument("pending");
    evidence.setDocType("evidence_only");
    evidence.setAmount(BigDecimal.ZERO);
    evidence.setDocDate(null);
    evidence.setSupplierName(null);

    when(documentMapper.selectOne(any())).thenReturn(evidence);

    service.confirmDocument("user-1", "supplement-1");

    assertThat(evidence.getStatus()).isEqualTo("archived");
    verify(materialMapper, never()).insert(any(Material.class));
    verify(ledgerService).applyConfirmedDocument(evidence);
  }

  @Test
  void editingPendingDocumentReplacesDetailRowsAndPersistsFingerprint() {
    Document document = supplyDocument("pending");
    when(documentMapper.selectOne(any())).thenReturn(document);

    List<DocumentItem> storedItems = new ArrayList<>();
    when(documentItemMapper.insert(any(DocumentItem.class))).thenAnswer(invocation -> {
      DocumentItem item = invocation.getArgument(0);
      item.setId("edited-item-" + storedItems.size());
      storedItems.add(item);
      return 1;
    });
    when(documentItemMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(storedItems));

    Document result = service.editDocument("user-1", "document-1", Map.of(
        "amount", 24000,
        "items", List.of(
            Map.of(
                "materialName", "工字钢",
                "spec", "Q235B",
                "pricingQuantity", 5,
                "pricingUnit", "吨",
                "unitPrice", 3600,
                "amount", 18000
            ),
            Map.of(
                "materialName", "槽钢",
                "spec", "10#",
                "pricingQuantity", 2,
                "pricingUnit", "吨",
                "unitPrice", 3000,
                "amount", 6000
            )
        )
    ));

    assertThat(result.getAmount()).isEqualByComparingTo("24000");
    assertThat(storedItems).hasSize(2);
    assertThat(storedItems).extracting(DocumentItem::getMaterialName)
        .containsExactly("工字钢", "槽钢");
    assertThat(result.getBusinessFingerprint()).isNotBlank();
    verify(documentItemMapper).delete(any());
    verify(documentMapper, org.mockito.Mockito.times(2)).updateById(document);
  }

  @Test
  void editingConfirmedDataUpdatesProjectionWithoutReturningToPending() {
    Document document = supplyDocument("matched");
    document.setLedgerApplied(true);
    DocumentItem oldItem = new DocumentItem();
    oldItem.setMaterialName("工字钢");
    oldItem.setPricingQuantity(new BigDecimal("5"));
    oldItem.setPricingUnit("吨");
    oldItem.setUnitPrice(new BigDecimal("3600"));
    oldItem.setAmount(new BigDecimal("18000"));

    List<DocumentItem> storedItems = new ArrayList<>(List.of(oldItem));
    when(documentMapper.selectOne(any())).thenReturn(document);
    when(documentItemMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(storedItems));
    when(documentItemMapper.delete(any())).thenAnswer(invocation -> {
      storedItems.clear();
      return 1;
    });
    when(documentItemMapper.insert(any(DocumentItem.class))).thenAnswer(invocation -> {
      storedItems.add(invocation.getArgument(0));
      return 1;
    });

    service.editDocument("user-1", "document-1", Map.of(
        "amount", 20000,
        "items", List.of(Map.of(
            "materialName", "工字钢",
            "spec", "Q235B",
            "pricingQuantity", 5,
            "pricingUnit", "吨",
            "unitPrice", 4000,
            "amount", 20000
        ))
    ));

    assertThat(document.getStatus()).isEqualTo("matched");
    assertThat(document.getAmount()).isEqualByComparingTo("20000");
    verify(ledgerService).correctConfirmedDocument(any(Document.class), any(), any(Document.class));
    verify(businessQueryService).addLog(
        "user-1", "project-1", "batch-1", "business_data_updated",
        "采购单 document-1 入库数据已修正");
  }

  @Test
  void confirmRejectsDocumentOwnedByAnotherUser() {
    when(documentMapper.selectOne(any())).thenReturn(null);

    assertThatThrownBy(() -> service.confirmDocument("other-user", "document-1"))
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(ledgerService, never()).applyConfirmedDocument(any());
  }

  private BatchDto batch() {
    return new BatchDto(
        "batch-1", "project-1", "测试项目", "测试批次", LocalDate.of(2026, 7, 20),
        "钢筋", "进行中", 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, null, java.util.Map.of(), null
    );
  }

  private Document supplyDocument(String status) {
    Document document = new Document();
    document.setId("document-1");
    document.setUserId("user-1");
    document.setProjectId("project-1");
    document.setBatchId("batch-1");
    document.setDocType("supply_order");
    document.setSupplierName("测试供应商");
    document.setAmount(new BigDecimal("19920"));
    document.setDocDate(LocalDate.of(2026, 7, 20));
    document.setStatus(status);
    return document;
  }

  private Document supplementDocument(String status) {
    Document document = new Document();
    document.setId("supplement-1");
    document.setUserId("user-1");
    document.setProjectId("project-1");
    document.setBatchId("batch-1");
    document.setDocType("payment");
    document.setSupplierName("测试供应商");
    document.setAmount(new BigDecimal("600"));
    document.setDocDate(LocalDate.of(2026, 7, 21));
    document.setStatus(status);
    return document;
  }
}
