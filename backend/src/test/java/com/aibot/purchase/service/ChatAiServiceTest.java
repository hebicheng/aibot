package com.aibot.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aibot.purchase.config.AiProperties;
import com.aibot.purchase.service.ai.AiReply;
import com.aibot.purchase.service.ai.QwenClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatAiServiceTest {
  @Mock private QwenClient qwenClient;

  private ChatAiService service;

  @BeforeEach
  void setUp() {
    AiProperties properties = new AiProperties(
        false,
        new AiProperties.Qwen("test-key", "https://example.test", "qwen3.7-plus", 5)
    );
    service = new ChatAiService(properties, qwenClient, new ObjectMapper());
    when(qwenClient.isConfigured()).thenReturn(true);
  }

  @Test
  void archiveProcessingModeWinsWhenModelCardIntentIsInconsistent() {
    when(qwenClient.completeMultimodal(any(), any(), any())).thenReturn(new QwenClient.QwenResult("""
        {
          "intent": "delivery_note_card",
          "reply": "识别到送货单",
          "confidence": 95,
          "payload": {
            "docCode": "0009184",
            "docDate": "2026-07-20",
            "processingMode": "archive_only",
            "detectedSourceType": "delivery_note",
            "applyToLedger": false
          }
        }
        """, new QwenClient.QwenUsage(0, 0, 0)));

    var result = service.analyzeFile(
        List.of(new QwenClient.MediaInput("data:image/png;base64,AA==", "image/png")),
        null,
        "作为原始凭证。",
        new ChatAiService.AiContext("杭州葭安科技有限公司", "测试项目", "测试批次", "测试客户")
    );
    AiReply reply = result.reply();

    assertThat(reply.intent()).isEqualTo("evidence_only_card");
    assertThat(reply.payload().get("processingMode")).isEqualTo("archive_only");
    assertThat(reply.payload().get("detectedSourceType")).isEqualTo("delivery_note");
    assertThat(reply.payload().get("applyToLedger")).isEqualTo(false);
    assertThat(reply.content()).contains("送货单").contains("不会更新台账");
  }

  @Test
  void ordinaryDocumentDefaultsToLedgerProcessingWithoutKeywordRules() {
    when(qwenClient.completeMultimodal(any(), any(), any())).thenReturn(new QwenClient.QwenResult("""
        {
          "intent": "delivery_note_card",
          "reply": "识别到送货单",
          "confidence": 95,
          "payload": {
            "docCode": "0009184",
            "docDate": "2026-07-20"
          }
        }
        """, new QwenClient.QwenUsage(0, 0, 0)));

    var result = service.analyzeFile(
        List.of(new QwenClient.MediaInput("data:image/png;base64,AA==", "image/png")),
        null,
        null,
        new ChatAiService.AiContext("杭州葭安科技有限公司", "测试项目", "测试批次", "测试客户")
    );
    AiReply reply = result.reply();

    assertThat(reply.intent()).isEqualTo("delivery_note_card");
    assertThat(reply.payload().get("processingMode")).isEqualTo("apply_to_ledger");
    assertThat(reply.payload().get("detectedSourceType")).isEqualTo("delivery_note");
    assertThat(reply.payload().get("applyToLedger")).isEqualTo(true);
  }

  @Test
  void salesSettlementWithCompanyAsCustomerIsCorrectedToSupplyOrder() {
    // 卖方平台（如找钢网）的“销售单/销售结算单”把本单位列为客户单位（买方），
    // 从本单位台账视角应为供货单，而非销售结算单。
    when(qwenClient.completeMultimodal(any(), any(), any())).thenReturn(new QwenClient.QwenResult("""
        {
          "intent": "sales_settlement_card",
          "reply": "识别到销售结算单",
          "payload": {
            "docCode": "ZGN-2026-001",
            "sellerName": "上海找钢网电子商务有限公司",
            "customerName": "杭州葭安科技有限公司",
            "amount": 150000,
            "expenseCategory": "material"
          }
        }
        """, new QwenClient.QwenUsage(0, 0, 0)));

    var result = service.analyzeFile(
        List.of(new QwenClient.MediaInput("data:image/png;base64,AA==", "image/png")),
        null,
        null,
        new ChatAiService.AiContext("杭州葭安科技有限公司", "测试项目", "测试批次", "测试客户")
    );
    AiReply reply = result.reply();

    assertThat(reply.intent()).isEqualTo("supply_order_card");
    assertThat(reply.payload().get("supplierName")).isEqualTo("上海找钢网电子商务有限公司");
    assertThat(reply.payload().get("detectedSourceType")).isEqualTo("supply_order");
    assertThat(reply.content()).contains("采购单").contains("购买方");
  }

  @Test
  void salesSettlementWithCompanyAsSellerStaysSalesSettlement() {
    when(qwenClient.completeMultimodal(any(), any(), any())).thenReturn(new QwenClient.QwenResult("""
        {
          "intent": "sales_settlement_card",
          "reply": "识别到销售结算单",
          "payload": {
            "docCode": "XS-2026-001",
            "sellerName": "杭州葭安科技有限公司",
            "customerName": "某工程有限公司",
            "amount": 200000,
            "expenseCategory": "material"
          }
        }
        """, new QwenClient.QwenUsage(0, 0, 0)));

    var result = service.analyzeFile(
        List.of(new QwenClient.MediaInput("data:image/png;base64,AA==", "image/png")),
        null,
        null,
        new ChatAiService.AiContext("杭州葭安科技有限公司", "测试项目", "测试批次", "测试客户")
    );
    AiReply reply = result.reply();

    assertThat(reply.intent()).isEqualTo("sales_settlement_card");
    assertThat(reply.payload().get("detectedSourceType")).isEqualTo("sales_settlement");
  }
}
