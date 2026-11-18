package com.aibot.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.BusinessDtos.ProjectDto;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.LedgerRecord;
import com.aibot.purchase.entity.OperationLog;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.entity.ProjectReceipt;
import com.aibot.purchase.entity.ProjectReceiptAllocation;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.FileRecordMapper;
import com.aibot.purchase.mapper.FinanceRecordMapper;
import com.aibot.purchase.mapper.FlowEventMapper;
import com.aibot.purchase.mapper.LedgerRecordMapper;
import com.aibot.purchase.mapper.MaterialMapper;
import com.aibot.purchase.mapper.MaterialMarketMappingMapper;
import com.aibot.purchase.mapper.OperationLogMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.mapper.ProjectReceiptAllocationMapper;
import com.aibot.purchase.mapper.SupplierMapper;
import com.aibot.purchase.mapper.SupplierQuoteMapper;
import com.aibot.purchase.service.market.DeliveryNetPriceService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class BusinessQueryServiceTest {
  @Mock private ProjectMapper projectMapper;
  @Mock private BatchMapper batchMapper;
  @Mock private LedgerRecordMapper ledgerRecordMapper;
  @Mock private ProjectReceiptAllocationMapper allocationMapper;
  @Mock private ReceiptCascadeService receiptCascadeService;
  @Mock private OperationLogMapper operationLogMapper;
  @Mock private AggregationService aggregationService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private BusinessQueryService service;
  private Project project;
  private Batch batch;

  @BeforeAll
  static void initializeTableMetadata() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    assistant.setCurrentNamespace("BusinessQueryServiceTest");
    for (Class<?> entity : List.of(Project.class, Batch.class, LedgerRecord.class,
        ProjectReceipt.class, ProjectReceiptAllocation.class)) {
      TableInfoHelper.initTableInfo(assistant, entity);
    }
  }

  @BeforeEach
  void setUp() {
    service = new BusinessQueryService(projectMapper, batchMapper,
        mock(MaterialMapper.class), mock(SupplierMapper.class), mock(SupplierQuoteMapper.class),
        mock(FlowEventMapper.class), mock(FinanceRecordMapper.class), mock(FileRecordMapper.class),
        operationLogMapper, mock(MaterialMarketMappingMapper.class), ledgerRecordMapper,
        aggregationService, objectMapper, mock(DeliveryNetPriceService.class),
        allocationMapper, receiptCascadeService);
    project = new Project();
    project.setId("project-1");
    project.setOwnerId("owner-1");
    project.setName("测试项目");
    project.setSalesAmount(new BigDecimal("100000"));
    batch = new Batch();
    batch.setId("batch-1");
    batch.setProjectId(project.getId());
    batch.setName("测试批次");
    batch.setSalesAmount(project.getSalesAmount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"1000", "0"})
  void batchWithAnyReceiptAllocationCascadesBeforeDeletion(String amount) {
    authorizeBatch();
    when(allocationMapper.selectList(any())).thenReturn(List.of(allocation(amount)));
    when(receiptCascadeService.deleteForBatch("owner-1", project.getId(), batch.getId())).thenReturn(1);
    service.deleteBatch("owner-1", batch.getId());
    InOrder order = inOrder(receiptCascadeService, batchMapper, aggregationService);
    order.verify(receiptCascadeService).deleteForBatch("owner-1", project.getId(), batch.getId());
    order.verify(batchMapper).deleteById(batch.getId());
    order.verify(aggregationService).recomputeProject(project.getId());
    verify(operationLogMapper).insert(any(OperationLog.class));
    verify(projectMapper, never()).deleteById(anyString());
  }

  @Test
  void projectCascadeRemovesReceiptsBeforeBatchesAndProject() {
    authorizeProject();
    when(receiptCascadeService.deleteForProject("owner-1", project.getId())).thenReturn(2);
    service.deleteProject("owner-1", project.getId());
    InOrder order = inOrder(receiptCascadeService, batchMapper, projectMapper, operationLogMapper);
    order.verify(receiptCascadeService).deleteForProject("owner-1", project.getId());
    order.verify(batchMapper).delete(any(Wrapper.class));
    order.verify(projectMapper).deleteById(project.getId());
    order.verify(operationLogMapper).insert(any(OperationLog.class));
  }

  @Test
  void projectWithoutBatchesStillDeletesReceipts() {
    when(projectMapper.selectOne(any())).thenReturn(project);
    service.deleteProject("owner-1", project.getId());
    verify(receiptCascadeService).deleteForProject("owner-1", project.getId());
    verify(projectMapper).deleteById(project.getId());
    verifyNoInteractions(ledgerRecordMapper);
  }

  @Test
  void batchWithoutReceiptsCanBeDeleted() {
    authorizeBatch();
    service.deleteBatch("owner-1", batch.getId());
    verify(receiptCascadeService).deleteForBatch("owner-1", project.getId(), batch.getId());
    verify(batchMapper).deleteById(batch.getId());
    verify(aggregationService).recomputeProject(project.getId());
  }

  @Test
  void failedReceiptCascadeDoesNotDeleteBatch() {
    authorizeBatch();
    when(receiptCascadeService.deleteForBatch("owner-1", project.getId(), batch.getId()))
        .thenThrow(new IllegalStateException("删除收款失败"));
    assertThrows(IllegalStateException.class, () -> service.deleteBatch("owner-1", batch.getId()));
    assertNoDeletes();
    verifyNoInteractions(aggregationService, operationLogMapper);
  }

  @Test
  void failedReceiptCascadeDoesNotDeleteProject() {
    authorizeProject();
    when(receiptCascadeService.deleteForProject("owner-1", project.getId()))
        .thenThrow(new IllegalStateException("删除收款失败"));
    assertThrows(IllegalStateException.class, () -> service.deleteProject("owner-1", project.getId()));
    assertNoDeletes();
    verifyNoInteractions(aggregationService, operationLogMapper);
  }

  @Test
  void unauthorizedBatchDeletionFailsBeforeReceiptCascade() {
    when(batchMapper.selectById(batch.getId())).thenReturn(batch);
    ApiException error = assertThrows(ApiException.class,
        () -> service.deleteBatch("other-owner", batch.getId()));
    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
    assertNoDeletes();
    verifyNoInteractions(allocationMapper, receiptCascadeService, ledgerRecordMapper,
        aggregationService, operationLogMapper);
  }

  @Test
  void unauthorizedProjectDeletionFailsBeforeReceiptCascade() {
    ApiException error = assertThrows(ApiException.class,
        () -> service.deleteProject("other-owner", project.getId()));
    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
    assertNoDeletes();
    verifyNoInteractions(allocationMapper, receiptCascadeService, ledgerRecordMapper,
        aggregationService, operationLogMapper);
    verify(batchMapper, never()).selectList(any());
  }

  @ParameterizedTest
  @CsvSource({"110000,-10000,0", "0,100000,100000", "40000,60000,60000", "100000,0,0"})
  void projectDifferenceAndRemainingReceivableSerializeSeparately(
      String received, String difference, String outstanding) throws Exception {
    authorizeProject();
    when(allocationMapper.selectList(any())).thenReturn(List.of(allocation(received)));
    ProjectDto dto = service.getProject("owner-1", project.getId());
    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(dto));
    assertThat(dto.salesAmount()).isEqualByComparingTo("100000");
    assertThat(dto.receivedAmount()).isEqualByComparingTo(received);
    assertThat(dto.differenceAmount()).isEqualByComparingTo(difference);
    assertThat(dto.outstanding()).isEqualByComparingTo(outstanding);
    assertThat(json.has("differenceAmount")).isTrue();
    assertThat(json.get("differenceAmount").decimalValue()).isEqualByComparingTo(difference);
    assertThat(json.get("outstanding").decimalValue()).isEqualByComparingTo(outstanding);
    assertThat(json.get("receivedAmount").decimalValue()).isEqualByComparingTo(received);
    assertThat(json.size()).isEqualTo(ProjectDto.class.getRecordComponents().length + 1);
  }

  @Test
  void projectWithoutReceiptAllocationsKeepsFullRemainingReceivable() {
    authorizeProject();
    ProjectDto dto = service.getProject("owner-1", project.getId());
    assertThat(dto.receivedAmount()).isEqualByComparingTo("0");
    assertThat(dto.differenceAmount()).isEqualByComparingTo("100000");
    assertThat(dto.outstanding()).isEqualByComparingTo("100000");
  }

  @Test
  void receiptQueriesUseLogicalDeletionMetadata() {
    assertThat(TableInfoHelper.getTableInfo(ProjectReceipt.class).getLogicDeleteSql(true, true))
        .contains("deleted=0");
  }

  @Test
  void sharedLedgerAndExportHeaderClarifiesRemainingReceivableWithoutNewColumns() {
    assertThat(LedgerTableSchema.HEADER.get(2))
        .anyMatch(cell -> "剩余应收金额".equals(cell.label()) && cell.exportable());
    assertThat(LedgerTableSchema.EXPORT_COLUMN_COUNT).isEqualTo(36);
  }

  private void authorizeBatch() {
    when(batchMapper.selectById(batch.getId())).thenReturn(batch);
    when(projectMapper.selectOne(any())).thenReturn(project);
  }

  private void authorizeProject() {
    when(projectMapper.selectOne(any())).thenReturn(project);
    when(batchMapper.selectList(any())).thenReturn(List.of(batch));
  }

  private ProjectReceiptAllocation allocation(String amount) {
    ProjectReceiptAllocation allocation = new ProjectReceiptAllocation();
    allocation.setId("allocation-1");
    allocation.setReceiptId("receipt-1");
    allocation.setProjectId(project.getId());
    allocation.setBatchId(batch.getId());
    allocation.setAmount(new BigDecimal(amount));
    return allocation;
  }

  private void assertNoDeletes() {
    verify(batchMapper, never()).deleteById(anyString());
    verify(batchMapper, never()).delete(any(Wrapper.class));
    verify(projectMapper, never()).deleteById(anyString());
    verify(projectMapper, never()).delete(any(Wrapper.class));
  }
}
