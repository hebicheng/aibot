package com.aibot.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.BusinessDtos.ReceiptAllocationInput;
import com.aibot.purchase.dto.BusinessDtos.ReceiptSaveRequest;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.entity.ProjectReceipt;
import com.aibot.purchase.entity.ProjectReceiptAllocation;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.mapper.ProjectReceiptMapper;
import com.aibot.purchase.mapper.ProjectReceiptAllocationMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import org.mockito.InOrder;

import com.aibot.purchase.entity.Batch;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 项目收款分摊规则（用户 2026-11-15 确认的口径）。 */
class ReceiptServiceTest {
  @org.junit.jupiter.api.BeforeAll
  static void initializeReceiptMetadata() {
    var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
    assistant.setCurrentNamespace("ReceiptServiceTest");
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ProjectReceipt.class);
  }

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

  @Test
  void managementUpdateSynchronizesSourceAfterSavingAllocations() {
    UpdateFixture f = new UpdateFixture("doc-1");
    LocalDate date = LocalDate.of(2026, 11, 16);
    var result = f.service.update("u1", "p1", "r1", new ReceiptSaveRequest(date, d("35000"),
        "新付款单位", null, List.of(new ReceiptAllocationInput("b1", d("10000")),
        new ReceiptAllocationInput("b2", d("25000")))));

    assertThat(result.amount()).isEqualByComparingTo("35000");
    assertThat(result.allocations()).extracting(a -> a.amount())
        .containsExactly(d("10000"), d("25000"));
    InOrder order = inOrder(f.receiptMapper, f.allocationMapper, f.documentService, f.ledgerService);
    order.verify(f.receiptMapper).update(any(ProjectReceipt.class), any());
    order.verify(f.allocationMapper).delete(any());
    order.verify(f.allocationMapper, org.mockito.Mockito.times(2)).insert(any(ProjectReceiptAllocation.class));
    order.verify(f.documentService).syncReceiptFromManagement("u1", "p1", "doc-1",
        d("35000"), date, "新付款单位");
    order.verify(f.ledgerService).refreshReceived("b1");
    order.verify(f.ledgerService).refreshReceived("b2");
  }

  @Test
  void manualReceiptUpdateDoesNotTouchDocuments() {
    UpdateFixture f = new UpdateFixture(null);
    f.service.update("u1", "p1", "r1", new ReceiptSaveRequest(LocalDate.of(2026, 11, 16),
        d("35000"), "付款方", "备注", List.of(new ReceiptAllocationInput("b2", d("35000")))));
    verifyNoInteractions(f.documentService);
    verify(f.ledgerService).refreshReceived("b1");
    verify(f.ledgerService).refreshReceived("b2");
  }

  @Test
  void invalidAllocationIsRejectedBeforeSourceSync() {
    UpdateFixture f = new UpdateFixture("doc-1");
    assertThrows(ApiException.class, () -> f.service.update("u1", "p1", "r1",
        new ReceiptSaveRequest(LocalDate.of(2026, 11, 16), d("35000"), null, null,
            List.of(new ReceiptAllocationInput("b1", d("10000"))))));
    verifyNoInteractions(f.documentService, f.ledgerService);
  }

  private static class UpdateFixture {
    final ProjectMapper projectMapper = mock(ProjectMapper.class);
    final BatchMapper batchMapper = mock(BatchMapper.class);
    final ProjectReceiptMapper receiptMapper = mock(ProjectReceiptMapper.class);
    final ProjectReceiptAllocationMapper allocationMapper = mock(ProjectReceiptAllocationMapper.class);
    final LedgerService ledgerService = mock(LedgerService.class);
    final BusinessQueryService businessQueryService = mock(BusinessQueryService.class);
    final DocumentService documentService = mock(DocumentService.class);
    final ReceiptService service = new ReceiptService(projectMapper, batchMapper, receiptMapper,
        allocationMapper, ledgerService, businessQueryService, documentService);
    final List<ProjectReceiptAllocation> stored = new ArrayList<>();

    UpdateFixture(String sourceId) {
      Project project = new Project();
      project.setId("p1");
      when(projectMapper.selectOne(any())).thenReturn(project);
      Batch b1 = batch("b1", "10000");
      Batch b2 = batch("b2", "30000");
      when(batchMapper.selectList(any())).thenReturn(List.of(b1, b2));
      ProjectReceipt receipt = new ProjectReceipt();
      receipt.setId("r1");
      receipt.setProjectId("p1");
      receipt.setAmount(d("30000"));
      receipt.setSourceDocumentId(sourceId);
      when(receiptMapper.selectById("r1")).thenReturn(receipt);
      ProjectReceiptAllocation old = new ProjectReceiptAllocation();
      old.setBatchId("b1");
      old.setAmount(d("30000"));
      stored.add(old);
      when(allocationMapper.selectList(any())).thenAnswer(i -> new ArrayList<>(stored));
      doAnswer(i -> { stored.clear(); return 1; }).when(allocationMapper).delete(any());
      doAnswer(i -> { stored.add(i.getArgument(0)); return 1; })
          .when(allocationMapper).insert(any(ProjectReceiptAllocation.class));
    }
  }
}
