package com.aibot.purchase.service;

import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.DocumentItemMarketMapping;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentItemMarketMappingMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.service.storage.FileStorage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 对账单导出（销售侧，模板：resources/对账单-模板.xlsx）。
 *
 * <p>口径：明细行 = 项目内送货单日期 ∈ [start, end] 的已确认送货单明细（网价/上浮/销售单价/
 * 金额全部读 {@link DocumentItemMarketMapping} 锁定值，无映射回退明细行自身值）；
 * 本次供应量/供货金额 = 范围内合计；累计 = 项目内送货单日期 ≤ end（含本次）；
 * 本次/累计已支付 = 回款单（receipt）按 docDate 落在范围内 / ≤ end 累加。
 * 一次导出 = 一个日期范围一个 sheet；J 列按日期合并（同日期多张送货单并入同一日期格），
 * 每张送货单图片在首行放一次，按最长边等比缩放适配单元格并居中。
 * 已删除（软删）批次归属的单据不导出；无批次归属的项目级单据正常导出。
 */
@Service
public class ReconciliationExportService {

  /** 模板布局（1-based 行号） */
  private static final int TPL_HEADER_ROW = 6;
  private static final int TPL_DETAIL_ROW = 7;
  private static final int TPL_TAIL_FIRST = 8;
  private static final int TPL_TAIL_LAST = 20;
  private static final int COL_COUNT = 12;
  /** 插图列（K，0-based）；插图行行高（点） */
  private static final int IMAGE_COLUMN = 10;
  private static final float IMAGE_ROW_HEIGHT = 70f;
  /** 1px = 9525 EMU（96dpi） */
  private static final long EMU_PER_PX = 9525;

  private final BusinessQueryService businessQueryService;
  private final DocumentMapper documentMapper;
  private final DocumentItemMapper documentItemMapper;
  private final DocumentItemMarketMappingMapper itemNetMappingMapper;
  private final ProjectMapper projectMapper;
  private final FileStorage fileStorage;

  public ReconciliationExportService(
      BusinessQueryService businessQueryService,
      DocumentMapper documentMapper,
      DocumentItemMapper documentItemMapper,
      DocumentItemMarketMappingMapper itemNetMappingMapper,
      ProjectMapper projectMapper,
      FileStorage fileStorage
  ) {
    this.businessQueryService = businessQueryService;
    this.documentMapper = documentMapper;
    this.documentItemMapper = documentItemMapper;
    this.itemNetMappingMapper = itemNetMappingMapper;
    this.projectMapper = projectMapper;
    this.fileStorage = fileStorage;
  }

  private record DetailRow(
      Document document,
      DocumentItem item,
      DocumentItemMarketMapping mapping,
      BigDecimal quantity,
      String unit
  ) {}

  // ---------------------------------------------------------------------------
  // 对外入口
  // ---------------------------------------------------------------------------

  public byte[] export(String userId, String projectId, LocalDate start, LocalDate end) {
    Project project = loadOwnedProject(userId, projectId);
    List<Document> deliveriesInRange = listDeliveries(projectId, start, end);
    List<Document> deliveriesCumulative = listDeliveries(projectId, null, end);
    List<Document> receiptsInRange = listReceipts(projectId, start, end);
    List<Document> receiptsCumulative = listReceipts(projectId, null, end);

    try (InputStream in = new ClassPathResource("对账单-模板.xlsx").getInputStream();
         Workbook workbook = WorkbookFactory.create(in);
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.getSheetAt(0);
      List<DetailRow> detailRows = loadDetailRows(deliveriesInRange);
      int n = detailRows.size();
      writeHeader(sheet, project, start, end);
      if (n > 0) {
        sheet.shiftRows(TPL_DETAIL_ROW - 1, TPL_TAIL_LAST - 1, n);
      }
      writeDetailRows(workbook, sheet, detailRows);
      int offset = n;
      writeTotals(sheet, offset, detailRows);
      writeSummary(sheet, offset,
          sumQuantity(detailRows), sumAmount(detailRows),
          cumulativeQuantity(deliveriesCumulative), cumulativeAmount(deliveriesCumulative),
          sumDocAmount(receiptsInRange), sumDocAmount(receiptsCumulative));
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception ex) {
      throw new IllegalStateException("生成对账单导出文件失败：" + ex.getMessage(), ex);
    }
  }

  public String fileName(String userId, String projectId, LocalDate start, LocalDate end) {
    Project project = loadOwnedProject(userId, projectId);
    String name = StringUtils.hasText(project.getName()) ? project.getName() : "项目";
    return "对账单-" + name + "-" + start + "_" + end + ".xlsx";
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

  /** 范围汇总（卡片展示用）：范围内送货单张数 + 锁定销售额合计 */
  public record RangeSummary(int deliveries, java.math.BigDecimal salesAmount) {}

  /** 卡片实时汇总视图：项目/客户信息 + 指定日期范围的张数与锁定销售额（前端改日期即重取） */
  public Map<String, Object> summaryView(String userId, String projectId, LocalDate start, LocalDate end) {
    Project project = loadOwnedProject(userId, projectId);
    RangeSummary summary = rangeSummary(userId, projectId, start, end);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("projectName", nzStr(project.getName()));
    view.put("customerName", nzStr(project.getCustomerName()));
    view.put("deliveries", summary.deliveries());
    view.put("salesAmount", summary.salesAmount());
    return view;
  }

  public RangeSummary rangeSummary(String userId, String projectId, LocalDate start, LocalDate end) {
    loadOwnedProject(userId, projectId);
    List<Document> deliveries = listDeliveries(projectId, start, end);
    BigDecimal sales = BigDecimal.ZERO;
    if (!deliveries.isEmpty()) {
      List<String> documentIds = deliveries.stream().map(Document::getId).toList();
      sales = itemNetMappingMapper.selectList(Wrappers.<DocumentItemMarketMapping>lambdaQuery()
              .in(DocumentItemMarketMapping::getDocumentId, documentIds)
              .ne(DocumentItemMarketMapping::getPriceBasis, "unmatched"))
          .stream()
          .map(DocumentItemMarketMapping::getAmount)
          .map(ReconciliationExportService::nz)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    return new RangeSummary(deliveries.size(), sales);
  }

  // ---------------------------------------------------------------------------
  // 数据装载
  // ---------------------------------------------------------------------------

  /**
   * 排除归属批次已软删的单据；batch_id 为空（项目级单据）不受影响。
   * 批次软删不回写单据（BusinessQueryService.deleteBatch 只置 batches.deleted），
   * 导出侧必须自行过滤，否则已删批次的送货单/回款单仍会进对账单。
   */
  private static final String EXCLUDE_DELETED_BATCH =
      "select 1 from batches b where b.id = documents.batch_id and b.deleted = 1";

  private List<Document> listDeliveries(String projectId, LocalDate start, LocalDate end) {
    return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
            .eq(Document::getProjectId, projectId)
            .eq(Document::getDocType, "delivery_note")
            .eq(Document::getStatus, "matched")
            .eq(Document::getLedgerApplied, true)
            .notExists(EXCLUDE_DELETED_BATCH)
            .le(end != null, Document::getDocDate, end)
            .ge(start != null, Document::getDocDate, start)
            .orderByAsc(Document::getDocDate)
            .orderByAsc(Document::getCreatedAt));
  }

  private List<Document> listReceipts(String projectId, LocalDate start, LocalDate end) {
    return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
            .eq(Document::getProjectId, projectId)
            .eq(Document::getDocType, "receipt")
            .eq(Document::getStatus, "matched")
            .notExists(EXCLUDE_DELETED_BATCH)
            .le(end != null, Document::getDocDate, end)
            .ge(start != null, Document::getDocDate, start));
  }

  private List<DetailRow> loadDetailRows(List<Document> deliveries) {
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
    List<DetailRow> rows = new ArrayList<>();
    for (Document document : deliveries) {
      for (DocumentItem item : itemsByDoc.getOrDefault(document.getId(), List.of())) {
        DocumentItemMarketMapping mapping = mappingByItem.get(item.getId());
        BigDecimal quantity = mapping != null && mapping.getPricingQuantity() != null
            ? mapping.getPricingQuantity()
            : item.getPricingQuantity();
        String unit = mapping != null && StringUtils.hasText(mapping.getPricingUnit())
            ? mapping.getPricingUnit()
            : (StringUtils.hasText(item.getPricingUnit()) ? item.getPricingUnit() : item.getUnit());
        rows.add(new DetailRow(document, item, mapping, quantity, unit));
      }
    }
    return rows;
  }

  // ---------------------------------------------------------------------------
  // 模板填充
  // ---------------------------------------------------------------------------

  private void writeHeader(Sheet sheet, Project project, LocalDate start, LocalDate end) {
    Row title = sheet.getRow(0);
    if (title != null) {
      String customer = StringUtils.hasText(project.getCustomerName())
          ? project.getCustomerName() : "";
      title.getCell(0).setCellValue(customer + "（钢筋）对账单");
    }
    Row info = sheet.getRow(1);
    if (info != null) {
      if (info.getCell(0) != null) {
        info.getCell(0).setCellValue("项目名称：" + nzStr(project.getName()));
      }
      if (info.getCell(8) != null) {
        info.getCell(8).setCellValue("日期：" + start + " 至 " + end);
      }
    }
  }

  private void writeDetailRows(Workbook workbook, Sheet sheet, List<DetailRow> detailRows) {
    if (detailRows.isEmpty()) {
      return;
    }
    // 模板行7（下移后位于 TPL_DETAIL_ROW-1+n）带边框样式；先捕获其样式供全部明细行复用
    CellStyle[] styles = captureRowStyles(sheet, TPL_DETAIL_ROW - 1 + detailRows.size());
    Drawing<?> drawing = sheet.createDrawingPatriarch();
    int rowIndex = TPL_DETAIL_ROW - 1;
    int seq = 1;
    // J 列按日期合并：同日期的多个送货单并入同一日期格；图片按送货单在首行放一次
    int mergeStart = -1;
    String prevDocId = null;
    LocalDate prevDate = null;
    for (DetailRow row : detailRows) {
      LocalDate docDate = row.document().getDocDate();
      boolean sameDoc = row.document().getId().equals(prevDocId);
      boolean sameDate = docDate != null && docDate.equals(prevDate);
      if (!sameDate) {
        if (mergeStart >= 0 && rowIndex - 1 > mergeStart) {
          sheet.addMergedRegion(new CellRangeAddress(mergeStart, rowIndex - 1, 9, 9));
        }
        mergeStart = rowIndex;
      }
      prevDocId = row.document().getId();
      prevDate = docDate;

      Row sheetRow = sheet.getRow(rowIndex);
      if (sheetRow == null) {
        sheetRow = sheet.createRow(rowIndex);
      }
      sheetRow.setHeightInPoints(IMAGE_ROW_HEIGHT);
      DocumentItem item = row.item();
      DocumentItemMarketMapping mapping = row.mapping();
      setCell(sheetRow, 0, styles[0], seq++);
      setCell(sheetRow, 1, styles[1], item.getMaterialName());
      setCell(sheetRow, 2, styles[2], item.getSpec());
      setCell(sheetRow, 3, styles[3], row.quantity());
      setCell(sheetRow, 4, styles[4],
          priceValue(mapping == null ? null : mapping.getQuotePrice()));
      setCell(sheetRow, 5, styles[5],
          priceValue(mapping == null ? null : mapping.getUplift()));
      setCell(sheetRow, 6, styles[6],
          mapping != null && mapping.getSalesUnitPrice() != null
              ? mapping.getSalesUnitPrice() : item.getUnitPrice());
      setCell(sheetRow, 7, styles[7], row.unit());
      setCell(sheetRow, 8, styles[8],
          mapping != null && mapping.getAmount() != null
              ? mapping.getAmount() : item.getAmount());
      setCell(sheetRow, 9, styles[9], row.document().getDocDate() == null
          ? "" : row.document().getDocDate().toString());
      setCell(sheetRow, 10, styles[10], null);
      setCell(sheetRow, 11, styles[11], item.getRemark());

      // 图片只在每张送货单的首行放一次；同日期多张发货单即连续行各放一张
      if (!sameDoc) {
        insertDeliveryImage(workbook, sheet, drawing, styles, rowIndex, row);
      }
      rowIndex++;
    }
    if (mergeStart >= 0 && rowIndex - 1 > mergeStart) {
      sheet.addMergedRegion(new CellRangeAddress(mergeStart, rowIndex - 1, 9, 9));
    }
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
    return style;
  }

  private void setCell(Row row, int col, CellStyle style, Object value) {
    Cell cell = row.getCell(col);
    if (cell == null) {
      cell = row.createCell(col);
    }
    cell.setCellStyle(style);
    if (value == null || (value instanceof String text && text.isEmpty())) {
      cell.setCellValue("");
      return;
    }
    if (value instanceof BigDecimal number) {
      cell.setCellValue(number.doubleValue());
    } else {
      cell.setCellValue(String.valueOf(value));
    }
  }

  /** 网价/上浮无锁定值时按模板惯例写 '/' */
  private static Object priceValue(BigDecimal value) {
    return value == null ? "/" : value;
  }

  /**
   * K 列插入原始送货单图片：保持宽高比，按行高缩放，在单元格内水平居中；
   * 取不到图降级写送货单编号。
   */
  private void insertDeliveryImage(
      Workbook workbook, Sheet sheet, Drawing<?> drawing,
      CellStyle[] styles, int rowIndex, DetailRow row
  ) {
    Row sheetRow = sheet.getRow(rowIndex);
    String fallbackText = StringUtils.hasText(row.document().getDocCode())
        ? row.document().getDocCode() : nzStr(row.document().getFileName());
    byte[] image = readDeliveryImage(row.document());
    if (image == null || image.length == 0) {
      setCell(sheetRow, 10, styles[10], fallbackText);
      return;
    }
    PictureType type = detectPictureType(image);
    if (type == null) {
      setCell(sheetRow, 10, styles[10], fallbackText);
      return;
    }
    java.awt.image.BufferedImage decoded = decodeImageSize(image);
    if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
      setCell(sheetRow, 10, styles[10], fallbackText);
      return;
    }

    // 等比缩放：最长边适配单元格（比例取 min，图片完整落在格内不超出），水平+垂直居中
    double cellHeightPx = IMAGE_ROW_HEIGHT * 96.0 / 72.0;
    double cellWidthPx = sheet.getColumnWidthInPixels(IMAGE_COLUMN);
    double scale = Math.min(cellWidthPx / decoded.getWidth(), cellHeightPx / decoded.getHeight());
    int drawWidthPx = Math.max(1, (int) Math.round(decoded.getWidth() * scale));
    int drawHeightPx = Math.max(1, (int) Math.round(decoded.getHeight() * scale));

    // 起点居中（图片宽于/高于单元格时钳住负偏移）；终点 marker 沿列累进
    long dx1Emu = Math.max(0, Math.round((cellWidthPx - drawWidthPx) / 2.0 * EMU_PER_PX));
    long dy1Emu = Math.max(0, Math.round((cellHeightPx - drawHeightPx) / 2.0 * EMU_PER_PX));
    long drawWidthEmu = Math.round(drawWidthPx * EMU_PER_PX);
    long firstColEmu = Math.round(sheet.getColumnWidthInPixels(IMAGE_COLUMN) * EMU_PER_PX) - dx1Emu;
    int endCol = IMAGE_COLUMN;
    long remaining = drawWidthEmu;
    long colEmu = firstColEmu;
    while (remaining > colEmu) {
      remaining -= colEmu;
      endCol++;
      colEmu = Math.round(sheet.getColumnWidthInPixels(endCol) * EMU_PER_PX);
    }

    int pictureIndex = workbook.addPicture(image, type == PictureType.PNG
        ? Workbook.PICTURE_TYPE_PNG : Workbook.PICTURE_TYPE_JPEG);
    ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
    anchor.setCol1(IMAGE_COLUMN);
    anchor.setRow1(rowIndex);
    anchor.setDx1((int) dx1Emu);
    anchor.setDy1((int) dy1Emu);
    anchor.setCol2(endCol);
    anchor.setRow2(rowIndex);
    anchor.setDx2((int) Math.min(remaining, Integer.MAX_VALUE));
    anchor.setDy2((int) Math.min(Math.round(dy1Emu + drawHeightPx * EMU_PER_PX), Integer.MAX_VALUE));
    anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
    drawing.createPicture(anchor, pictureIndex);
  }

  private java.awt.image.BufferedImage decodeImageSize(byte[] bytes) {
    try {
      return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
    } catch (Exception ex) {
      return null;
    }
  }

  private byte[] readDeliveryImage(Document document) {
    if (!StringUtils.hasText(document.getFileObjectKey())) {
      return null;
    }
    try {
      byte[] bytes = fileStorage.read(document.getFileObjectKey());
      return bytes == null || bytes.length == 0 ? null : bytes;
    } catch (Exception ex) {
      return null;
    }
  }

  private PictureType detectPictureType(byte[] bytes) {
    if (bytes.length < 4) {
      return null;
    }
    if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') {
      return PictureType.PNG;
    }
    if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
      return PictureType.JPEG;
    }
    return null;
  }

  private void writeTotals(Sheet sheet, int offset, List<DetailRow> detailRows) {
    Row totals = sheet.getRow(TPL_TAIL_FIRST - 1 + offset);
    if (totals == null) {
      return;
    }
    if (totals.getCell(3) != null) {
      totals.getCell(3).setCellValue(sumQuantity(detailRows).doubleValue());
    }
    if (totals.getCell(8) != null) {
      totals.getCell(8).setCellValue(sumAmount(detailRows).doubleValue());
    }
  }

  private void writeSummary(
      Sheet sheet, int offset,
      BigDecimal rangeQuantity, BigDecimal rangeAmount,
      BigDecimal cumulativeQuantity, BigDecimal cumulativeAmount,
      BigDecimal rangePaid, BigDecimal cumulativePaid
  ) {
    // 行10 本次供应量 / 累计供应量；行11 本次供货金额 / 累计供货金额；行12 本次支付 / 累计已支付
    setSummaryValue(sheet, 10 + offset, 2, rangeQuantity);
    setSummaryValue(sheet, 10 + offset, 9, cumulativeQuantity);
    setSummaryValue(sheet, 11 + offset, 2, rangeAmount);
    setSummaryValue(sheet, 11 + offset, 9, cumulativeAmount);
    setSummaryValue(sheet, 12 + offset, 2, rangePaid);
    setSummaryValue(sheet, 12 + offset, 9, cumulativePaid);
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

  /** 数量合计：保留原始精度，不做舍入 */
  private BigDecimal sumQuantity(List<DetailRow> rows) {
    return rows.stream()
        .map(DetailRow::quantity)
        .map(ReconciliationExportService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** 金额合计：明细金额本身已是货币 2 位，求和不额外舍入 */
  private BigDecimal sumAmount(List<DetailRow> rows) {
    BigDecimal total = BigDecimal.ZERO;
    for (DetailRow row : rows) {
      BigDecimal amount = row.mapping() != null && row.mapping().getAmount() != null
          ? row.mapping().getAmount() : row.item().getAmount();
      total = total.add(nz(amount));
    }
    return total;
  }

  private BigDecimal cumulativeQuantity(List<Document> deliveries) {
    List<DetailRow> rows = loadDetailRows(deliveries);
    return sumQuantity(rows);
  }

  private BigDecimal cumulativeAmount(List<Document> deliveries) {
    List<DetailRow> rows = loadDetailRows(deliveries);
    return sumAmount(rows);
  }

  private BigDecimal sumDocAmount(List<Document> documents) {
    return documents.stream()
        .map(Document::getAmount)
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
