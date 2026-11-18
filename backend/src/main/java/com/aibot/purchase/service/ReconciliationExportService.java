package com.aibot.purchase.service;

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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 对账单导出（销售侧，模板：resources/对账单-模板.xlsx，2026-11-17 改为客户钢材对账单样式）。
 *
 * <p>筛选口径：
 * <ul>
 *   <li>明细 = 项目内送货单日期 ∈ [start, end] 的已确认、已入台账送货单；</li>
 *   <li>传 material 时只取「批次材料」（新建批次时填写的材料）等于该值的批次，
 *       累计供应量/供货金额与本次/累计支付同样只统计这些批次；</li>
 *   <li>已删除批次的单据不导出。</li>
 * </ul>
 *
 * <p>样式（对齐客户样表）：序号 / 钢材名称（大类 + 牌号）/ 规格型号（直径*定尺）/ 数量（吨）/
 * 网价 / 上浮额 / 计量单价 / 小计 / 日期（同一天只在首行写）/ 备注（产地或品牌，不再插送货单图片）。
 * 客户承担的销售运费单独成「运费」行，金额计入小计与合计，不计入数量。
 */
@Service
public class ReconciliationExportService {

  /** 模板布局（1-based 行号） */
  private static final int TPL_DETAIL_ROW = 7;
  private static final int TPL_TOTAL_ROW = 8;
  private static final int TPL_TAIL_LAST = 20;
  private static final int COL_COUNT = 10;
  private static final float DETAIL_ROW_HEIGHT = 32f;

  private static final int COL_SEQ = 0;
  private static final int COL_NAME = 1;
  private static final int COL_SPEC = 2;
  private static final int COL_QTY = 3;
  private static final int COL_QUOTE = 4;
  private static final int COL_UPLIFT = 5;
  private static final int COL_PRICE = 6;
  private static final int COL_AMOUNT = 7;
  private static final int COL_DATE = 8;
  private static final int COL_REMARK = 9;

  private static final String FREIGHT_LABEL = "运费";

  private final BusinessQueryService businessQueryService;
  private final DocumentMapper documentMapper;
  private final DocumentItemMapper documentItemMapper;
  private final DocumentItemMarketMappingMapper itemNetMappingMapper;
  private final ProjectMapper projectMapper;
  private final ProjectReceiptMapper receiptMapper;
  private final ProjectReceiptAllocationMapper allocationMapper;
  private final BatchMapper batchMapper;
  private final FreightItemMapper freightItemMapper;
  private final UserMapper userMapper;

  public ReconciliationExportService(
      BusinessQueryService businessQueryService,
      DocumentMapper documentMapper,
      DocumentItemMapper documentItemMapper,
      DocumentItemMarketMappingMapper itemNetMappingMapper,
      ProjectMapper projectMapper,
      ProjectReceiptMapper receiptMapper,
      ProjectReceiptAllocationMapper allocationMapper,
      BatchMapper batchMapper,
      FreightItemMapper freightItemMapper,
      UserMapper userMapper
  ) {
    this.businessQueryService = businessQueryService;
    this.documentMapper = documentMapper;
    this.documentItemMapper = documentItemMapper;
    this.itemNetMappingMapper = itemNetMappingMapper;
    this.projectMapper = projectMapper;
    this.receiptMapper = receiptMapper;
    this.allocationMapper = allocationMapper;
    this.batchMapper = batchMapper;
    this.freightItemMapper = freightItemMapper;
    this.userMapper = userMapper;
  }

  /** 一行对账明细：材料行或运费行。 */
  record Line(
      Document document,
      boolean freight,
      String name,
      String spec,
      BigDecimal quantity,
      Object quotePrice,
      Object uplift,
      Object unitPrice,
      BigDecimal amount,
      String remark
  ) {}

  /** 导出范围：项目 + 可选材料（批次材料） + 日期。 */
  private record Scope(String projectId, String material, Set<String> batchIds) {
    boolean filtered() {
      return StringUtils.hasText(material);
    }
  }

  // ---------------------------------------------------------------------------
  // 对外入口
  // ---------------------------------------------------------------------------

  public byte[] export(String userId, String projectId, LocalDate start, LocalDate end) {
    return export(userId, projectId, start, end, null);
  }

  public byte[] export(String userId, String projectId, LocalDate start, LocalDate end, String material) {
    Project project = loadOwnedProject(userId, projectId);
    Scope scope = scope(projectId, material);
    List<Document> cumulativeDeliveries = listDeliveries(scope, null, end);
    List<Document> rangeDeliveries = inRange(cumulativeDeliveries, start, end);
    Map<String, String> freightAnchors = tripFreightAnchors(cumulativeDeliveries);
    List<Line> rangeLines = buildLines(rangeDeliveries, freightAnchors);
    List<Line> cumulativeLines = buildLines(cumulativeDeliveries, freightAnchors);
    BigDecimal rangePaid = sumReceipts(scope, start, end);
    BigDecimal cumulativePaid = sumReceipts(scope, null, end);

    try (InputStream in = new ClassPathResource("对账单-模板.xlsx").getInputStream();
         Workbook workbook = WorkbookFactory.create(in);
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.getSheetAt(0);
      writeHeader(sheet, project, supplierName(userId), start, end, scope.material());
      int offset = writeDetailRows(sheet, rangeLines);
      writeTotals(sheet, offset, rangeLines);
      writeSummary(sheet, offset,
          sumQuantity(rangeLines), sumAmount(rangeLines),
          sumQuantity(cumulativeLines), sumAmount(cumulativeLines),
          rangePaid, cumulativePaid);
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception ex) {
      throw new IllegalStateException("生成对账单导出文件失败：" + ex.getMessage(), ex);
    }
  }

  public String fileName(String userId, String projectId, LocalDate start, LocalDate end) {
    return fileName(userId, projectId, start, end, null);
  }

  public String fileName(String userId, String projectId, LocalDate start, LocalDate end, String material) {
    Project project = loadOwnedProject(userId, projectId);
    String name = StringUtils.hasText(project.getName()) ? project.getName() : "项目";
    String materialPart = StringUtils.hasText(material) ? "-" + material.trim() : "";
    return "对账单-" + name + materialPart + "-" + start + "_" + end + ".xlsx";
  }

  /**
   * 可选材料：项目内有效批次填写的材料（去重，按首次出现顺序）。
   * 前端导出弹框/聊天卡片据此渲染「材料」下拉，空值批次不列出。
   */
  public List<String> materialOptions(String userId, String projectId) {
    loadOwnedProject(userId, projectId);
    return batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
            .eq(Batch::getProjectId, projectId)
            .orderByAsc(Batch::getBusinessDate)
            .orderByAsc(Batch::getCreatedAt))
        .stream()
        .map(Batch::getMaterialSummary)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .collect(Collectors.toCollection(LinkedHashSet::new))
        .stream()
        .toList();
  }

  private Project loadOwnedProject(String userId, String projectId) {
    // 复用台账导出的属主校验：非本人项目直接抛错
    businessQueryService.getProject(userId, projectId);
    Project project = projectMapper.selectById(projectId);
    if (project == null) {
      throw new IllegalStateException("项目不存在：" + projectId);
    }
    return project;
  }

  /** 范围汇总（卡片展示用）：范围内送货单张数 + 对账金额（货款 + 客户承担运费，与导出小计合计一致） */
  public record RangeSummary(int deliveries, BigDecimal salesAmount) {}

  /** 卡片实时汇总视图：项目/客户信息 + 指定日期范围（可选材料）的张数与对账金额 */
  public Map<String, Object> summaryView(String userId, String projectId, LocalDate start, LocalDate end) {
    return summaryView(userId, projectId, start, end, null);
  }

  public Map<String, Object> summaryView(
      String userId, String projectId, LocalDate start, LocalDate end, String material) {
    Project project = loadOwnedProject(userId, projectId);
    RangeSummary summary = rangeSummary(userId, projectId, start, end, material);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("projectName", nzStr(project.getName()));
    view.put("customerName", nzStr(project.getCustomerName()));
    view.put("material", StringUtils.hasText(material) ? material.trim() : null);
    view.put("deliveries", summary.deliveries());
    view.put("salesAmount", summary.salesAmount());
    return view;
  }

  public RangeSummary rangeSummary(String userId, String projectId, LocalDate start, LocalDate end) {
    return rangeSummary(userId, projectId, start, end, null);
  }

  public RangeSummary rangeSummary(
      String userId, String projectId, LocalDate start, LocalDate end, String material) {
    loadOwnedProject(userId, projectId);
    Scope scope = scope(projectId, material);
    List<Document> cumulative = listDeliveries(scope, null, end);
    List<Document> range = inRange(cumulative, start, end);
    List<Line> lines = buildLines(range, tripFreightAnchors(cumulative));
    return new RangeSummary(range.size(), sumAmount(lines));
  }

  // ---------------------------------------------------------------------------
  // 数据装载
  // ---------------------------------------------------------------------------

  /**
   * 排除归属批次已软删的单据；batch_id 为空（项目级单据）不受影响。
   * 批次软删不回写单据（BusinessQueryService.deleteBatch 只置 batches.deleted），
   * 导出侧必须自行过滤，否则已删批次的送货单仍会进对账单。
   */
  private static final String EXCLUDE_DELETED_BATCH =
      "select 1 from batches b where b.id = documents.batch_id and b.deleted = 1";

  private Scope scope(String projectId, String material) {
    if (!StringUtils.hasText(material)) {
      return new Scope(projectId, null, Set.of());
    }
    String wanted = material.trim();
    Set<String> batchIds = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
            .eq(Batch::getProjectId, projectId))
        .stream()
        .filter(batch -> StringUtils.hasText(batch.getMaterialSummary())
            && wanted.equals(batch.getMaterialSummary().trim()))
        .map(Batch::getId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return new Scope(projectId, wanted, batchIds);
  }

  /** 截至 end（含）的已确认送货单，按日期、创建时间排序；材料筛选时限定批次。 */
  private List<Document> listDeliveries(Scope scope, LocalDate start, LocalDate end) {
    if (scope.filtered() && scope.batchIds().isEmpty()) {
      return new ArrayList<>();
    }
    return new ArrayList<>(documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getProjectId, scope.projectId())
        .eq(Document::getDocType, "delivery_note")
        .eq(Document::getStatus, "matched")
        .eq(Document::getLedgerApplied, true)
        .in(scope.filtered(), Document::getBatchId, scope.batchIds())
        .notExists(EXCLUDE_DELETED_BATCH)
        .le(end != null, Document::getDocDate, end)
        .ge(start != null, Document::getDocDate, start)
        .orderByAsc(Document::getDocDate)
        .orderByAsc(Document::getCreatedAt)));
  }

  private static List<Document> inRange(List<Document> deliveries, LocalDate start, LocalDate end) {
    return deliveries.stream()
        .filter(document -> document.getDocDate() != null)
        .filter(document -> start == null || !document.getDocDate().isBefore(start))
        .filter(document -> end == null || !document.getDocDate().isAfter(end))
        .toList();
  }

  /**
   * 回款：不筛材料时取项目收款合计（含未落到批次上的部分，与项目汇总一致）；
   * 筛材料时只取分摊到这些批次上的金额，按收款日期统计。
   */
  private BigDecimal sumReceipts(Scope scope, LocalDate start, LocalDate end) {
    List<ProjectReceipt> receipts = receiptMapper.selectList(Wrappers.<ProjectReceipt>lambdaQuery()
        .eq(ProjectReceipt::getProjectId, scope.projectId())
        .le(end != null, ProjectReceipt::getReceiptDate, end)
        .ge(start != null, ProjectReceipt::getReceiptDate, start));
    if (!scope.filtered()) {
      return receipts.stream()
          .map(ProjectReceipt::getAmount)
          .map(ReconciliationExportService::nz)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    if (receipts.isEmpty() || scope.batchIds().isEmpty()) {
      return BigDecimal.ZERO;
    }
    return allocationMapper.selectList(Wrappers.<ProjectReceiptAllocation>lambdaQuery()
            .in(ProjectReceiptAllocation::getReceiptId,
                receipts.stream().map(ProjectReceipt::getId).toList())
            .in(ProjectReceiptAllocation::getBatchId, scope.batchIds()))
        .stream()
        .map(ProjectReceiptAllocation::getAmount)
        .map(ReconciliationExportService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * 按趟包干的销售运费是批次级的一笔钱：挂在该批次（截至 end）最早的一张送货单后面，
   * 导出范围包含那张送货单才出现，避免分段导出时每段都重复计一次。
   */
  private static Map<String, String> tripFreightAnchors(List<Document> cumulative) {
    Map<String, String> anchors = new HashMap<>();
    for (Document document : cumulative) {
      if (StringUtils.hasText(document.getBatchId())) {
        anchors.putIfAbsent(document.getBatchId(), document.getId());
      }
    }
    return anchors;
  }

  /** 送货单 → 对账行：每张送货单的材料行，之后紧跟该单对应的运费行。 */
  List<Line> buildLines(List<Document> deliveries, Map<String, String> tripAnchors) {
    if (deliveries.isEmpty()) {
      return new ArrayList<>();
    }
    List<String> documentIds = deliveries.stream().map(Document::getId).toList();
    Map<String, List<DocumentItem>> itemsByDoc = new HashMap<>();
    for (DocumentItem item : documentItemMapper.selectList(
        Wrappers.<DocumentItem>lambdaQuery()
            .in(DocumentItem::getDocumentId, documentIds)
            .orderByAsc(DocumentItem::getSortOrder))) {
      itemsByDoc.computeIfAbsent(item.getDocumentId(), k -> new ArrayList<>()).add(item);
    }
    Map<String, DocumentItemMarketMapping> mappingByItem = new HashMap<>();
    for (DocumentItemMarketMapping mapping : itemNetMappingMapper.selectList(
        Wrappers.<DocumentItemMarketMapping>lambdaQuery()
            .in(DocumentItemMarketMapping::getDocumentId, documentIds))) {
      mappingByItem.put(mapping.getDocumentItemId(), mapping);
    }
    Map<String, List<FreightItem>> freightByBatch = salesFreightByBatch(deliveries);

    List<Line> lines = new ArrayList<>();
    for (Document document : deliveries) {
      List<DocumentItem> items = itemsByDoc.getOrDefault(document.getId(), List.of());
      for (DocumentItem item : items) {
        lines.add(materialLine(document, item, mappingByItem.get(item.getId())));
      }
      BigDecimal freight = deliveryFreight(document, items, mappingByItem,
          freightByBatch.getOrDefault(document.getBatchId(), List.of()), tripAnchors);
      if (freight.signum() > 0) {
        lines.add(new Line(document, true, FREIGHT_LABEL, null, null, null, null, null,
            freight, null));
      }
    }
    return lines;
  }

  private Map<String, List<FreightItem>> salesFreightByBatch(List<Document> deliveries) {
    Set<String> batchIds = deliveries.stream()
        .map(Document::getBatchId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    if (batchIds.isEmpty()) {
      return Map.of();
    }
    return freightItemMapper.selectList(Wrappers.<FreightItem>lambdaQuery()
            .in(FreightItem::getBatchId, batchIds)
            .eq(FreightItem::getSide, FreightService.SIDE_SALES))
        .stream()
        .collect(Collectors.groupingBy(FreightItem::getBatchId));
  }

  /**
   * 一张送货单对应的销售运费（客户承担）：
   * 单据写明的运费归本单；按吨口径 = 本单吨数 × 单价；按趟口径只挂批次最早的那张送货单。
   */
  private BigDecimal deliveryFreight(
      Document document,
      List<DocumentItem> items,
      Map<String, DocumentItemMarketMapping> mappingByItem,
      List<FreightItem> batchFreight,
      Map<String, String> tripAnchors
  ) {
    BigDecimal total = BigDecimal.ZERO;
    for (FreightItem freight : batchFreight) {
      String source = freight.getSource();
      if (FreightService.SOURCE_DOCUMENT_STATED.equals(source)
          && Objects.equals(freight.getSourceDocumentId(), document.getId())) {
        total = total.add(nz(freight.getAmount()));
      } else if (FreightService.SOURCE_POLICY_TON.equals(source) && freight.getUnitPrice() != null) {
        BigDecimal tons = items.stream()
            .map(item -> tonnage(item, mappingByItem.get(item.getId())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        total = total.add(tons.multiply(freight.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
      } else if (FreightService.SOURCE_POLICY_TRIP.equals(source)
          && document.getId().equals(tripAnchors.get(document.getBatchId()))) {
        total = total.add(nz(freight.getAmount()));
      }
    }
    return total;
  }

  private Line materialLine(Document document, DocumentItem item, DocumentItemMarketMapping mapping) {
    MaterialIdentity.Identity identity =
        MaterialIdentity.parse(item.getMaterialName(), item.getSpec(), item.getOrigin());
    BigDecimal quantity = mapping != null && mapping.getPricingQuantity() != null
        ? mapping.getPricingQuantity()
        : item.getPricingQuantity();
    Object unitPrice = mapping != null && mapping.getSalesUnitPrice() != null
        ? mapping.getSalesUnitPrice()
        : (item.getUnitPrice() != null ? item.getUnitPrice() : "/");
    BigDecimal amount = mapping != null && mapping.getAmount() != null
        ? mapping.getAmount()
        : item.getAmount();
    return new Line(document, false,
        materialName(item, identity),
        specText(item, identity),
        quantity,
        priceValue(mapping == null ? null : mapping.getQuotePrice()),
        priceValue(mapping == null ? null : mapping.getUplift()),
        unitPrice,
        amount,
        StringUtils.hasText(item.getOrigin()) ? item.getOrigin().trim() : null);
  }

  /** 钢材名称：大类 + 换行 + 牌号（如「螺纹钢\nHRB400E」）；识别不出大类回退原名。 */
  static String materialName(DocumentItem item, MaterialIdentity.Identity identity) {
    String category = identity.category();
    String name = "未标注材料".equals(category)
        ? nzStr(item.getMaterialName()).trim()
        : category;
    return identity.hasGrade() ? name + "\n" + identity.grade() : name;
  }

  /** 规格型号：直径*定尺（如「18*9」），盘螺等无定尺只写直径；解析不出回退原规格。 */
  static String specText(DocumentItem item, MaterialIdentity.Identity identity) {
    if (StringUtils.hasText(identity.diameter())) {
      return StringUtils.hasText(identity.lengthM())
          ? identity.diameter() + "*" + identity.lengthM()
          : identity.diameter();
    }
    return nzStr(item.getSpec()).trim();
  }

  /** 明细吨数（按吨口径运费用）：锁定计价数量 → 明细重量 → 计价数量。 */
  private static BigDecimal tonnage(DocumentItem item, DocumentItemMarketMapping mapping) {
    if (mapping != null && mapping.getPricingQuantity() != null && isTon(mapping.getPricingUnit())) {
      return mapping.getPricingQuantity();
    }
    BigDecimal weight = item.getTotalWeight();
    if (weight != null && weight.signum() > 0) {
      String unit = item.getWeightUnit() == null ? "" : item.getWeightUnit().trim().toLowerCase();
      if (List.of("kg", "千克", "公斤").contains(unit)) {
        return weight.divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
      }
      if (isTon(unit)) {
        return weight;
      }
    }
    if (item.getPricingQuantity() != null && isTon(item.getPricingUnit())) {
      return item.getPricingQuantity();
    }
    return BigDecimal.ZERO;
  }

  private static boolean isTon(String unit) {
    return unit != null && List.of("t", "ton", "吨").contains(unit.trim().toLowerCase());
  }

  private String supplierName(String userId) {
    User user = userMapper.selectById(userId);
    return user == null ? "" : nzStr(user.getCompanyName());
  }

  // ---------------------------------------------------------------------------
  // 模板填充
  // ---------------------------------------------------------------------------

  private void writeHeader(Sheet sheet, Project project, String supplier,
      LocalDate start, LocalDate end, String material) {
    setText(sheet, 0, 0, nzStr(project.getCustomerName()) + "钢材对账单");
    setText(sheet, 1, 0, "项目名称：" + nzStr(project.getName()));
    setText(sheet, 1, 5, "日期：" + chineseDate(start) + "——" + chineseDate(end));
    setText(sheet, 2, 2, "无合同");
    setText(sheet, 3, 2, supplier);
    setText(sheet, 4, 0, StringUtils.hasText(material) ? "材料明细（" + material + "）" : "材料明细");
  }

  private static String chineseDate(LocalDate date) {
    return date == null ? "" : date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
  }

  /**
   * 写明细并返回后续区块的下移行数：明细直接覆盖模板样式行，多出的行把合计及以下整体下移。
   * 日期列同一天只在首行写「M月d日」，其余留空（对齐客户样表）。
   */
  private int writeDetailRows(Sheet sheet, List<Line> lines) {
    int n = lines.size();
    CellStyle[] styles = captureRowStyles(sheet, TPL_DETAIL_ROW - 1);
    if (n == 0) {
      return 0;
    }
    if (n > 1) {
      sheet.shiftRows(TPL_TOTAL_ROW - 1, TPL_TAIL_LAST - 1, n - 1, true, false);
    }
    int rowIndex = TPL_DETAIL_ROW - 1;
    int seq = 1;
    LocalDate prevDate = null;
    for (Line line : lines) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        row = sheet.createRow(rowIndex);
      }
      row.setHeightInPoints(DETAIL_ROW_HEIGHT);
      LocalDate date = line.document().getDocDate();
      boolean firstOfDate = date != null && !date.equals(prevDate);
      prevDate = date;
      setCell(row, COL_SEQ, styles[COL_SEQ], seq++);
      setCell(row, COL_NAME, styles[COL_NAME], line.name());
      setCell(row, COL_SPEC, styles[COL_SPEC], line.spec());
      setCell(row, COL_QTY, styles[COL_QTY], line.quantity());
      setCell(row, COL_QUOTE, styles[COL_QUOTE], line.quotePrice());
      setCell(row, COL_UPLIFT, styles[COL_UPLIFT], line.uplift());
      setCell(row, COL_PRICE, styles[COL_PRICE], line.unitPrice());
      setCell(row, COL_AMOUNT, styles[COL_AMOUNT], line.amount());
      setCell(row, COL_DATE, styles[COL_DATE],
          firstOfDate ? date.getMonthValue() + "月" + date.getDayOfMonth() + "日" : null);
      setCell(row, COL_REMARK, styles[COL_REMARK], line.remark());
      rowIndex++;
    }
    return n - 1;
  }

  private CellStyle[] captureRowStyles(Sheet sheet, int styledRowIndex) {
    Row styled = sheet.getRow(styledRowIndex);
    CellStyle[] styles = new CellStyle[COL_COUNT];
    CellStyle fallback = fallbackStyle(sheet.getWorkbook());
    for (int i = 0; i < COL_COUNT; i++) {
      Cell cell = styled == null ? null : styled.getCell(i);
      styles[i] = cell != null ? cell.getCellStyle() : fallback;
    }
    return styles;
  }

  private CellStyle fallbackStyle(Workbook workbook) {
    CellStyle style = workbook.createCellStyle();
    style.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
    style.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
    style.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
    style.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
    style.setWrapText(true);
    return style;
  }

  private void setCell(Row row, int col, CellStyle style, Object value) {
    Cell cell = row.getCell(col);
    if (cell == null) {
      cell = row.createCell(col);
    }
    cell.setCellStyle(style);
    if (value == null || (value instanceof String text && text.isEmpty())) {
      cell.setBlank();
      return;
    }
    if (value instanceof BigDecimal number) {
      cell.setCellValue(number.doubleValue());
    } else if (value instanceof Number number) {
      cell.setCellValue(number.doubleValue());
    } else {
      cell.setCellValue(String.valueOf(value));
    }
  }

  private void setText(Sheet sheet, int rowIndex, int col, String value) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) {
      return;
    }
    Cell cell = row.getCell(col);
    if (cell == null) {
      cell = row.createCell(col);
    }
    // 模板单元格是 inlineStr 时 POI 的 setCellValue 不覆盖原值，必须先清空
    cell.setBlank();
    cell.setCellValue(value);
  }

  /** 网价/上浮无锁定值时按模板惯例写 '/' */
  private static Object priceValue(BigDecimal value) {
    return value == null ? "/" : value;
  }

  private void writeTotals(Sheet sheet, int offset, List<Line> lines) {
    Row totals = sheet.getRow(TPL_TOTAL_ROW - 1 + offset);
    if (totals == null) {
      return;
    }
    if (totals.getCell(COL_QTY) != null) {
      totals.getCell(COL_QTY).setCellValue(sumQuantity(lines).doubleValue());
    }
    if (totals.getCell(COL_AMOUNT) != null) {
      totals.getCell(COL_AMOUNT).setCellValue(sumAmount(lines).doubleValue());
    }
  }

  private void writeSummary(
      Sheet sheet, int offset,
      BigDecimal rangeQuantity, BigDecimal rangeAmount,
      BigDecimal cumulativeQuantity, BigDecimal cumulativeAmount,
      BigDecimal rangePaid, BigDecimal cumulativePaid
  ) {
    // 行10 本次 / 累计供应量；行11 本次 / 累计供货金额；行12 本次 / 累计已支付（C 列本次，H 列累计）
    setSummaryValue(sheet, 10 + offset, 2, rangeQuantity);
    setSummaryValue(sheet, 10 + offset, 7, cumulativeQuantity);
    setSummaryValue(sheet, 11 + offset, 2, rangeAmount);
    setSummaryValue(sheet, 11 + offset, 7, cumulativeAmount);
    setSummaryValue(sheet, 12 + offset, 2, rangePaid);
    setSummaryValue(sheet, 12 + offset, 7, cumulativePaid);
  }

  private void setSummaryValue(Sheet sheet, int rowNumber1Based, int col, BigDecimal value) {
    Row row = sheet.getRow(rowNumber1Based - 1);
    if (row == null) {
      return;
    }
    Cell cell = row.getCell(col);
    if (cell != null) {
      cell.setCellValue(value.doubleValue());
    }
  }

  // ---------------------------------------------------------------------------
  // 汇总
  // ---------------------------------------------------------------------------

  /** 数量合计：只算材料行（运费行无数量），保留原始精度 */
  static BigDecimal sumQuantity(List<Line> lines) {
    return lines.stream()
        .filter(line -> !line.freight())
        .map(Line::quantity)
        .map(ReconciliationExportService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** 金额合计：材料小计 + 运费 */
  static BigDecimal sumAmount(List<Line> lines) {
    return lines.stream()
        .map(Line::amount)
        .map(ReconciliationExportService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String nzStr(String value) {
    return value == null ? "" : value;
  }
}
