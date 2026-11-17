package com.aibot.purchase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aibot.purchase.dto.BusinessDtos.ProjectDto;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.DocumentItemMarketMapping;
import com.aibot.purchase.entity.FreightItem;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.entity.ProjectReceipt;
import com.aibot.purchase.entity.ProjectReceiptAllocation;
import com.aibot.purchase.entity.User;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentItemMarketMappingMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.FreightItemMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.mapper.ProjectReceiptAllocationMapper;
import com.aibot.purchase.mapper.ProjectReceiptMapper;
import com.aibot.purchase.mapper.UserMapper;
import com.aibot.purchase.service.market.MaterialIdentity;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 对账单导出（2026-11-17 客户钢材对账单样式）：
 * 列布局、名称/规格拆分、日期首行、备注写产地、运费独立行、按批次材料筛选、累计与回款同口径。
 */
class ReconciliationExportServiceTest {

  private BusinessQueryService businessQueryService;
  private DocumentMapper documentMapper;
  private DocumentItemMapper documentItemMapper;
  private DocumentItemMarketMappingMapper itemNetMappingMapper;
  private ProjectMapper projectMapper;
  private ProjectReceiptMapper receiptMapper;
  private ProjectReceiptAllocationMapper allocationMapper;
  private BatchMapper batchMapper;
  private FreightItemMapper freightItemMapper;
  private UserMapper userMapper;
  private ReconciliationExportService service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    assistant.setCurrentNamespace("ReconciliationExportServiceTest");
    for (Class<?> entity : List.of(Document.class, DocumentItem.class, DocumentItemMarketMapping.class,
        Batch.class, FreightItem.class, ProjectReceipt.class, ProjectReceiptAllocation.class)) {
      TableInfoHelper.initTableInfo(assistant, entity);
    }
  }

  @BeforeEach
  void setUp() {
    businessQueryService = mock(BusinessQueryService.class);
    documentMapper = mock(DocumentMapper.class);
    documentItemMapper = mock(DocumentItemMapper.class);
    itemNetMappingMapper = mock(DocumentItemMarketMappingMapper.class);
    projectMapper = mock(ProjectMapper.class);
    receiptMapper = mock(ProjectReceiptMapper.class);
    allocationMapper = mock(ProjectReceiptAllocationMapper.class);
    batchMapper = mock(BatchMapper.class);
    freightItemMapper = mock(FreightItemMapper.class);
    userMapper = mock(UserMapper.class);
    service = new ReconciliationExportService(businessQueryService, documentMapper, documentItemMapper,
        itemNetMappingMapper, projectMapper, receiptMapper, allocationMapper, batchMapper,
        freightItemMapper, userMapper);

    when(businessQueryService.getProject("u1", "p1")).thenReturn(new ProjectDto(
        "p1", "天池禅寺复建项目", "浙江众安建设集团有限公司", "进行中", 0,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, java.util.Map.of(), null, 1));
    Project project = new Project();
    project.setId("p1");
    project.setName("天池禅寺复建项目");
    project.setCustomerName("浙江众安建设集团有限公司");
    when(projectMapper.selectById("p1")).thenReturn(project);
    User user = new User();
    user.setCompanyName("杭州葭安科技有限公司");
    when(userMapper.selectById("u1")).thenReturn(user);
  }

  @Test
  void exportsCustomerStyleWithFreightRowsDateOnFirstRowAndOriginRemark() throws Exception {
    Document d1 = delivery("d1", "b1", LocalDate.of(2025, 12, 30));
    Document d2 = delivery("d2", "b1", LocalDate.of(2026, 1, 7));
    // 累计查询（≤ end）返回两张，范围内同样两张
    when(documentMapper.selectList(any())).thenReturn(List.of(d1, d2));
    DocumentItem i1 = item("i1", "d1", "螺纹钢", "HRB400E 18*9", "中天", "8.208", "27086.40");
    DocumentItem i2 = item("i2", "d1", "螺纹钢", "HRB400E 22*9", "芜湖富鑫", "7.940", "26122.60");
    DocumentItem i3 = item("i3", "d2", "螺纹钢HRB400E", "25*9", "西城", "21.834", "73362.24");
    when(documentItemMapper.selectList(any())).thenReturn(List.of(i1, i2, i3));
    when(itemNetMappingMapper.selectList(any())).thenReturn(List.of(
        mapping("i1", "8.208", "3280", "20", "3300", "27086.40"),
        mapping("i2", "7.940", "3270", "20", "3290", "26122.60"),
        mapping("i3", "21.834", "3340", "20", "3360", "73362.24")));
    // 每张送货单写明的销售运费 1900 → 各自一行「运费」
    when(freightItemMapper.selectList(any())).thenReturn(List.of(
        salesFreight("b1", "d1", "1900"), salesFreight("b1", "d2", "1900")));
    ProjectReceipt receipt = new ProjectReceipt();
    receipt.setId("r1");
    receipt.setAmount(new BigDecimal("50000"));
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt));

    byte[] content = service.export("u1", "p1", LocalDate.of(2025, 12, 1), LocalDate.of(2026, 9, 30));

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertEquals("浙江众安建设集团有限公司钢材对账单", cell(sheet, 0, 0));
      assertEquals("项目名称：天池禅寺复建项目", cell(sheet, 1, 0));
      assertEquals("日期：2025年12月1日——2026年9月30日", cell(sheet, 1, 5));
      assertEquals("无合同", cell(sheet, 2, 2));
      assertEquals("杭州葭安科技有限公司", cell(sheet, 3, 2));
      assertEquals("序号", cell(sheet, 5, 0));
      assertEquals("钢材名称", cell(sheet, 5, 1));
      assertEquals("备注", cell(sheet, 5, 9));

      // 行 7 起：d1 两行材料 + 运费；d2 一行材料 + 运费
      assertEquals("1", cell(sheet, 6, 0));
      assertEquals("螺纹钢\nHRB400E", cell(sheet, 6, 1));
      assertEquals("18*9", cell(sheet, 6, 2));
      assertEquals(8.208, number(sheet, 6, 3), 1e-6);
      assertEquals(3280.0, number(sheet, 6, 4), 1e-6);
      assertEquals(20.0, number(sheet, 6, 5), 1e-6);
      assertEquals(3300.0, number(sheet, 6, 6), 1e-6);
      assertEquals(27086.40, number(sheet, 6, 7), 1e-6);
      assertEquals("12月30日", cell(sheet, 6, 8));
      assertEquals("中天", cell(sheet, 6, 9));

      assertEquals("22*9", cell(sheet, 7, 2));
      assertEquals("", cell(sheet, 7, 8), "同一天只在首行写日期");
      assertEquals("芜湖富鑫", cell(sheet, 7, 9));

      assertEquals("运费", cell(sheet, 8, 1));
      assertEquals("", cell(sheet, 8, 3));
      assertEquals(1900.0, number(sheet, 8, 7), 1e-6);

      assertEquals("螺纹钢\nHRB400E", cell(sheet, 9, 1), "名称含牌号时也能拆出大类与牌号");
      assertEquals("25*9", cell(sheet, 9, 2));
      assertEquals("1月7日", cell(sheet, 9, 8));
      assertEquals("西城", cell(sheet, 9, 9));
      assertEquals("运费", cell(sheet, 10, 1));

      // 不再插入送货单图片
      assertEquals(0, workbook.getAllPictures().size());

      // 合计（模板第 8 行，下移 4 行 → 第 12 行）：数量只算材料，金额含运费
      assertEquals("合计：", cell(sheet, 11, 0));
      assertEquals(37.982, number(sheet, 11, 3), 1e-6);
      assertEquals(130371.24, number(sheet, 11, 7), 1e-6);
      // 本次 / 累计（C 列 / H 列）
      assertEquals(37.982, number(sheet, 13, 2), 1e-6);
      assertEquals(130371.24, number(sheet, 14, 2), 1e-6);
      assertEquals(130371.24, number(sheet, 14, 7), 1e-6);
      assertEquals(50000.0, number(sheet, 15, 2), 1e-6);
      assertEquals(50000.0, number(sheet, 15, 7), 1e-6);
      assertTrue(cell(sheet, 23, 0).startsWith("注："), "签章区整体下移");
    }
  }

  @Test
  void materialFilterOnlyUsesMatchingBatchesForDetailsCumulativeAndReceipts() throws Exception {
    Batch rebar = batch("b1", "螺纹钢");
    Batch coil = batch("b2", "盘螺");
    when(batchMapper.selectList(any())).thenReturn(List.of(rebar, coil));
    Document d1 = delivery("d1", "b1", LocalDate.of(2026, 1, 7));
    when(documentMapper.selectList(any())).thenReturn(List.of(d1));
    DocumentItem i1 = item("i1", "d1", "螺纹钢", "HRB400E 25*9", "西城", "10", "33600");
    when(documentItemMapper.selectList(any())).thenReturn(List.of(i1));
    when(itemNetMappingMapper.selectList(any())).thenReturn(List.of());
    when(freightItemMapper.selectList(any())).thenReturn(List.of());
    ProjectReceipt receipt = new ProjectReceipt();
    receipt.setId("r1");
    receipt.setAmount(new BigDecimal("80000"));
    when(receiptMapper.selectList(any())).thenReturn(List.of(receipt));
    ProjectReceiptAllocation allocation = new ProjectReceiptAllocation();
    allocation.setReceiptId("r1");
    allocation.setBatchId("b1");
    allocation.setAmount(new BigDecimal("30000"));
    when(allocationMapper.selectList(any())).thenReturn(List.of(allocation));

    byte[] content = service.export("u1", "p1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "螺纹钢");

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertEquals("材料明细（螺纹钢）", cell(sheet, 4, 0));
      assertEquals("螺纹钢\nHRB400E", cell(sheet, 6, 1));
      assertEquals("/", cell(sheet, 6, 4), "无锁定网价写 /");
      // 单行明细不下移：合计在第 8 行；本次支付只取分摊到螺纹钢批次的 30000，而不是整笔 80000
      assertEquals(33600.0, number(sheet, 7, 7), 1e-6);
      assertEquals(30000.0, number(sheet, 11, 2), 1e-6);
    }
    assertEquals("对账单-天池禅寺复建项目-螺纹钢-2026-01-01_2026-01-31.xlsx",
        service.fileName("u1", "p1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "螺纹钢"));
  }

  @Test
  void unknownMaterialExportsEmptyStatementWithoutQueryingDeliveries() throws Exception {
    when(batchMapper.selectList(any())).thenReturn(List.of(batch("b1", "螺纹钢")));
    when(receiptMapper.selectList(any())).thenReturn(List.of());

    byte[] content = service.export("u1", "p1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "水泥");

    verify(documentMapper, never()).selectList(any());
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
      Sheet sheet = workbook.getSheetAt(0);
      assertEquals("合计：", cell(sheet, 7, 0));
      assertEquals(0.0, number(sheet, 7, 7), 1e-6);
    }
  }

  @Test
  void materialOptionsAreDistinctBatchMaterials() {
    when(batchMapper.selectList(any())).thenReturn(List.of(
        batch("b1", "螺纹钢"), batch("b2", " 盘螺 "), batch("b3", "螺纹钢"), batch("b4", null)));
    assertEquals(List.of("螺纹钢", "盘螺"), service.materialOptions("u1", "p1"));
  }

  @Test
  void tripFreightOnlyAppearsOnFirstDeliveryOfBatch() {
    Document d1 = delivery("d1", "b1", LocalDate.of(2026, 1, 7));
    Document d2 = delivery("d2", "b1", LocalDate.of(2026, 1, 9));
    when(documentItemMapper.selectList(any())).thenReturn(List.of(
        item("i1", "d1", "盘螺", "HRB400E 10", null, "9.92", "35216"),
        item("i2", "d2", "盘螺", "HRB400E 8", null, "5", "17750")));
    when(itemNetMappingMapper.selectList(any())).thenReturn(List.of());
    FreightItem trip = new FreightItem();
    trip.setBatchId("b1");
    trip.setSide(FreightService.SIDE_SALES);
    trip.setSource(FreightService.SOURCE_POLICY_TRIP);
    trip.setAmount(new BigDecimal("3800"));
    when(freightItemMapper.selectList(any())).thenReturn(List.of(trip));

    var lines = service.buildLines(List.of(d1, d2), java.util.Map.of("b1", "d1"));
    assertEquals(3, lines.size());
    assertEquals("运费", lines.get(1).name());
    assertEquals(0, new BigDecimal("3800").compareTo(lines.get(1).amount()));
    assertEquals("10", lines.get(0).spec(), "盘螺无定尺只写直径");
    // 只导出第二张（范围不含 d1）时不重复计包干运费
    var later = service.buildLines(List.of(d2), java.util.Map.of("b1", "d1"));
    assertEquals(1, later.size());
  }

  @Test
  void nameAndSpecFallBackToOriginalTextForNonSteel() {
    DocumentItem cement = item("i9", "d9", "水泥 P.O42.5", "50kg/袋", null, "10", "4500");
    MaterialIdentity.Identity identity = MaterialIdentity.parse(
        cement.getMaterialName(), cement.getSpec(), null);
    assertTrue(ReconciliationExportService.materialName(cement, identity).startsWith("水泥"));
  }

  // ---------------------------------------------------------------------------

  private Document delivery(String id, String batchId, LocalDate date) {
    Document doc = new Document();
    doc.setId(id);
    doc.setProjectId("p1");
    doc.setBatchId(batchId);
    doc.setDocType("delivery_note");
    doc.setStatus("matched");
    doc.setLedgerApplied(true);
    doc.setDocDate(date);
    return doc;
  }

  private Batch batch(String id, String material) {
    Batch batch = new Batch();
    batch.setId(id);
    batch.setProjectId("p1");
    batch.setMaterialSummary(material);
    return batch;
  }

  private DocumentItem item(String id, String documentId, String name, String spec, String origin,
      String qty, String amount) {
    DocumentItem item = new DocumentItem();
    item.setId(id);
    item.setDocumentId(documentId);
    item.setMaterialName(name);
    item.setSpec(spec);
    item.setOrigin(origin);
    item.setPricingQuantity(new BigDecimal(qty));
    item.setPricingUnit("吨");
    item.setAmount(new BigDecimal(amount));
    item.setSortOrder(0);
    return item;
  }

  private DocumentItemMarketMapping mapping(String itemId, String qty, String quote, String uplift,
      String price, String amount) {
    DocumentItemMarketMapping mapping = new DocumentItemMarketMapping();
    mapping.setDocumentItemId(itemId);
    mapping.setPriceBasis("quote");
    mapping.setQuotePrice(new BigDecimal(quote));
    mapping.setUplift(new BigDecimal(uplift));
    mapping.setSalesUnitPrice(new BigDecimal(price));
    mapping.setPricingQuantity(new BigDecimal(qty));
    mapping.setPricingUnit("吨");
    mapping.setAmount(new BigDecimal(amount));
    return mapping;
  }

  private FreightItem salesFreight(String batchId, String documentId, String amount) {
    FreightItem item = new FreightItem();
    item.setBatchId(batchId);
    item.setSide(FreightService.SIDE_SALES);
    item.setSource(FreightService.SOURCE_DOCUMENT_STATED);
    item.setSourceDocumentId(documentId);
    item.setAmount(new BigDecimal(amount));
    return item;
  }

  private static String cell(Sheet sheet, int rowIdx, int colIdx) {
    Row row = sheet.getRow(rowIdx);
    if (row == null || row.getCell(colIdx) == null) {
      return "";
    }
    return new DataFormatter().formatCellValue(row.getCell(colIdx));
  }

  private static double number(Sheet sheet, int rowIdx, int colIdx) {
    return sheet.getRow(rowIdx).getCell(colIdx).getNumericCellValue();
  }
}
