package com.aibot.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aibot.purchase.dto.BusinessDtos.ProjectDto;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.DocumentItemMarketMapping;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentItemMarketMappingMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.service.storage.FileStorage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;

/**
 * 对账单导出的结构性验证：模板移行、明细行、J 列合并、合计与累计区。
 * 使用 Mockito 构造数据，不依赖数据库。
 */
class ReconciliationExportServiceTest {

  @Test
  void exportsReconciliationWithDetailRowsAndCumulativeSummary() throws Exception {
    BusinessQueryService businessQueryService = mock(BusinessQueryService.class);
    DocumentMapper documentMapper = mock(DocumentMapper.class);
    DocumentItemMapper documentItemMapper = mock(DocumentItemMapper.class);
    DocumentItemMarketMappingMapper itemNetMappingMapper = mock(DocumentItemMarketMappingMapper.class);
    ProjectMapper projectMapper = mock(ProjectMapper.class);
    FileStorage fileStorage = mock(FileStorage.class);

    when(businessQueryService.getProject("u1", "p1")).thenReturn(new ProjectDto(
        "p1", "测试项目", "客户A", "进行中", 0,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, java.util.Map.of(), null, 1));
    Project project = new Project();
    project.setId("p1");
    project.setName("测试项目");
    project.setCustomerName("客户A");
    when(projectMapper.selectById("p1")).thenReturn(project);

    // 范围内送货单（mock 不区分范围与累计两次查询，两批数据一致即可验证结构）。
    // export() 内 documentMapper.selectList 调用顺序固定：范围内送货单 → 累计送货单 → 范围内回款 → 累计回款
    Document d1 = delivery("d1", "SH-001", LocalDate.of(2026, 9, 2), "6000");
    Document d2 = delivery("d2", "SH-002", LocalDate.of(2026, 9, 5), "3200");
    Document receipt = delivery("r1", "SK-001", LocalDate.of(2026, 9, 3), "500");
    receipt.setDocType("receipt");
    when(documentMapper.selectList(any()))
        .thenReturn(List.of(d1, d2))
        .thenReturn(List.of(d1, d2))
        .thenReturn(List.of(receipt))
        .thenReturn(List.of(receipt));

    DocumentItem i1 = item("i1", "d1", "螺纹钢", "HRB400E Φ18",
        "20.16", "3037", "3067", "61681");
    DocumentItem i2 = item("i2", "d1", "螺纹钢", "HRB400E Φ20",
        "10.00", "3037", "3067", "30670");
    DocumentItem i3 = item("i3", "d2", "螺纹钢", "HRB400E Φ22",
        null, null, null, "3200");
    when(documentItemMapper.selectList(any())).thenReturn(List.of(i1, i2, i3));

    DocumentItemMarketMapping m1 = mapping("i1", "20.16", "3037", "3067", "61681");
    when(itemNetMappingMapper.selectList(any())).thenReturn(List.of(m1));

    // d1 带真实 PNG（30x40 竖版），验证等比缩放锚点计算与「每张送货单只插一次」
    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
        30, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(img, "png", bos);
    d1.setFileObjectKey("k-d1");
    when(fileStorage.read("k-d1")).thenReturn(bos.toByteArray());

    ReconciliationExportService service = new ReconciliationExportService(
        businessQueryService, documentMapper, documentItemMapper,
        itemNetMappingMapper, projectMapper, fileStorage);

    byte[] content = service.export("u1", "p1",
        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10));
    assertTrue(content.length > 0, "导出内容不能为空");

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheetAt(0);

      // 抬头与信息行
      assertEquals("客户A（钢筋）对账单", cell(sheet, 0, 0));
      assertEquals("项目名称：测试项目", cell(sheet, 1, 0));
      assertEquals("日期：2026-09-01 至 2026-09-10", cell(sheet, 1, 8));

      // 明细行从模板行 7 开始：3 行明细（d1 两行 + d2 一行）
      assertEquals("螺纹钢", cell(sheet, 6, 1));
      assertEquals("HRB400E Φ18", cell(sheet, 6, 2));
      assertEquals(20.16, Double.parseDouble(cell(sheet, 6, 3)), 1e-6);
      assertEquals(3037.0, Double.parseDouble(cell(sheet, 6, 4)), 1e-6);
      assertEquals(3067.0, Double.parseDouble(cell(sheet, 6, 6)), 1e-6);
      assertEquals("2026-09-02", cell(sheet, 6, 9)); // J 列日期
      assertEquals("", cell(sheet, 6, 10)); // d1 有图片 → K 格留空，图片浮于其上
      assertEquals("HRB400E Φ20", cell(sheet, 7, 2));
      assertEquals("", cell(sheet, 7, 10)); // 非首行不再重复放图/编号
      // d2 无映射锁定 → 网价/上浮写 '/'
      assertEquals("/", cell(sheet, 8, 4));
      assertEquals("SH-002", cell(sheet, 8, 10));

      // 同一送货单 d1 的两行 J 列（列 9）合并
      assertTrue(hasMerge(sheet, 6, 7, 9), "d1 的 J 列应合并");

      // 图片只插一张（d1 首行），不逐行重复（getAllPictures 统计 addPicture 次数）
      assertEquals(1, workbook.getAllPictures().size(), "发货单图片应只放一次");

      // 合计行（模板行 8 下移 3 → 行 11）：数量 30.16、金额 95551（61681+30670+3200，d2 回退明细金额）
      assertEquals(30.16, sheet.getRow(10).getCell(3).getNumericCellValue(), 1e-6);
      assertEquals(95551.0, sheet.getRow(10).getCell(8).getNumericCellValue(), 1e-6);

      // 累计区：行 13 本次供应量 / 行 14 本次供货金额 / 行 15 本次支付
      assertEquals(30.16, sheet.getRow(12).getCell(2).getNumericCellValue(), 1e-6);
      assertEquals(95551.0, sheet.getRow(13).getCell(2).getNumericCellValue(), 1e-6);
      // 本次/累计支付 = 回款单金额（mock 两次查询同返回 500）
      assertEquals(500.0, sheet.getRow(14).getCell(2).getNumericCellValue(), 1e-6);
      assertEquals(500.0, sheet.getRow(14).getCell(9).getNumericCellValue(), 1e-6);

      // 尾部注记行整体下移：模板行 20 → 行 23
      assertTrue(cell(sheet, 22, 0).startsWith("注："), "签章区应整体下移");
    }
  }

  // ---------------------------------------------------------------------------
  // 测试数据构造
  // ---------------------------------------------------------------------------

  private Document delivery(String id, String docCode, LocalDate date, String amount) {
    Document doc = new Document();
    doc.setId(id);
    doc.setProjectId("p1");
    doc.setDocType("delivery_note");
    doc.setStatus("matched");
    doc.setLedgerApplied(true);
    doc.setDocCode(docCode);
    doc.setDocDate(date);
    doc.setAmount(new BigDecimal(amount));
    return doc;
  }

  private DocumentItem item(String id, String documentId, String name, String spec,
      String qty, String netPrice, String unitPrice, String amount) {
    DocumentItem item = new DocumentItem();
    item.setId(id);
    item.setDocumentId(documentId);
    item.setMaterialName(name);
    item.setSpec(spec);
    if (qty != null) {
      item.setPricingQuantity(new BigDecimal(qty));
    }
    item.setPricingUnit("吨");
    if (netPrice != null) {
      item.setNetPrice(new BigDecimal(netPrice));
    }
    if (unitPrice != null) {
      item.setUnitPrice(new BigDecimal(unitPrice));
    }
    item.setAmount(new BigDecimal(amount));
    item.setSortOrder(0);
    return item;
  }

  private DocumentItemMarketMapping mapping(String itemId, String qty,
      String quotePrice, String salesUnitPrice, String amount) {
    DocumentItemMarketMapping mapping = new DocumentItemMarketMapping();
    mapping.setDocumentItemId(itemId);
    mapping.setPriceBasis("quote");
    mapping.setQuotePrice(new BigDecimal(quotePrice));
    mapping.setUplift(new BigDecimal("30"));
    mapping.setSalesUnitPrice(new BigDecimal(salesUnitPrice));
    mapping.setPricingQuantity(new BigDecimal(qty));
    mapping.setPricingUnit("吨");
    mapping.setAmount(new BigDecimal(amount));
    return mapping;
  }

  private static String cell(Sheet sheet, int rowIdx, int colIdx) {
    Row row = sheet.getRow(rowIdx);
    if (row == null || row.getCell(colIdx) == null) {
      return "";
    }
    return new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(row.getCell(colIdx));
  }

  private static boolean hasMerge(Sheet sheet, int firstRow, int lastRow, int col) {
    for (CellRangeAddress region : sheet.getMergedRegions()) {
      if (region.getFirstRow() == firstRow && region.getLastRow() == lastRow
          && region.getFirstColumn() == col && region.getLastColumn() == col) {
        return true;
      }
    }
    return false;
  }
}
