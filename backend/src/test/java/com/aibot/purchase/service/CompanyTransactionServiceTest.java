package com.aibot.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompanyTransactionServiceTest {
  private final CompanyTransactionService service =
      new CompanyTransactionService(null, null, null, null, new ObjectMapper());

  private static Document doc(String id, String type, String status, String supplier, String amount, String date) {
    Document d = new Document();
    d.setId(id);
    d.setBatchId("b1");
    d.setDocType(type);
    d.setStatus(status);
    d.setSupplierName(supplier);
    d.setAmount(new BigDecimal(amount));
    d.setDocDate(date == null ? null : LocalDate.parse(date));
    return d;
  }

  private CompanyTransactionService.Overview run(List<Document> docs) {
    Project p = new Project();
    p.setId("p1");
    p.setName("小学项目");
    Batch b = new Batch();
    b.setId("b1");
    b.setProjectId("p1");
    b.setName("批次1");
    return service.build(docs, Map.of("p1", p), Map.of("b1", b), Document::getAmount);
  }

  @Test
  void summarizesPurchaseInvoicedAndUninvoicedPerSupplier() {
    Document inv = doc("i1", "purchase_invoice", "matched", "上海 找钢网", "60000", "2026-11-02");
    inv.setDocCode("04417206");
    inv.setStructuredData("{\"taxAmount\":\"6902.65\",\"taxRate\":\"13%\"}");
    var o = run(List.of(
        doc("s1", "supply_order", "matched", "上海找钢网", "100000", "2026-11-01"),
        doc("s2", "supply_order", "archived", "上海找钢网", "20000", "2026-11-03"),
        doc("s3", "supply_order", "pending", "上海找钢网", "99999", "2026-11-03"),
        inv,
        doc("i2", "purchase_invoice", "pending", "上海找钢网", "30000", "2026-11-05"),
        doc("s4", "supply_order", "matched", "西城钢铁", "50000", "2026-11-04")));
    assertEquals(0, new BigDecimal("170000").compareTo(o.purchaseAmount()));
    assertEquals(0, new BigDecimal("60000").compareTo(o.invoicedAmount()));
    assertEquals(0, new BigDecimal("110000").compareTo(o.uninvoicedAmount()));
    assertEquals(2, o.supplierCount());
    assertEquals(1, o.invoiceCount());
    assertEquals(1, o.pendingInvoiceCount());
    var zgw = o.suppliers().get(0);
    assertEquals("上海找钢网", zgw.supplierName());
    assertEquals(0, new BigDecimal("60000").compareTo(zgw.uninvoicedAmount()));
    // 列表含待确认发票，按日期倒序
    assertEquals(2, o.invoices().size());
    assertEquals("i2", o.invoices().get(0).id());
    assertFalse(o.invoices().get(0).confirmed());
    var confirmed = o.invoices().get(1);
    assertTrue(confirmed.confirmed());
    assertEquals("04417206", confirmed.invoiceNo());
    assertEquals("13%", confirmed.taxRate());
    assertEquals("小学项目", confirmed.projectName());
  }

  @Test
  void overInvoicedIsNegativeAndBlankSupplierGrouped() {
    var o = run(List.of(
        doc("s1", "supply_order", "matched", "", "1000", null),
        doc("i1", "invoice", "matched", null, "1500", null)));
    assertEquals(1, o.supplierCount());
    assertEquals(CompanyTransactionService.UNKNOWN_SUPPLIER, o.suppliers().get(0).supplierName());
    assertEquals(0, new BigDecimal("-500").compareTo(o.uninvoicedAmount()));
  }

  @Test
  void skippedDuplicateIsExcludedAndPendingDuplicateFlagged() {
    Document skipped = doc("i1", "purchase_invoice", "archived", "西城", "800", "2026-11-01");
    skipped.setDuplicateOfDocumentId("i0");
    skipped.setDuplicateDecision("skip");
    Document dup = doc("i2", "purchase_invoice", "pending", "西城", "800", "2026-11-02");
    dup.setDuplicateOfDocumentId("i0");
    var o = run(List.of(doc("s1", "supply_order", "matched", "西城", "1000", null), skipped, dup));
    assertEquals(0, BigDecimal.ZERO.compareTo(o.invoicedAmount()));
    assertEquals(1, o.invoices().size());
    assertTrue(o.invoices().get(0).duplicatePending());
  }

  @Test
  void emptyDocs() {
    var o = run(List.of());
    assertEquals(0, BigDecimal.ZERO.compareTo(o.purchaseAmount()));
    assertTrue(o.invoices().isEmpty());
  }
}
