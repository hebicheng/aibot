package com.aibot.purchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.LedgerRecord;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.LedgerRecordMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {
  @org.junit.jupiter.api.BeforeAll
  static void initializeReceiptMetadata() {
    var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
    assistant.setCurrentNamespace("LedgerServiceTest");
    com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
        com.aibot.purchase.entity.ProjectReceipt.class);
  }
  @Mock private DocumentMapper documentMapper;
  @Mock private DocumentItemMapper documentItemMapper;
  @Mock private com.aibot.purchase.mapper.DocumentItemMarketMappingMapper itemNetMappingMapper;
  @Mock private LedgerRecordMapper ledgerRecordMapper;
  @Mock private BatchMapper batchMapper;
  @Mock private ProjectMapper projectMapper;
  @Mock private BatchHintService batchHintService;
  @Mock private com.aibot.purchase.service.market.PurchaseSalePriceService salePriceService;
  @Mock private FreightService freightService;
  @Mock private com.aibot.purchase.mapper.ProjectReceiptMapper receiptMapper;
  @Mock private com.aibot.purchase.mapper.ProjectReceiptAllocationMapper allocationMapper;
  @Mock private PurchaseCostBasisService costBasisService;

  private LedgerService service;

  @BeforeEach
  void setUp() {
    service = new LedgerService(
        documentMapper,
        documentItemMapper,
        itemNetMappingMapper,
        ledgerRecordMapper,
        batchMapper,
        projectMapper,
        new ObjectMapper(),
        batchHintService,
        salePriceService,
        freightService,
        receiptMapper,
        allocationMapper,
        costBasisService
    );
    // 运费行由 FreightService 维护：单测里默认没有运费
    lenient().when(freightService.sum(anyString(), anyString())).thenReturn(BigDecimal.ZERO);
    lenient().when(freightService.list(anyString(), any())).thenReturn(List.of());
    // 采购成本基准默认空（没有采购单 / 没有送货单时实际值等于计划值）
    lenient().when(costBasisService.basis(anyString())).thenReturn(
        new PurchaseCostBasisService.Basis(false, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, false, List.of()));
  }

  @Test
  void receiptMetadataEditPreservesExistingMultiBatchAllocations() {
    Batch batch = new Batch();
    batch.setId("b1");
    batch.setProjectId("p1");
    when(batchMapper.selectById("b1")).thenReturn(batch);
    com.aibot.purchase.entity.ProjectReceipt receipt = new com.aibot.purchase.entity.ProjectReceipt();
    receipt.setId("r1");
    receipt.setAmount(new BigDecimal("35000"));
    when(receiptMapper.selectOne(any())).thenReturn(receipt);
    Document doc = receiptDocument("35000");
    service.correctConfirmedDocument(doc, List.of(), doc);
    assertThat(receipt.getAmount()).isEqualByComparingTo("35000");
    verify(allocationMapper, never()).updateById(any(com.aibot.purchase.entity.ProjectReceiptAllocation.class));
    verify(allocationMapper, never()).insert(any(com.aibot.purchase.entity.ProjectReceiptAllocation.class));
  }

  @Test
  void decreasingSourceReceiptReducesLastAllocationsWithoutNegativeAmounts() {
    Batch batch = new Batch();
    batch.setId("b1");
    batch.setProjectId("p1");
    when(batchMapper.selectById("b1")).thenReturn(batch);
    com.aibot.purchase.entity.ProjectReceipt receipt = new com.aibot.purchase.entity.ProjectReceipt();
    receipt.setId("r1");
    receipt.setAmount(new BigDecimal("35000"));
    when(receiptMapper.selectOne(any())).thenReturn(receipt);
    var a1 = receiptAllocation("a1", "b1", "12000");
    var a2 = receiptAllocation("a2", "b2", "10000");
    var a3 = receiptAllocation("a3", "b3", "13000");
    when(allocationMapper.selectList(any())).thenReturn(List.of(a1, a2, a3));
    Document doc = receiptDocument("5000");
    service.correctConfirmedDocument(doc, List.of(), doc);
    assertThat(receipt.getAmount()).isEqualByComparingTo("5000");
    assertThat(a1.getAmount()).isEqualByComparingTo("5000");
    assertThat(a2.getAmount()).isZero();
    assertThat(a3.getAmount()).isZero();
  }

  private Document receiptDocument(String amount) {
    Document doc = new Document();
    doc.setId("doc-r1");
    doc.setBatchId("b1");
    doc.setProjectId("p1");
    doc.setDocType("receipt");
    doc.setAmount(new BigDecimal(amount));
    doc.setDocDate(LocalDate.of(2026, 11, 16));
    return doc;
  }

  private com.aibot.purchase.entity.ProjectReceiptAllocation receiptAllocation(
      String id, String batchId, String amount) {
    var allocation = new com.aibot.purchase.entity.ProjectReceiptAllocation();
    allocation.setId(id);
    allocation.setReceiptId("r1");
    allocation.setBatchId(batchId);
    allocation.setAmount(new BigDecimal(amount));
    return allocation;
  }

  @Test
  void confirmedSupplyCreatesLedgerSkeletonImmediately() {
    Document document = new Document();
    document.setId("document-1");
    document.setProjectId("project-1");
    document.setBatchId("batch-1");
    document.setDocType("supply_order");
    document.setSupplierName("测试供应商");
    document.setDocDate(LocalDate.of(2026, 7, 20));
    document.setAmount(new BigDecimal("176620.21"));
    document.setStructuredData("""
        {"freight":2460,"items":[{"materialName":"旧识别材料"}]}
        """);

    DocumentItem persistedItemOne = new DocumentItem();
    persistedItemOne.setMaterialName("盘螺");
    DocumentItem persistedItemTwo = new DocumentItem();
    persistedItemTwo.setMaterialName("螺纹钢");
    when(ledgerRecordMapper.selectList(any())).thenReturn(List.of());
    when(documentItemMapper.selectList(any())).thenReturn(List.of(persistedItemOne, persistedItemTwo));
    // 采购金额按「批次内全部已确认采购单」重算（2026-09-21 起），必须让批次能查回本单
    when(documentMapper.selectList(any())).thenReturn(List.of(document));
    when(batchMapper.selectById("batch-1")).thenReturn(null);

    service.applyConfirmedDocument(document);

    ArgumentCaptor<LedgerRecord> captor = ArgumentCaptor.forClass(LedgerRecord.class);
    verify(ledgerRecordMapper).insert(captor.capture());
    LedgerRecord record = captor.getValue();
    assertThat(record.getSourceDocumentId()).isEqualTo("document-1");
    assertThat(record.getPurchaseAmount()).isEqualByComparingTo("176620.21");
    assertThat(record.getFreight()).isEqualByComparingTo("0");
    assertThat(record.getUnpaidAmount()).isEqualByComparingTo("176620.21");
    assertThat(record.getMaterial()).isEqualTo("盘螺 / 螺纹钢");
    assertThat(record.getStatus()).isEqualTo("pending");
  }

  @Test
  void supplyLedgerUsesConfirmedItemTotalInsteadOfConflictingHeaderAmount() {
    Document document = new Document();
    document.setId("document-1");
    document.setProjectId("project-1");
    document.setBatchId("batch-1");
    document.setDocType("supply_order");
    document.setSupplierName("测试供应商");
    document.setAmount(new BigDecimal("1000"));

    DocumentItem first = new DocumentItem();
    first.setMaterialName("材料A");
    first.setAmount(new BigDecimal("400"));
    DocumentItem second = new DocumentItem();
    second.setMaterialName("材料B");
    second.setAmount(new BigDecimal("500"));
    when(ledgerRecordMapper.selectList(any())).thenReturn(List.of());
    when(documentItemMapper.selectList(any())).thenReturn(List.of(first, second));
    // 采购金额按「批次内全部已确认采购单」重算（2026-09-21 起），必须让批次能查回本单
    when(documentMapper.selectList(any())).thenReturn(List.of(document));
    when(batchMapper.selectById("batch-1")).thenReturn(null);

    service.applyConfirmedDocument(document);

    ArgumentCaptor<LedgerRecord> captor = ArgumentCaptor.forClass(LedgerRecord.class);
    verify(ledgerRecordMapper).insert(captor.capture());
    assertThat(captor.getValue().getPurchaseAmount()).isEqualByComparingTo("900");
  }

  @Test
  void confirmedSupplementsIncrementTheSameLedgerRow() {
    LedgerRecord record = new LedgerRecord();
    record.setId("ledger-1");
    record.setProjectId("project-1");
    record.setBatchId("batch-1");
    record.setSupplier("测试供应商");
    record.setPurchaseAmount(new BigDecimal("1000"));
    record.setFreight(BigDecimal.ZERO);
    record.setDeliveryAmount(BigDecimal.ZERO);
    record.setRequestedAmount(BigDecimal.ZERO);
    record.setPaidAmount(BigDecimal.ZERO);
    record.setInvoiceAmount(BigDecimal.ZERO);
    record.setSalesAmount(BigDecimal.ZERO);
    record.setReceivedAmount(BigDecimal.ZERO);

    when(ledgerRecordMapper.selectList(any())).thenReturn(List.of(record));
    when(batchMapper.selectById("batch-1")).thenReturn(null);
    DocumentItem deliveryItem = new DocumentItem();
    deliveryItem.setPackageQuantity(new BigDecimal("20"));
    deliveryItem.setPackageUnit("件");
    deliveryItem.setTotalWeight(new BigDecimal("50.2"));
    deliveryItem.setWeightUnit("吨");
    when(documentItemMapper.selectList(any())).thenReturn(List.of(deliveryItem));

    service.applyConfirmedDocument(supplement("delivery_note", "1000", "{\"freight\":80}"));
    service.applyConfirmedDocument(supplement("payment_request", "900", "{}"));
    service.applyConfirmedDocument(supplement("payment", "600", "{}"));
    service.applyConfirmedDocument(supplement("invoice", "1000", "{\"taxRate\":\"13%\"}"));

    // 送货单 = 销售侧发货单：单据金额计入销售金额；单据标注的运输费用（freight）并入运输费用；
    // 到货数量/重量单独回填
    assertThat(record.getSalesAmount()).isEqualByComparingTo("1000");
    assertThat(record.getFreight()).isEqualByComparingTo("80");
    assertThat(record.getDeliveryAmount()).isEqualByComparingTo("0");
    assertThat(record.getDeliveredPackageQuantity()).isEqualByComparingTo("20");
    assertThat(record.getDeliveredPackageUnit()).isEqualTo("件");
    assertThat(record.getDeliveredWeight()).isEqualByComparingTo("50.2");
    assertThat(record.getDeliveredWeightUnit()).isEqualTo("吨");
    assertThat(record.getRequestedAmount()).isEqualByComparingTo("900");
    assertThat(record.getPaidAmount()).isEqualByComparingTo("600");
    // 未付 = 采购 1000 + 运费 80 - 已付 600 = 480
    assertThat(record.getUnpaidAmount()).isEqualByComparingTo("480");
    assertThat(record.getInvoiceAmount()).isEqualByComparingTo("1000");
    assertThat(record.getTaxRate()).isEqualTo("13%");
    assertThat(record.getStatus()).isEqualTo("pending_payment");
    verify(ledgerRecordMapper, org.mockito.Mockito.times(4)).updateById(record);
  }

  @Test
  void confirmedTransportRecordAddsIndependentFreight() {
    LedgerRecord record = ledger("ledger-1", "材料供应商", "螺纹钢");
    record.setPurchaseAmount(new BigDecimal("1000"));
    record.setFreight(BigDecimal.ZERO);
    record.setPaidAmount(BigDecimal.ZERO);
    record.setRequestedAmount(BigDecimal.ZERO);
    record.setInvoiceAmount(BigDecimal.ZERO);
    record.setSalesAmount(BigDecimal.ZERO);
    record.setReceivedAmount(BigDecimal.ZERO);

    when(ledgerRecordMapper.selectList(any())).thenReturn(List.of(record));
    when(batchMapper.selectById("batch-1")).thenReturn(null);

    Document transport = supplement("transport_note", "2796.05", "{}");
    transport.setSupplierName("杭州朋达物流有限公司");
    service.applyConfirmedDocument(transport);

    assertThat(record.getFreight()).isEqualByComparingTo("2796.05");
    assertThat(record.getUnpaidAmount()).isEqualByComparingTo("3796.05");
    assertThat(transport.getLedgerApplied()).isTrue();
    assertThat(transport.getTargetLedgerId()).isEqualTo("ledger-1");
  }

  @Test
  void salesInvoiceUpdatesOnlySalesInvoiceFields() {
    LedgerRecord record = ledger("ledger-1", "材料供应商", "螺纹钢");
    record.setPurchaseAmount(new BigDecimal("100000"));
    record.setFreight(BigDecimal.ZERO);
    record.setPaidAmount(BigDecimal.ZERO);
    record.setRequestedAmount(BigDecimal.ZERO);
    record.setInvoiceAmount(BigDecimal.ZERO);
    record.setSalesAmount(new BigDecimal("198000"));
    record.setSalesInvoiceAmount(BigDecimal.ZERO);
    record.setReceivedAmount(BigDecimal.ZERO);
    when(ledgerRecordMapper.selectList(any())).thenReturn(List.of(record));
    when(batchMapper.selectById("batch-1")).thenReturn(null);

    Document invoice = supplement("sales_invoice", "198000", "{\"taxRate\":\"13%\"}");
    invoice.setDocDate(LocalDate.of(2026, 7, 16));
    service.applyConfirmedDocument(invoice);

    assertThat(record.getInvoiceAmount()).isEqualByComparingTo("0");
    assertThat(record.getSalesInvoiceAmount()).isEqualByComparingTo("198000");
    assertThat(record.getSalesInvoiceDate()).isEqualTo(LocalDate.of(2026, 7, 16));
    assertThat(record.getSalesTaxRate()).isEqualTo("13%");
  }

  @Test
  void evidenceOnlyMarksDocumentAppliedWithoutChangingLedgerValues() {
    LedgerRecord record = ledger("ledger-1", "材料供应商", "螺纹钢");
    record.setPurchaseAmount(new BigDecimal("1000"));
    record.setPaidAmount(new BigDecimal("600"));
    when(ledgerRecordMapper.selectList(any())).thenReturn(List.of(record));
    when(batchMapper.selectById("batch-1")).thenReturn(null);

    Document evidence = supplement("evidence_only", "9999", "{\"processingMode\":\"archive_only\"}");
    service.applyConfirmedDocument(evidence);

    assertThat(evidence.getLedgerApplied()).isTrue();
    assertThat(record.getPurchaseAmount()).isEqualByComparingTo("1000");
    assertThat(record.getPaidAmount()).isEqualByComparingTo("600");
    verify(ledgerRecordMapper, never()).updateById(any(LedgerRecord.class));
    verify(ledgerRecordMapper, never()).insert(any(LedgerRecord.class));
  }

  @Test
  void projectLedgerRejectsCrossTenantAccess() {
    when(projectMapper.selectCount(any())).thenReturn(0L);

    assertThatThrownBy(() -> service.getLedgerByProject("other-user", "project-1"))
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(ledgerRecordMapper, never()).selectList(any());
  }

  @Test
  void currentBatchNeverRequiresSelectingAnotherLedger() {
    Document payment = supplement("payment", "5000", "{}");
    payment.setSupplierName("项目临时结算账户");

    assertThat(service.suggestTargets(payment)).isEmpty();
    verify(ledgerRecordMapper, never()).selectList(any());
  }

  @Test
  void correctingConfirmedPaymentAppliesOnlyTheAmountDifference() {
    LedgerRecord record = ledger("ledger-1", "测试供应商", "螺纹钢");
    record.setPurchaseAmount(new BigDecimal("1000"));
    record.setFreight(BigDecimal.ZERO);
    record.setPaidAmount(new BigDecimal("600"));
    record.setRequestedAmount(BigDecimal.ZERO);
    record.setInvoiceAmount(BigDecimal.ZERO);
    record.setSalesAmount(BigDecimal.ZERO);
    record.setReceivedAmount(BigDecimal.ZERO);
    when(ledgerRecordMapper.selectList(any())).thenReturn(List.of(record));
    when(batchMapper.selectById("batch-1")).thenReturn(null);

    Document before = supplement("payment", "600", "{}");
    before.setTargetLedgerId("ledger-1");
    Document after = supplement("payment", "500", "{}");
    after.setTargetLedgerId("ledger-1");

    service.correctConfirmedDocument(before, List.of(), after);

    assertThat(record.getPaidAmount()).isEqualByComparingTo("500");
    assertThat(record.getUnpaidAmount()).isEqualByComparingTo("500");
    assertThat(after.getLedgerApplied()).isTrue();
    assertThat(after.getTargetLedgerId()).isEqualTo("ledger-1");
  }

  @Test
  void batchSummaryAlwaysCalculatesGrossProfitFromSalesAndConfirmedCosts() {
    LedgerRecord material = ledger("ledger-material", "材料供应商", "螺纹钢");
    material.setProjectId("project-1");
    material.setPurchaseAmount(new BigDecimal("154200"));
    material.setFreight(BigDecimal.ZERO);
    material.setSalesAmount(BigDecimal.ZERO);
    material.setReceivedAmount(BigDecimal.ZERO);
    LedgerRecord transport = ledger("ledger-transport", "运输供应商", "运输费用");
    transport.setProjectId("project-1");
    transport.setPurchaseAmount(BigDecimal.ZERO);
    transport.setFreight(new BigDecimal("6800"));
    transport.setSalesAmount(BigDecimal.ZERO);
    transport.setReceivedAmount(BigDecimal.ZERO);

    Batch batch = new Batch();
    batch.setId("batch-1");
    batch.setProjectId("project-1");
    Project project = new Project();
    project.setId("project-1");

    when(batchMapper.selectById("batch-1")).thenReturn(batch);
    when(projectMapper.selectCount(any())).thenReturn(1L);
    when(ledgerRecordMapper.selectList(any())).thenReturn(List.of(material, transport));
    when(projectMapper.selectById("project-1")).thenReturn(project);
    when(batchMapper.selectList(any())).thenReturn(List.of(batch));

    service.updateBatchSummary("user-1", "batch-1", Map.of(
        "salesAmount", new BigDecimal("198000"),
        "grossProfit", new BigDecimal("1"),
        "grossMargin", new BigDecimal("1")
    ));

    assertThat(material.getGrossProfit()).isEqualByComparingTo("37000");
    assertThat(material.getGrossMargin()).isEqualByComparingTo("18.69");
    assertThat(batch.getProfitAmount()).isEqualByComparingTo("37000");
    assertThat(batch.getMarginRate()).isEqualByComparingTo("18.69");
    assertThat(project.getProfitAmount()).isEqualByComparingTo("37000");
    assertThat(project.getMarginRate()).isEqualByComparingTo("18.69");
  }

  private Document supplement(String type, String amount, String structuredData) {
    Document document = new Document();
    document.setId(type + "-1");
    document.setProjectId("project-1");
    document.setBatchId("batch-1");
    document.setDocType(type);
    document.setSupplierName("测试供应商");
    document.setAmount(new BigDecimal(amount));
    document.setStructuredData(structuredData);
    return document;
  }

  private LedgerRecord ledger(String id, String supplier, String material) {
    LedgerRecord record = new LedgerRecord();
    record.setId(id);
    record.setBatchId("batch-1");
    record.setSupplier(supplier);
    record.setMaterial(material);
    return record;
  }
}
