package com.aibot.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aibot.purchase.entity.Batch;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 项目收款分摊规则（用户 2026-11-15 确认的口径）。 */
class ReceiptServiceTest {

  private static Batch batch(String id, String sales) {
    Batch batch = new Batch();
    batch.setId(id);
    batch.setName(id);
    batch.setSalesAmount(new BigDecimal(sales));
    return batch;
  }

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  void thirtyThousandCoversBatchOneTwoAndPartOfThree() {
    List<Batch> batches = List.of(batch("b1", "12000"), batch("b2", "10000"), batch("b3", "15000"));
    ReceiptService.Allocation result = ReceiptService.allocateInOrder(d("30000"), batches, Map.of());
    assertEquals(0, d("12000").compareTo(result.amounts().get("b1")));
    assertEquals(0, d("10000").compareTo(result.amounts().get("b2")));
    assertEquals(0, d("8000").compareTo(result.amounts().get("b3")));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.overflow()));
    assertEquals(ReceiptService.STATUS_PARTIAL, ReceiptService.receiptStatus(d("15000"), d("8000")));
    assertEquals(ReceiptService.STATUS_DONE, ReceiptService.receiptStatus(d("12000"), d("12000")));
  }

  @Test
  void nextReceiptOnlyCountsSelectedBatchesAndIgnoresUnfinishedOlderBatch() {
    // 批次 3 之前只收了 8000，但这笔只选了批次 4、5 → 只在 4、5 上分摊
    List<Batch> selected = List.of(batch("b4", "20000"), batch("b5", "40000"));
    Map<String, BigDecimal> received = Map.of("b3", d("8000"));
    ReceiptService.Allocation result = ReceiptService.allocateInOrder(d("50000"), selected, received);
    assertEquals(0, d("20000").compareTo(result.amounts().get("b4")));
    assertEquals(0, d("30000").compareTo(result.amounts().get("b5")));
    assertEquals(2, result.amounts().size());
  }

  @Test
  void overflowStaysOnLastSelectedBatch() {
    List<Batch> selected = List.of(batch("b1", "1000"), batch("b2", "2000"));
    ReceiptService.Allocation result = ReceiptService.allocateInOrder(d("3500"), selected, Map.of());
    assertEquals(0, d("1000").compareTo(result.amounts().get("b1")));
    assertEquals(0, d("2500").compareTo(result.amounts().get("b2")));
    assertEquals(0, d("500").compareTo(result.overflow()));
  }

  @Test
  void alreadyReceivedPartIsNotChargedAgain() {
    List<Batch> selected = List.of(batch("b3", "15000"), batch("b4", "20000"));
    ReceiptService.Allocation result = ReceiptService.allocateInOrder(
        d("10000"), selected, Map.of("b3", d("8000")));
    assertEquals(0, d("7000").compareTo(result.amounts().get("b3")));
    assertEquals(0, d("3000").compareTo(result.amounts().get("b4")));
  }

  @Test
  void zeroSalesBatchIsNotReceived() {
    assertEquals(ReceiptService.STATUS_NONE, ReceiptService.receiptStatus(d("5000"), BigDecimal.ZERO));
  }
}
