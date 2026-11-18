package com.aibot.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aibot.purchase.dto.BusinessDtos.BatchDto;
import com.aibot.purchase.dto.BusinessDtos.ProjectDto;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.LedgerRecord;
import com.aibot.purchase.mapper.DocumentItemMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

/**
 * 台账导出的结构性验证：三层表头、跨行合并、数据行与状态。
 * 使用 Mockito 构造数据，不依赖数据库。
 */
class LedgerExportServiceTest {

  @Test
  void exportsWorkbookWithGroupedHeaderAndMergedRows() throws Exception {
    BusinessQueryService businessQueryService = mock(BusinessQueryService.class);
    DocumentService documentService = mock(DocumentService.class);
    DocumentItemMapper documentItemMapper = mock(DocumentItemMapper.class);
    LedgerService ledgerService = mock(LedgerService.class);
    PurchaseCostBasisService costBasisService = mock(PurchaseCostBasisService.class);
    when(costBasisService.basis(any())).thenReturn(
        new PurchaseCostBasisService.Basis(false, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, false, List.of()));

    ProjectDto project = new ProjectDto("p1", "测试项目", "客户A", "进行中", 0,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, java.util.Map.of(), null, 1);
    BatchDto batch = new BatchDto("b1", "p1", "测试项目", "批次1",
        LocalDate.of(2026, 9, 1), "螺纹钢", "进行中", 0,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "",
        java.util.Map.of(), null, false, BigDecimal.ZERO, "未收款");

    when(businessQueryService.getProject("u1", "p1")).thenReturn(project);
    when(businessQueryService.listBatches("u1", "p1")).thenReturn(List.of(batch));

    Document supply = document("d1", "supply_order", "供应商A",
        new BigDecimal("1000"), LocalDate.of(2026, 9, 1));
    Document transport = document("d2", "transport_note", "物流B",
        new BigDecimal("100"), LocalDate.of(2026, 9, 2));
    // 送货单上的金额按配送运输费用处理（与运输单并列，但整批合并为一行）
    Document delivery = document("d3", "delivery_note", "配送C",
        new BigDecimal("300"), LocalDate.of(2026, 9, 3));
    when(documentService.listByProject("u1", "p1", null, null))
        .thenReturn(List.of(supply, transport, delivery));
    when(documentService.structuredDataOf(any())).thenReturn(Map.of());

    DocumentItem item = item("d1", "螺纹钢", "HRB400",
        new BigDecimal("10"), "吨", new BigDecimal("100"), new BigDecimal("1000"));
    DocumentItem secondItem = item("d1", "螺纹钢", "HRB500",
        new BigDecimal("5"), "吨", new BigDecimal("100"), new BigDecimal("500"));
    when(documentItemMapper.selectList(any())).thenReturn(List.of(item, secondItem));

    LedgerRecord record = new LedgerRecord();
    record.setBatchId("b1");
    record.setSourceDocumentId("d1");
    // 台账采购金额 = 用户确认后的价（此处=明细合计 1500），导出的材料行金额以此为准
    record.setPurchaseAmount(new BigDecimal("1500"));
    record.setSalesAmount(new BigDecimal("2000"));
    record.setReceivedAmount(BigDecimal.ZERO);
    record.setDeliveryAmount(new BigDecimal("1000"));
    record.setStatus("进行中");
    when(ledgerService.getLedgerByProject("u1", "p1")).thenReturn(List.of(record));

    LedgerExportService service = new LedgerExportService(
        businessQueryService, documentService, documentItemMapper, ledgerService,
        costBasisService);

    byte[] content = service.export("u1", "p1", "b1");
    assertTrue(content.length > 0, "导出内容不能为空");

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertEquals("项目批次对账台账", sheet.getSheetName());

      // 三层表头（结构唯一来源 LedgerTableSchema；导出不含页面专属「操作」列）
      assertEquals("项目与批次", cell(sheet, 0, 0));
      assertEquals("业务分类", cell(sheet, 0, 2));
      assertEquals("材料采购 / 运输费用 / 实际送达", cell(sheet, 0, 3));
      assertEquals("采购与付款", cell(sheet, 0, 15));
      assertEquals("销售与收款", cell(sheet, 0, 22));
      assertEquals("状态", cell(sheet, 0, 36));
      assertEquals("材料 / 费用", cell(sheet, 1, 3));
      assertEquals("采购 / 运输金额", cell(sheet, 1, 6));
      assertEquals("采购单数量", cell(sheet, 1, 7));
      assertEquals("送货单数量", cell(sheet, 1, 8));
      assertEquals("送达状态", cell(sheet, 1, 9));
      assertEquals("总额", cell(sheet, 1, 10));
      assertEquals("采购运费成本", cell(sheet, 1, 11));
      assertEquals("销售运费", cell(sheet, 1, 12));
      assertEquals("采购毛利金额", cell(sheet, 1, 13));
      assertEquals("采购毛利率", cell(sheet, 1, 14));
      assertEquals("请款", cell(sheet, 1, 15));
      assertEquals("付款记录", cell(sheet, 1, 16));
      assertEquals("进项发票", cell(sheet, 1, 19));
      assertEquals("销售订单号", cell(sheet, 1, 22));
      assertEquals("销售总金额", cell(sheet, 1, 23));
      assertEquals("销售运费", cell(sheet, 1, 24));
      assertEquals("请款金额", cell(sheet, 2, 15));
      assertEquals("付款金额", cell(sheet, 2, 18));
      assertEquals("开票金额", cell(sheet, 2, 20));
      assertEquals("开票日期", cell(sheet, 2, 25));
      assertEquals("开票金额", cell(sheet, 2, 26));
      assertEquals("税率", cell(sheet, 2, 27));
      assertEquals("已收款金额", cell(sheet, 2, 28));
      assertEquals("未回款金额", cell(sheet, 2, 29));

      // 数据行：材料合并为一行汇总 + 1 条运输行（送货单未标注运费，不进运输费用行）
      Row materialRow = sheet.getRow(3);
      Row transportRow = sheet.getRow(4);
      assertEquals("材料", materialRow.getCell(2).getStringCellValue());
      assertEquals("材料明细（1 大类）", materialRow.getCell(3).getStringCellValue());
      assertEquals("螺纹钢", materialRow.getCell(4).getStringCellValue());
      assertEquals(1500d, materialRow.getCell(6).getNumericCellValue(), 0.001);
      assertTrue(materialRow.getCell(7).getStringCellValue().contains("15"),
          "采购单数量列应汇总两条采购明细的数量");
      assertEquals("运输", transportRow.getCell(2).getStringCellValue());
      assertEquals("运输费用", transportRow.getCell(3).getStringCellValue());
      assertEquals("1 笔", transportRow.getCell(7).getStringCellValue());
      assertEquals(100d, transportRow.getCell(6).getNumericCellValue(), 0.001);
      assertTrue(transportRow.getCell(5).getStringCellValue().contains("物流B"),
          "运输行应保留运输单承运单位");
      assertNull(sheet.getRow(5), "运输行之后不应再有数据行");

      // 总额列：材料 1500 + 运输 100 = 1600，跨材料行与运输行合并
      assertEquals(1600d, sheet.getRow(3).getCell(10).getNumericCellValue(), 0.001);
      assertTrue(sheet.getMergedRegions().stream().anyMatch(region ->
          region.getFirstRow() == 3 && region.getLastRow() == 4
              && region.getFirstColumn() == 10 && region.getLastColumn() == 10), "总额列应纵向合并");

      // 项目/批次与状态列跨两行合并
      assertFalse(sheet.getMergedRegions().isEmpty(), "必须存在合并单元格");
      assertTrue(sheet.getMergedRegions().stream().anyMatch(region ->
          region.getFirstRow() == 3 && region.getLastRow() == 4
              && region.getFirstColumn() == 0 && region.getLastColumn() == 0), "项目列应纵向合并");
      assertTrue(sheet.getMergedRegions().stream().anyMatch(region ->
          region.getFirstRow() == 3 && region.getLastRow() == 4
              && region.getFirstColumn() == 36 && region.getLastColumn() == 36), "状态列应纵向合并");

      // 销售金额写入合并首格（销售区列序：22 销售订单号、23 销售总金额、24 销售运费）
      assertEquals(2000d, sheet.getRow(3).getCell(23).getNumericCellValue(), 0.001);
      assertEquals("待补充", sheet.getRow(3).getCell(36).getStringCellValue());
      // 无采购口径时采购运费成本/采购毛利额/采购毛利率留 "-"；
      // 销售运费两列同理（没有客户承担的运费，与页面一致留 "-"）
      assertEquals("-", cell(sheet, 3, 11));
      assertEquals("-", cell(sheet, 3, 12));
      assertEquals("-", cell(sheet, 3, 24));
      assertEquals("-", cell(sheet, 3, 13));
      assertEquals("-", cell(sheet, 3, 14));
    }
  }

  /** 安全读取单元格文本（POI 空行/空格返回 null 会让断言信息变成裸 NPE）。 */
  private static String cell(Sheet sheet, int rowIdx, int colIdx) {
    Row row = sheet.getRow(rowIdx);
    if (row == null || row.getCell(colIdx) == null) {
      return null;
    }
    return row.getCell(colIdx).getStringCellValue();
  }

  private Document document(String id, String docType, String supplier,
                            BigDecimal amount, LocalDate date) {
    Document doc = new Document();
    doc.setId(id);
    doc.setUserId("u1");
    doc.setProjectId("p1");
    doc.setBatchId("b1");
    doc.setDocType(docType);
    doc.setSupplierName(supplier);
    doc.setAmount(amount);
    doc.setDocDate(date);
    doc.setStatus("matched");
    return doc;
  }

  private DocumentItem item(String documentId, String materialName, String spec,
                            BigDecimal pricingQuantity, String unit,
                            BigDecimal unitPrice, BigDecimal amount) {
    DocumentItem item = new DocumentItem();
    item.setDocumentId(documentId);
    item.setMaterialName(materialName);
    item.setSpec(spec);
    item.setPricingQuantity(pricingQuantity);
    item.setPricingUnit(unit);
    item.setQuantity(pricingQuantity);
    item.setUnit(unit);
    item.setUnitPrice(unitPrice);
    item.setAmount(amount);
    return item;
  }
}
