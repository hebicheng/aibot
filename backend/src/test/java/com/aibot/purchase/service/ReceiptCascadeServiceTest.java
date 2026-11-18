package com.aibot.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.ProjectReceipt;
import com.aibot.purchase.entity.ProjectReceiptAllocation;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.ProjectReceiptAllocationMapper;
import com.aibot.purchase.mapper.ProjectReceiptMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReceiptCascadeServiceTest {
  @Mock private ProjectReceiptMapper receiptMapper;
  @Mock private ProjectReceiptAllocationMapper allocationMapper;
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentService documentService;
  @Mock private LedgerService ledgerService;
  private ReceiptCascadeService service;

  @BeforeAll
  static void metadata() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    assistant.setCurrentNamespace("ReceiptCascadeServiceTest");
    for (Class<?> entity : List.of(ProjectReceipt.class, ProjectReceiptAllocation.class, Document.class)) {
      TableInfoHelper.initTableInfo(assistant, entity);
    }
  }

  @BeforeEach
  void setUp() {
    service = new ReceiptCascadeService(receiptMapper, allocationMapper, documentMapper,
        documentService, ledgerService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"8000", "0"})
  void deletingOneBatchDeletesWholeSharedReceiptAndRefreshesOtherBatches(String share) {
    ProjectReceipt receipt = receipt("r1", null);
    ProjectReceiptAllocation a3 = allocation("r1", "b3", share);
    when(allocationMapper.selectList(any())).thenReturn(List.of(a3), List.of(
        allocation("r1", "b1", "12000"), allocation("r1", "b2", "10000"), a3));
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt));
    assertThat(service.deleteForBatch("u1", "p1", "b3")).isEqualTo(1);
    ArgumentCaptor<Wrapper<ProjectReceiptAllocation>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(allocationMapper).delete(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("receipt_id").doesNotContain("batch_id");
    assertThat(((LambdaQueryWrapper<?>) query.getValue()).getParamNameValuePairs())
        .containsValue("r1").doesNotContainValue("unrelated-receipt");
    verify(receiptMapper).delete(any(Wrapper.class));
    verify(ledgerService).refreshReceived("b1");
    verify(ledgerService).refreshReceived("b2");
    verify(ledgerService).refreshReceived("b3");
    verifyNoInteractions(documentService);
    InOrder order = inOrder(allocationMapper, receiptMapper, ledgerService);
    order.verify(allocationMapper).delete(any(Wrapper.class));
    order.verify(receiptMapper).delete(any(Wrapper.class));
    order.verify(ledgerService).refreshReceived("b1");
  }

  @Test
  void sourceDocumentInSurvivingBatchIsAlsoDeletedAndCardUpdatedThroughExistingFlow() {
    when(allocationMapper.selectList(any())).thenReturn(
        List.of(allocation("r1", "b3", "8000")), List.of(allocation("r1", "b2", "30000")));
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt("r1", "d2")));
    when(documentMapper.selectById("d2")).thenReturn(document("d2", "b2"));
    service.deleteForBatch("u1", "p1", "b3");
    verify(documentService).deleteDocument("u1", "d2");
    verify(ledgerService).refreshReceived("b2");
    InOrder order = inOrder(documentService, allocationMapper);
    order.verify(documentService).deleteDocument("u1", "d2");
    order.verify(allocationMapper).delete(any(Wrapper.class));
  }

  @Test
  void sourceBatchWithAllocationsMovedAwayStillDeletesWholeReceipt() {
    when(allocationMapper.selectList(any())).thenReturn(List.of(), List.of(allocation("r1", "b2", "30000")));
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt("r1", "d1")));
    Document doc = document("d1", "b1");
    when(documentMapper.selectList(any())).thenReturn(List.of(doc));
    when(documentMapper.selectById("d1")).thenReturn(doc);
    service.deleteForBatch("u1", "p1", "b1");
    verify(documentService).deleteDocument("u1", "d1"); // 同一来源只调用一次
    verify(ledgerService).refreshReceived("b2");
    ArgumentCaptor<Wrapper<ProjectReceipt>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(receiptMapper).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("source_document_id IN", "batch_id");
    assertThat(((LambdaQueryWrapper<?>) query.getValue()).getParamNameValuePairs())
        .containsValue("p1").containsValue("b1");
  }

  @Test
  void pendingReceiptDocumentsWithoutReceiptRowsAreDeletedToo() {
    Document pending = document("d1", "b1");
    pending.setStatus("pending");
    when(documentMapper.selectList(any())).thenReturn(List.of(pending));
    when(documentMapper.selectById("d1")).thenReturn(pending);
    assertThat(service.deleteForBatch("u1", "p1", "b1")).isZero();
    verify(documentService).deleteDocument("u1", "d1");
    verify(allocationMapper, never()).delete(any(Wrapper.class));
    verifyNoInteractions(ledgerService);
  }

  @Test
  void projectWithoutBatchesDeletesOrphanReceiptsAndResidualAllocations() {
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt("r1", null)));
    when(allocationMapper.selectList(any())).thenReturn(List.of(allocation("r-old", "b-old", "200")),
        List.of(allocation("r1", "b1", "30000"), allocation("r-old", "b-old", "200")));
    assertThat(service.deleteForProject("u1", "p1")).isEqualTo(1);
    ArgumentCaptor<Wrapper<ProjectReceipt>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(receiptMapper).delete(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("project_id", "id");
    assertThat(((LambdaQueryWrapper<?>) query.getValue()).getParamNameValuePairs())
        .containsValue("p1").containsValue("r1").containsValue("r-old");
    verify(ledgerService).refreshReceived("b1");
    verify(ledgerService).refreshReceived("b-old");
  }

  @Test
  void alreadyDeletedSourceStillCleansReceiptAndAllocations() {
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt("r1", "gone")));
    when(allocationMapper.selectList(any())).thenReturn(List.of(), List.of(allocation("r1", "b2", "30000")));
    service.deleteForBatch("u1", "p1", "b1");
    verifyNoInteractions(documentService);
    verify(receiptMapper).delete(any(Wrapper.class));
    verify(ledgerService).refreshReceived("b2");
  }

  @Test
  void noAssociationsDoesNotRunUnrestrictedDeletesOrRefresh() {
    assertThat(service.deleteForBatch("u1", "p1", "b1")).isZero();
    verify(allocationMapper, never()).delete(any(Wrapper.class));
    verify(receiptMapper, never()).delete(any(Wrapper.class));
    verifyNoInteractions(documentService, ledgerService);
  }

  @Test
  void invalidCrossProjectSourceAbortsBeforeRemoval() {
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt("r1", "d1")));
    Document doc = document("d1", "b1");
    doc.setProjectId("p-other");
    when(documentMapper.selectById("d1")).thenReturn(doc);
    assertThrows(ApiException.class, () -> service.deleteForBatch("u1", "p1", "b1"));
    assertNothingDeleted();
  }

  @Test
  void crossProjectAllocationsAbortBeforeRemoval() {
    ProjectReceiptAllocation wrong = allocation("r1", "b-other", "30000");
    wrong.setProjectId("p-other");
    when(allocationMapper.selectList(any())).thenReturn(List.of(allocation("r1", "b1", "30000")), List.of(wrong));
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt("r1", null)));
    assertThrows(ApiException.class, () -> service.deleteForBatch("u1", "p1", "b1"));
    assertNothingDeleted();
  }

  @Test
  void sourceSharedByAnotherProjectCannotBypassProjectScope() {
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt("r1", "d1")));
    when(documentMapper.selectById("d1")).thenReturn(document("d1", "b1"));
    when(receiptMapper.exists(any())).thenReturn(true);
    ApiException error = assertThrows(ApiException.class,
        () -> service.deleteForBatch("u1", "p1", "b1"));
    assertThat(error.getMessage()).contains("跨项目共用");
    assertNothingDeleted();
  }

  @Test
  void sourceDeletionFailurePropagatesAndStopsReceiptDeletion() {
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt("r1", "d1")));
    when(documentMapper.selectById("d1")).thenReturn(document("d1", "b1"));
    org.mockito.Mockito.doThrow(new IllegalStateException("rollback"))
        .when(documentService).deleteDocument("u1", "d1");
    assertThrows(IllegalStateException.class, () -> service.deleteForBatch("u1", "p1", "b1"));
    verify(allocationMapper, never()).delete(any(Wrapper.class));
    verify(receiptMapper, never()).delete(any(Wrapper.class));
    verifyNoInteractions(ledgerService);
  }

  private void assertNothingDeleted() {
    verify(allocationMapper, never()).delete(any(Wrapper.class));
    verify(receiptMapper, never()).delete(any(Wrapper.class));
    verify(documentService, never()).deleteDocument(anyString(), anyString());
    verifyNoInteractions(ledgerService);
  }

  private ProjectReceipt receipt(String id, String source) {
    ProjectReceipt r = new ProjectReceipt();
    r.setId(id);
    r.setProjectId("p1");
    r.setAmount(new BigDecimal("30000"));
    r.setSourceDocumentId(source);
    return r;
  }

  private ProjectReceiptAllocation allocation(String id, String batch, String amount) {
    ProjectReceiptAllocation a = new ProjectReceiptAllocation();
    a.setReceiptId(id);
    a.setProjectId("p1");
    a.setBatchId(batch);
    a.setAmount(new BigDecimal(amount));
    return a;
  }

  private Document document(String id, String batch) {
    Document d = new Document();
    d.setId(id);
    d.setUserId("u1");
    d.setProjectId("p1");
    d.setBatchId(batch);
    d.setDocType("receipt");
    d.setStatus("matched");
    return d;
  }
}
