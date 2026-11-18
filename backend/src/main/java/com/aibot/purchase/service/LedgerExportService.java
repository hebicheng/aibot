package com.aibot.purchase.service;

import com.aibot.purchase.dto.BusinessDtos.BatchDto;
import com.aibot.purchase.dto.BusinessDtos.ProjectDto;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.LedgerRecord;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 台账 Excel 导出：1:1 还原页面表格（三层表头、跨行跨列合并、金额与状态样式）。
 *
 * <p>聚合口径与前端 ReconciliationLedgerPage.buildGroups 保持一致：
 * 项目/批次纵向合并、材料财务按材料行数合并、销售与收款按批次合并。
 */
@Service
public class LedgerExportService {

  private static final int HEADER_ROWS = 3;
  private static final int COL_COUNT = LedgerTableSchema.EXPORT_COLUMN_COUNT;
  /** 数据行统一行高（点）。显式设置可关掉 Excel 对换行内容的自动撑高，保证各批次行高一致。 */
  private static final float BODY_ROW_HEIGHT = 24f;

  // 列索引（与页面表头顺序一致）
  private static final int C_PROJECT = 0;
  private static final int C_BATCH = 1;
  private static final int C_KIND = 2;
  private static final int C_MATERIAL = 3;
  private static final int C_SPEC = 4;
  private static final int C_SUPPLIER = 5;
  private static final int C_PURCHASE_AMOUNT = 6;
  private static final int C_SUPPLY_QUANTITY = 7;
  private static final int C_DELIVERED_QUANTITY = 8;
  private static final int C_DELIVERY_STATUS = 9;
  private static final int C_TOTAL = 10;
  private static final int C_PURCHASE_MARGIN_AMOUNT = 11;
  private static final int C_PURCHASE_MARGIN = 12;
  // 计划 / 实际差额三列（2026-11-12）：采购金额差额、采购运费差额、采购毛利差额，
  // 位置紧跟材料组（13-15），采购与付款因此后移到 16-22，销售区 23 起不变。
  private static final int C_PURCHASE_AMOUNT_GAP = 13;
  private static final int C_PURCHASE_FREIGHT_GAP = 14;
  private static final int C_PURCHASE_GAP = 15;
  private static final int C_REQUESTED = 16;
  private static final int C_PAY_DATE = 17;
  private static final int C_PAYEE = 18;
  private static final int C_PAID = 19;
  private static final int C_INVOICE_DATE = 20;
  private static final int C_INVOICE_AMOUNT = 21;
  private static final int C_TAX_RATE = 22;
  private static final int C_SALES_ORDER = 23;
  private static final int C_SALES_AMOUNT = 24;
  /** 销售区「销售运费」：销售总金额里含的客户承担运费，只作构成说明（2026-10-28 建）。 */
  private static final int C_SALES_FREIGHT = 25;
  private static final int C_SALES_INVOICE_DATE = 26;
  private static final int C_SALES_INVOICE_AMOUNT = 27;
  private static final int C_SALES_TAX_RATE = 28;
  private static final int C_RECEIVED = 29;
  private static final int C_OUTSTANDING = 30;
  private static final int C_RECEIPT_DATE = 31;
  private static final int C_REQUIRED_RECEIPT_DATE = 32;
  private static final int C_INTEREST = 33;
  private static final int C_GROSS_PROFIT = 34;
  private static final int C_GROSS_MARGIN = 35;
  private static final int C_MANAGER = 36;
  private static final int C_STATUS = 37;

  private final BusinessQueryService businessQueryService;
  private final DocumentService documentService;
  private final DocumentItemMapper documentItemMapper;
  private final LedgerService ledgerService;
  private final PurchaseCostBasisService costBasisService;

  public LedgerExportService(
      BusinessQueryService businessQueryService,
      DocumentService documentService,
      DocumentItemMapper documentItemMapper,
      LedgerService ledgerService,
      PurchaseCostBasisService costBasisService
  ) {
    this.businessQueryService = businessQueryService;
    this.documentService = documentService;
    this.documentItemMapper = documentItemMapper;
    this.ledgerService = ledgerService;
    this.costBasisService = costBasisService;
  }

  // ---------------------------------------------------------------------------
  // 对外入口
  // ---------------------------------------------------------------------------

  public byte[] export(String userId, String projectId, String batchId) {
    List<ExportGroup> groups = buildGroups(userId, projectId, batchId);
    try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("项目批次对账台账");
      Styles styles = new Styles(workbook);
      writeHeader(sheet, styles);
      writeBody(sheet, styles, groups);
      applyLayout(sheet);
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception ex) {
      throw new IllegalStateException("生成台账导出文件失败：" + ex.getMessage(), ex);
    }
  }

  public String fileName() {
    return "项目批次对账台账-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
  }

  // ---------------------------------------------------------------------------
  // 聚合（与前端 buildGroups 口径一致）
  // ---------------------------------------------------------------------------

  private List<ExportGroup> buildGroups(String userId, String projectId, String batchId) {
    List<ExportGroup> groups = new ArrayList<>();
    List<ProjectDto> projects;
    if (StringUtils.hasText(projectId)) {
      projects = List.of(businessQueryService.getProject(userId, projectId));
    } else if (StringUtils.hasText(batchId)) {
      // 只传 batchId（web 端按当前批次导出）：反查所属项目，再在批次层过滤
      BatchDto batch = businessQueryService.getBatch(userId, batchId);
      projects = List.of(businessQueryService.getProject(userId, batch.projectId()));
    } else {
      projects = businessQueryService.listProjects(userId, "");
    }

    for (ProjectDto project : projects) {
      List<BatchDto> batches = businessQueryService.listBatches(userId, project.id());
      if (StringUtils.hasText(batchId)) {
        batches = batches.stream().filter(batch -> batchId.equals(batch.id())).toList();
      }
      if (batches.isEmpty()) {
        continue;
      }
      List<Document> documents = documentService.listByProject(userId, project.id(), null, null);
      Map<String, List<DocumentItem>> itemsByDocument = loadItems(documents);
      List<LedgerRecord> ledgerRows = ledgerService.getLedgerByProject(userId, project.id());

      for (BatchDto batch : batches) {
        List<Document> batchRecords = documents.stream()
            .filter(doc -> batch.id().equals(doc.getBatchId()))
            .filter(doc -> List.of("matched", "archived").contains(doc.getStatus()))
            .filter(doc -> !"skip".equals(doc.getDuplicateDecision()))
            .toList();
        List<LedgerRecord> batchLedger = ledgerRows.stream()
            .filter(row -> batch.id().equals(row.getBatchId()))
            .toList();
        List<Document> supplyRecords = filterType(batchRecords, "supply_order");
        List<Document> transportRecords = filterType(batchRecords, "transport_note");
        // 送货单 = 销售侧发货单；只有其上标注的运输费用（transportFee）参与「运输费用」行，
        // 销售金额走台账销售列，不再把送货单金额当运费。
        List<Document> deliveryFeeRecords = filterType(batchRecords, "delivery_note").stream()
            .filter(doc -> deliveryFreightAmount(doc).compareTo(BigDecimal.ZERO) > 0)
            .toList();
        List<Document> financeRecords = batchRecords.stream()
            .filter(doc -> List.of("payment_request", "payment", "purchase_invoice", "invoice")
                .contains(doc.getDocType()))
            .toList();
        if (supplyRecords.isEmpty() && transportRecords.isEmpty()
            && deliveryFeeRecords.isEmpty() && batchLedger.isEmpty()) {
          continue;
        }

        Map<String, DeliveryDetail> deliveryDetails =
            summarizeDelivery(batchRecords, itemsByDocument);

        // ---- 材料行（按采购单明细汇总） ----
        // 材料行金额按明细行自身金额（item.amount），缺失/无价即 0；
        // 不再用单据总额填满每行、也不按比例摊回差额。
        Map<String, MaterialRow> materialMap = new LinkedHashMap<>();
        for (Document supply : supplyRecords) {
          List<DocumentItem> sourceItems = Optional.of(itemsByDocument.get(supply.getId()))
              .filter(items -> !items.isEmpty())
              .orElseGet(() -> List.of(fallbackItem(supply)));

          for (DocumentItem item : sourceItems) {
            String key = materialKey(item.getMaterialName(), item.getSpec());
            DeliveryDetail delivery = deliveryDetails.get(key);
            MaterialRow current = materialMap.computeIfAbsent(key, ignored -> {
              MaterialRow row = new MaterialRow();
              row.material = MaterialCategoryNormalizer.category(item.getMaterialName());
              row.purchaseUnit = pricingUnit(item);
              row.deliveredPackages = delivery == null ? BigDecimal.ZERO : delivery.deliveredPackages;
              row.deliveredPackageUnit = delivery == null ? "" : delivery.deliveredPackageUnit;
              row.deliveredWeight = delivery == null ? BigDecimal.ZERO : delivery.deliveredWeight;
              row.deliveredWeightUnit = delivery == null ? "吨" : delivery.deliveredWeightUnit;
              row.deliveryStatus = delivery == null ? "待送达" : delivery.status;
              return row;
            });
            current.purchaseQuantity = current.purchaseQuantity.add(pricingQuantity(item));
            current.purchaseAmount = current.purchaseAmount.add(nz(item.getAmount()));
            if (!StringUtils.hasText(current.purchaseUnit)) {
              current.purchaseUnit = pricingUnit(item);
            }
            String supplier = supply.getSupplierName();
            if (StringUtils.hasText(supplier)
                && !List.of(current.supplier.split(" / ")).contains(supplier)) {
              current.supplier = StringUtils.hasText(current.supplier)
                  ? current.supplier + " / " + supplier : supplier;
            }
          }
        }
        List<MaterialRow> materialRows = new ArrayList<>(materialMap.values());

        // 采购金额按「实际送达量」口径（2026-11-12）：与页面共用同一个取数基准。
        // 材料行括号里的计划值也来自这里，保证「实际（计划）」两侧同源。
        PurchaseCostBasisService.Basis costBasis = costBasisService.basis(batch.id());
        Map<String, PurchaseCostBasisService.MaterialBasis> basisByCategory = new LinkedHashMap<>();
        for (PurchaseCostBasisService.MaterialBasis entry : costBasis.materials()) {
          basisByCategory.putIfAbsent(normalize(entry.category()), entry);
        }
        for (MaterialRow row : materialRows) {
          PurchaseCostBasisService.MaterialBasis entry = basisByCategory.get(normalize(row.material));
          if (entry == null) {
            continue;
          }
          row.purchaseAmountPlan = entry.planAmount();
          row.purchaseAmount = entry.actualAmount();
          row.purchaseTons = entry.planTons();
          row.deliveredTons = entry.deliveredTons();
          row.basisFallback = entry.fallbackPriced();
          row.basisUnpriced = entry.unpriced();
        }

        // ---- 送货单先行行：采购单未到或明细未匹配上的送货明细各自成行 ----
        // 只显示发货数量（状态=待采购单），金额列保持 0——送货明细金额是销售侧的，
        // 不能进采购金额列（会污染 totalPurchase 与毛利基数），销售金额走批次销售列。
        // 匹配只用于送达核对，不参与任何金额计算。
        Map<String, MaterialRow> deliveryOnlyMap = new LinkedHashMap<>();
        for (Document delivery : filterType(batchRecords, "delivery_note")) {
          for (DocumentItem item : itemsByDocument.getOrDefault(delivery.getId(), List.of())) {
            String key = materialKey(item.getMaterialName(), item.getSpec());
            if (materialMap.containsKey(key)) {
              continue;
            }
            MaterialRow row = deliveryOnlyMap.computeIfAbsent(key, ignored -> {
              MaterialRow created = new MaterialRow();
              created.material = MaterialCategoryNormalizer.category(item.getMaterialName());
              created.deliveredWeightUnit = "吨";
              created.deliveryStatus = "待采购单";
              return created;
            });
            // 送货单先行行：发货数量只进「送货单数量」（delivered），不借用 purchaseQuantity——
            // 数量列拆分后借用会把送货量串到「采购单数量」列，并污染送达状态判定（2026-09-21 修复）
            BigDecimal pkg = nz(item.getPackageQuantity());
            if (pkg.compareTo(BigDecimal.ZERO) > 0) {
              row.deliveredPackages = row.deliveredPackages.add(pkg);
              if (!StringUtils.hasText(row.deliveredPackageUnit)) {
                row.deliveredPackageUnit = StringUtils.hasText(item.getPackageUnit())
                    ? item.getPackageUnit() : item.getUnit();
              }
            }
            BigDecimal tons = weightInTons(nz(item.getTotalWeight()), item.getWeightUnit());
            if (tons.compareTo(BigDecimal.ZERO) > 0) {
              row.deliveredWeight = row.deliveredWeight.add(tons);
            }
          }
        }
        materialRows.addAll(deliveryOnlyMap.values());

        // 送货先行行（本大类还没有采购单）：批次有采购单时按整批均价估算成本并带上计划/实际括号，
        // 整个批次都没有采购单时保持 0（那时没有采购价可摊，采购金额列留空）。
        if (!costBasis.isEmpty()) {
          for (MaterialRow row : deliveryOnlyMap.values()) {
            PurchaseCostBasisService.MaterialBasis entry =
                basisByCategory.get(normalize(row.material));
            if (entry == null || entry.actualAmount().signum() <= 0) {
              continue;
            }
            row.purchaseAmountPlan = entry.planAmount();
            row.purchaseAmount = entry.actualAmount();
            row.purchaseTons = entry.planTons();
            row.deliveredTons = entry.deliveredTons();
            row.basisFallback = true;
          }
        }

        // ---- 运输费用行：批次内运输单 + 送货单标注的运输费用（transportFee）+ 运费口径推算，合并为一条 ----
        // 送货单运费与运输单同属「运输费用」分类，按批次汇入同一行，不再单独成行。
        // 2026-11-07：批次运费口径（元/趟 包干价 或 元/吨×采购吨数）也落在这一行，
        // 金额取批次权威值 purchaseFreightCost（= 运输单 + 送货单标注 + 口径，后端累加）。
        List<Document> transportSources = new ArrayList<>(transportRecords);
        transportSources.addAll(deliveryFeeRecords);
        Set<String> deliveryIds = deliveryFeeRecords.stream()
            .map(Document::getId)
            .collect(Collectors.toSet());
        LedgerRecord batchSummary = batchLedger.isEmpty() ? null : batchLedger.get(0);
        BigDecimal docFreight = transportSources.stream()
            .map(doc -> "delivery_note".equals(doc.getDocType())
                ? deliveryFreightAmount(doc) : nz(doc.getAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledgerFreight = batchSummary == null || batchSummary.getPurchaseFreightCost() == null
            ? null : batchSummary.getPurchaseFreightCost();
        // 台账已装饰的权威运费（含口径）；缺失时退回单据金额，保证不会把运费行整条丢掉
        BigDecimal totalFreight = ledgerFreight == null ? docFreight : ledgerFreight;

        // ---- 财务归集：运输 vs 材料 ----
        List<String> transportSuppliers = transportRecords.stream()
            .map(doc -> normalize(doc.getSupplierName()))
            .toList();
        List<Document> transportFinance = financeRecords.stream()
            .filter(doc -> isTransportFinance(doc, transportSuppliers)
                || (StringUtils.hasText(doc.getTargetDocumentId())
                    && deliveryIds.contains(doc.getTargetDocumentId())))
            .toList();
        List<String> transportFinanceIds = transportFinance.stream().map(Document::getId).toList();
        List<Document> materialFinanceRecords = financeRecords.stream()
            .filter(doc -> !transportFinanceIds.contains(doc.getId()))
            .toList();

        BigDecimal materialPurchase = materialRows.stream()
            .map(row -> row.purchaseAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        FinanceSummary materialFinance = summarizeFinance(materialFinanceRecords, materialPurchase);

        List<ExportRow> rows = new ArrayList<>();
        // 材料合并为一行汇总（与页面折叠态、运输行的表现一致），不再逐行平铺；
        // 材料行按大类聚合（2026-09-21 起），汇总行标注大类数
        if (!materialRows.isEmpty()) {
          ExportRow summary = new ExportRow();
          summary.kind = "material";
          summary.material = "材料明细（" + materialRows.size() + " 大类）";
          summary.spec = materialRows.stream()
              .map(r -> r.material)
              .filter(name -> !"未标注材料".equals(name))
              .distinct()
              .collect(Collectors.joining(" / "));
          if (!StringUtils.hasText(summary.spec)) {
            summary.spec = "未拆分采购数据";
          }
          summary.supplier = materialRows.stream()
              .flatMap(r -> java.util.Arrays.stream(r.supplier.split(" / ")))
              .filter(StringUtils::hasText)
              .distinct()
              .collect(Collectors.joining(" / "));
          summary.purchaseQuantity = materialRows.stream()
              .map(r -> r.purchaseQuantity)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          summary.purchaseUnit = materialRows.size() == 1 ? materialRows.get(0).purchaseUnit : "合计";
          summary.purchaseAmount = materialRows.stream()
              .map(r -> r.purchaseAmount)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          // 括号里的计划值：各材料大类计划金额合计（与批次级 purchasePlanAmount 同源）
          summary.purchaseAmountPlan = materialRows.stream()
              .map(r -> r.purchaseAmountPlan)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          summary.purchaseTons = materialRows.stream()
              .map(r -> r.purchaseTons)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          summary.deliveredTons = materialRows.stream()
              .map(r -> r.deliveredTons)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          summary.deliveredPackages = materialRows.stream()
              .map(r -> r.deliveredPackages)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          summary.deliveredWeight = materialRows.stream()
              .map(r -> r.deliveredWeight)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
          summary.deliveredWeightUnit = "吨";
          boolean allDelivered = materialRows.stream()
              .allMatch(r -> "已送达".equals(r.deliveryStatus) || "超供送达".equals(r.deliveryStatus));
          boolean anyDelivered = materialRows.stream()
              .anyMatch(r -> "已送达".equals(r.deliveryStatus) || "超供送达".equals(r.deliveryStatus));
          boolean anyOversupplied = materialRows.stream()
              .anyMatch(r -> "超供送达".equals(r.deliveryStatus));
          summary.deliveryStatus = allDelivered
              ? (anyOversupplied ? "超供送达" : "已送达")
              : anyDelivered ? "部分送达" : "待送达";
          summary.finance = materialFinance;
          rows.add(summary);
        }
        if (totalFreight.signum() > 0) {
          Set<String> transportSourceIds = transportSources.stream()
              .map(Document::getId)
              .collect(Collectors.toSet());
          List<Document> rowFinance = transportFinance.stream()
              .filter(doc -> !StringUtils.hasText(doc.getTargetDocumentId())
                  || transportSourceIds.contains(doc.getTargetDocumentId()))
              .toList();
          // 构成明细直接读运费行（口径 / 运输单 / 单据写明并列累加，2026-11-11），
          // 不再按「总额 − 单据运费」倒算一条「按运费口径」
          List<com.aibot.purchase.entity.FreightItem> freightRows =
              batchSummary == null || batchSummary.getFreightItems() == null
                  ? List.of() : batchSummary.getFreightItems();
          ExportRow row = new ExportRow();
          row.kind = "transport";
          row.material = "运输费用";
          row.spec = freightRows.stream()
              .map(com.aibot.purchase.entity.FreightItem::getNote)
              .filter(StringUtils::hasText)
              .distinct()
              .collect(Collectors.joining(" / "));
          if (!StringUtils.hasText(row.spec)) {
            row.spec = transportSources.stream()
                .map(Document::getDocCode)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.joining(" / "));
          }
          if (!StringUtils.hasText(row.spec)) {
            row.spec = "运输费用";
          }
          // 承运单位以运费行为准（V28 起存在行上、可人工覆盖），不再回头拼源单据
          row.supplier = freightRows.stream()
              .map(com.aibot.purchase.entity.FreightItem::getCarrierName)
              .filter(StringUtils::hasText)
              .distinct()
              .collect(Collectors.joining(" / "));
          if (!StringUtils.hasText(row.supplier)) {
            row.supplier = "待补充承运单位";
          }
          // 笔数：按运费行条数（口径行也算一笔）
          row.purchaseQuantity = BigDecimal.valueOf(Math.max(freightRows.size(), 1));
          row.purchaseUnit = "笔";
          row.purchaseAmount = totalFreight;
          row.deliveryStatus = transportSources.stream()
              .map(Document::getDocDate)
              .filter(Objects::nonNull)
              .map(LocalDate::toString)
              .distinct()
              .collect(Collectors.joining(" / "));
          if (!StringUtils.hasText(row.deliveryStatus)) {
            row.deliveryStatus = "按口径估算";
          }
          row.finance = summarizeFinance(rowFinance, totalFreight);
          rows.add(row);
        }
        // 送货单单独导入（暂无采购单）时保留台账行：占位材料行，采购单确认后自动替换
        if (materialRows.isEmpty() && !batchLedger.isEmpty()) {
          ExportRow placeholder = new ExportRow();
          placeholder.kind = "material";
          placeholder.material = "材料明细";
          placeholder.spec = "待采购单导入";
          placeholder.deliveryStatus = "待送达";
          placeholder.finance = materialFinance;
          rows.add(placeholder);
        }
        if (rows.isEmpty()) {
          continue;
        }

        // ---- 批次级汇总 ----
        LedgerRecord summary = batchSummary;
        BigDecimal sales = batchLedger.stream()
            .map(row -> nz(row.getSalesAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal received = batchLedger.stream()
            .map(row -> nz(row.getReceivedAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 采购及运输合计锚定台账权威采购额（batchLedger：明细合计 or 单据总额兜底）+ 运输金额，
        // 与页面 totalPurchase 口径一致；展示行的 item.amount 可能为空白（单据总额兜底场景），
        // 用行金额求和会导致导出总额/毛利比页面偏小。
        BigDecimal ledgerPurchase = batchLedger.stream()
            .map(row -> nz(row.getPurchaseAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal transportRowAmount = rows.stream()
            .filter(row -> "transport".equals(row.kind))
            .map(row -> row.purchaseAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPurchase = ledgerPurchase.add(transportRowAmount);
        BigDecimal grossProfit = sales.compareTo(BigDecimal.ZERO) > 0
            ? sales.subtract(totalPurchase) : BigDecimal.ZERO;
        BigDecimal grossMargin = sales.compareTo(BigDecimal.ZERO) > 0
            ? grossProfit.multiply(BigDecimal.valueOf(100))
                .divide(sales, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        boolean materialBalanced = materialRows.isEmpty()
            || (materialFinance.unpaid.compareTo(BigDecimal.ZERO) <= 0
                && materialFinance.invoice.compareTo(materialPurchase) >= 0);
        boolean transportBalanced = rows.stream()
            .filter(row -> "transport".equals(row.kind))
            .allMatch(row -> row.finance.unpaid.compareTo(BigDecimal.ZERO) <= 0
                && row.finance.invoice.compareTo(row.purchaseAmount) >= 0);
        boolean allDelivered = rows.stream()
            .filter(row -> "material".equals(row.kind))
            .allMatch(row -> "已送达".equals(row.deliveryStatus)
                || "超供送达".equals(row.deliveryStatus));

        ExportGroup group = new ExportGroup();
        group.project = project.name();
        group.batch = batch.name();
        group.batchDate = batch.businessDate() == null ? "" : batch.businessDate().toString();
        group.rows = rows;
        // 总额列：材料采购 + 运输费用（跨材料行与运输行做总计）
        group.totalAmount = totalPurchase;
        // 采购毛利额/毛利率：台账装饰层按批次口径现算的瞬时值（挂在批次首条记录上）
        group.purchaseMarginAmount = summary == null ? null : summary.getPurchaseMarginAmount();
        group.purchaseMarginRate = summary == null ? null : summary.getPurchaseMarginRate();
        group.purchaseMarginPlanAmount =
            summary == null ? null : summary.getPurchaseMarginPlanAmount();
        group.purchaseMarginPlanRate = summary == null ? null : summary.getPurchaseMarginPlanRate();
        // 计划 / 实际差额三列（2026-11-12）：都取台账装饰层的瞬时值，与页面同源
        group.purchaseMarginGap = summary == null ? null : summary.getPurchaseMarginGap();
        group.purchaseAmountGap = summary == null ? null : summary.getPurchaseAmountGap();
        group.purchaseFreightGap = summary == null ? null : summary.getPurchaseFreightGap();
        group.purchasePlanAmount = summary == null ? null : summary.getPurchasePlanAmount();
        group.hasDelivery = summary != null && Boolean.TRUE.equals(summary.getPurchaseHasDelivery());
        group.purchaseFreightCost = summary == null ? null : summary.getPurchaseFreightCost();
        group.salesFreight = summary == null || summary.getPurchaseSalesFreight() == null
            ? BigDecimal.ZERO : summary.getPurchaseSalesFreight();
        // 材料已合并为一行汇总，固定 1（占位行也是 1）
        group.materialRowCount = 1;
        group.salesOrder = summary == null ? "" : valueOrEmpty(summary.getSalesOrder());
        group.sales = summary == null ? sales : nz(summary.getSalesAmount());
        group.salesInvoiceDate = summary == null || summary.getSalesInvoiceDate() == null
            ? "" : summary.getSalesInvoiceDate().toString();
        group.salesInvoiceAmount = summary == null ? BigDecimal.ZERO : nz(summary.getSalesInvoiceAmount());
        group.salesTaxRate = summary == null || !StringUtils.hasText(summary.getSalesTaxRate())
            ? "-" : summary.getSalesTaxRate();
        group.received = received;
        group.outstanding = group.sales.subtract(received).max(BigDecimal.ZERO);
        group.receiptDate = summary == null || summary.getReceiptDate() == null
            ? "" : summary.getReceiptDate().toString();
        group.requiredReceiptDate = summary == null || summary.getRequiredReceiptDate() == null
            ? "" : summary.getRequiredReceiptDate().toString();
        group.monthlySettlement = summary != null && Boolean.TRUE.equals(summary.getIsMonthlySettlement());
        group.interest = summary == null ? BigDecimal.ZERO : nz(summary.getInterest());
        group.grossProfit = grossProfit;
        group.grossMargin = grossMargin;
        group.projectManager = summary == null ? "" : valueOrEmpty(summary.getProjectManager());
        group.status = materialBalanced && transportBalanced && allDelivered ? "已对平" : "待补充";
        groups.add(group);
      }
    }
    return groups;
  }

  private Map<String, List<DocumentItem>> loadItems(List<Document> documents) {
    if (documents.isEmpty()) {
      return Map.of();
    }
    List<String> ids = documents.stream().map(Document::getId).toList();
    List<DocumentItem> items = documentItemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .in(DocumentItem::getDocumentId, ids)
        .orderByAsc(DocumentItem::getSortOrder));
    Map<String, List<DocumentItem>> result = new LinkedHashMap<>();
    for (DocumentItem item : items) {
      result.computeIfAbsent(item.getDocumentId(), ignored -> new ArrayList<>()).add(item);
    }
    return result;
  }

  private Map<String, DeliveryDetail> summarizeDelivery(
      List<Document> batchRecords,
      Map<String, List<DocumentItem>> itemsByDocument
  ) {
    Map<String, DeliveryDetail> details = new LinkedHashMap<>();
    for (Document supply : filterType(batchRecords, "supply_order")) {
      for (DocumentItem item : itemsByDocument.getOrDefault(supply.getId(), List.of())) {
        String key = materialKey(item.getMaterialName(), item.getSpec());
        DeliveryDetail detail = details.computeIfAbsent(key, ignored -> {
          DeliveryDetail created = new DeliveryDetail();
          created.purchaseUnit = pricingUnit(item);
          return created;
        });
        detail.purchaseQuantity = detail.purchaseQuantity.add(pricingQuantity(item));
        if (!StringUtils.hasText(detail.purchaseUnit)) {
          detail.purchaseUnit = pricingUnit(item);
        }
      }
    }
    for (Document delivery : filterType(batchRecords, "delivery_note")) {
      for (DocumentItem item : itemsByDocument.getOrDefault(delivery.getId(), List.of())) {
        DeliveryDetail detail = details.get(materialKey(item.getMaterialName(), item.getSpec()));
        if (detail == null) {
          continue;
        }
        detail.deliveredPackages = detail.deliveredPackages.add(
            nz(item.getPackageQuantity()).compareTo(BigDecimal.ZERO) > 0
                ? nz(item.getPackageQuantity()) : nz(item.getQuantity()));
        if (!StringUtils.hasText(detail.deliveredPackageUnit)) {
          detail.deliveredPackageUnit = StringUtils.hasText(item.getPackageUnit())
              ? item.getPackageUnit() : valueOrEmpty(item.getUnit());
        }
        detail.deliveredWeight = detail.deliveredWeight.add(
            weightInTons(nz(item.getTotalWeight()), item.getWeightUnit()));
      }
    }
    for (DeliveryDetail detail : details.values()) {
      BigDecimal delivered = isWeightUnit(detail.purchaseUnit)
          ? detail.deliveredWeight : detail.deliveredPackages;
      // 超供送达：先导送货单时，送达数量可能超过采购单数量（2026-09-21 增加状态）
      detail.status = delivered.compareTo(BigDecimal.ZERO) <= 0
          ? "待送达"
          : detail.purchaseQuantity.compareTo(BigDecimal.ZERO) > 0
              && delivered.compareTo(detail.purchaseQuantity.add(new BigDecimal("0.0001"))) > 0
              ? "超供送达"
              : detail.purchaseQuantity.compareTo(BigDecimal.ZERO) > 0
                  && delivered.add(new BigDecimal("0.0001")).compareTo(detail.purchaseQuantity) < 0
                  ? "部分送达" : "已送达";
    }
    return details;
  }

  private FinanceSummary summarizeFinance(List<Document> records, BigDecimal purchaseAmount) {
    FinanceSummary summary = new FinanceSummary();
    summary.requested = sumByType(records, "payment_request");
    summary.paid = sumByType(records, "payment");
    summary.invoice = sumByType(records, "purchase_invoice").add(sumByType(records, "invoice"));
    records.stream().filter(doc -> "payment".equals(doc.getDocType())).findFirst()
        .ifPresent(doc -> {
          summary.paymentDate = doc.getDocDate() == null ? "" : doc.getDocDate().toString();
          summary.payee = valueOrEmpty(doc.getSupplierName());
        });
    records.stream()
        .filter(doc -> List.of("purchase_invoice", "invoice").contains(doc.getDocType()))
        .filter(doc -> doc.getDocDate() != null)
        .findFirst()
        .ifPresent(doc -> summary.invoiceDate = doc.getDocDate().toString());
    List<String> taxRates = records.stream()
        .filter(doc -> List.of("purchase_invoice", "invoice").contains(doc.getDocType()))
        .map(doc -> structuredText(doc, "taxRate"))
        .filter(StringUtils::hasText)
        .distinct()
        .toList();
    summary.taxRate = taxRates.isEmpty() ? "-" : String.join(" / ", taxRates);
    summary.unpaid = purchaseAmount.subtract(summary.paid).max(BigDecimal.ZERO);
    return summary;
  }

  private boolean isTransportFinance(Document document, List<String> transportSuppliers) {
    String category = structuredText(document, "expenseCategory");
    if ("transport".equals(category)) {
      return true;
    }
    if (StringUtils.hasText(category)) {
      return false;
    }
    if (transportSuppliers.contains(normalize(document.getSupplierName()))) {
      return true;
    }
    String hint = String.join(" ",
        valueOrEmpty(document.getSourceText()),
        valueOrEmpty(document.getGuidanceText()),
        valueOrEmpty(document.getAiSummary()),
        valueOrEmpty(document.getRemark()));
    return hint.matches(".*(运输|运费|物流|承运).*");
  }

  private BigDecimal sumByType(List<Document> records, String docType) {
    return records.stream()
        .filter(doc -> docType.equals(doc.getDocType()))
        .map(doc -> nz(doc.getAmount()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private String structuredText(Document document, String field) {
    Map<String, Object> structured = documentService.structuredDataOf(document);
    Object value = structured.get(field);
    return value == null ? "" : String.valueOf(value).trim();
  }

  /** 送货单上标注为运输费用的金额（structuredData.transportFee/freight）；amount 字段是销售额。 */
  private BigDecimal deliveryFreightAmount(Document document) {
    String fee = structuredText(document, "transportFee");
    if (!StringUtils.hasText(fee)) {
      fee = structuredText(document, "freight");
    }
    if (!StringUtils.hasText(fee)) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(fee.replaceAll("[^\\d.-]", "")).max(BigDecimal.ZERO);
    } catch (Exception ex) {
      return BigDecimal.ZERO;
    }
  }

  // ---------------------------------------------------------------------------
  // Excel 写出
  // ---------------------------------------------------------------------------

  /**
   * 表头渲染：结构来自 {@link LedgerTableSchema}（唯一来源，与页面共用）。
   * 用列占用表还原 HTML 表格的 rowSpan/colSpan 语义：rowSpan 跨行单元格占用后
   * 续行的列，后续行的光标跳过被占列——三层表头错列修复（2026-09-14）。
   */
  private void writeHeader(Sheet sheet, Styles styles) {
    List<List<LedgerTableSchema.HeaderCell>> rows = LedgerTableSchema.HEADER;
    boolean[][] used = new boolean[rows.size()][64];
    for (int r = 0; r < rows.size(); r++) {
      int cursor = 0;
      for (LedgerTableSchema.HeaderCell cell : rows.get(r)) {
        while (cursor < used[r].length && used[r][cursor]) {
          cursor++;
        }
        int colStart = cursor;
        int colEnd = colStart + cell.colSpan() - 1;
        int rowEnd = r + cell.rowSpan() - 1;
        if (cell.exportable()) {
          header(sheet, styles, cell.label(), r, rowEnd, colStart, colEnd);
        }
        for (int rr = r; rr <= rowEnd && rr < used.length; rr++) {
          for (int cc = colStart; cc <= colEnd && cc < used[rr].length; cc++) {
            used[rr][cc] = true;
          }
        }
        cursor = colEnd + 1;
      }
    }
  }

  private void header(Sheet sheet, Styles styles, String value,
                      int rowStart, int rowEnd, int colStart, int colEnd) {
    for (int r = rowStart; r <= rowEnd; r++) {
      Row row = sheet.getRow(r) == null ? sheet.createRow(r) : sheet.getRow(r);
      for (int c = colStart; c <= colEnd; c++) {
        Cell cell = row.createCell(c);
        cell.setCellValue(value);
        cell.setCellStyle(styles.headGroup);
      }
    }
    if (rowStart != rowEnd || colStart != colEnd) {
      sheet.addMergedRegion(new CellRangeAddress(rowStart, rowEnd, colStart, colEnd));
    }
  }

  private void writeBody(Sheet sheet, Styles styles, List<ExportGroup> groups) {
    int rowIndex = HEADER_ROWS;
    for (ExportGroup group : groups) {
      int start = rowIndex;
      for (ExportRow row : group.rows) {
        Row sheetRow = sheet.createRow(rowIndex);
        sheetRow.setHeightInPoints(BODY_ROW_HEIGHT);
        for (int c = 0; c < COL_COUNT; c++) {
          Cell cell = sheetRow.createCell(c);
          cell.setCellStyle(styles.text);
        }
        text(sheetRow, C_KIND, "material".equals(row.kind) ? "材料" : "运输");
        text(sheetRow, C_MATERIAL, row.material);
        text(sheetRow, C_SPEC, row.spec);
        text(sheetRow, C_SUPPLIER, StringUtils.hasText(row.supplier) ? row.supplier : "-");
        sheetRow.getCell(C_PURCHASE_AMOUNT).setCellValue(row.purchaseAmount.doubleValue());
        sheetRow.getCell(C_PURCHASE_AMOUNT).setCellStyle(styles.money);
        // 括号里的计划值写不进数字单元格（会变文本、破坏求和），改写成批注：悬停可见
        annotate(sheetRow.getCell(C_PURCHASE_AMOUNT), purchaseAmountNote(row, group));
        // 送货单先行行（无采购单）供货数量列显示 "-"，数量在送货单数量列
        boolean deliveryOnlyRow = "material".equals(row.kind)
            && row.purchaseQuantity.compareTo(BigDecimal.ZERO) <= 0;
        text(sheetRow, C_SUPPLY_QUANTITY, deliveryOnlyRow ? "-"
            : quantityText(row.purchaseQuantity)
                + (StringUtils.hasText(row.purchaseUnit) ? " " + row.purchaseUnit : ""));
        if ("material".equals(row.kind)) {
          String delivered = row.deliveredWeight.compareTo(BigDecimal.ZERO) > 0
              ? quantityText(row.deliveredWeight) + " " + row.deliveredWeightUnit
              : row.deliveredPackages.compareTo(BigDecimal.ZERO) > 0
                  ? quantityText(row.deliveredPackages) + " " + row.deliveredPackageUnit : "-";
          text(sheetRow, C_DELIVERED_QUANTITY, delivered);
          text(sheetRow, C_DELIVERY_STATUS, row.deliveryStatus);
        } else {
          // 运输行：送货数量列留空，日期信息维持在送达状态列（与页面行为一致）
          text(sheetRow, C_DELIVERED_QUANTITY, "-");
          text(sheetRow, C_DELIVERY_STATUS, row.deliveryStatus);
        }
        boolean firstMaterialRow = rowIndex == start && group.materialRowCount > 0;
        if (firstMaterialRow || "transport".equals(row.kind)) {
          FinanceSummary finance = row.finance;
          sheetRow.getCell(C_REQUESTED).setCellValue(finance.requested.doubleValue());
          sheetRow.getCell(C_REQUESTED).setCellStyle(styles.money);
          text(sheetRow, C_PAY_DATE, finance.paymentDate);
          text(sheetRow, C_PAYEE, StringUtils.hasText(finance.payee) ? finance.payee : "-");
          sheetRow.getCell(C_PAID).setCellValue(finance.paid.doubleValue());
          sheetRow.getCell(C_PAID).setCellStyle(styles.money);
          text(sheetRow, C_INVOICE_DATE, finance.invoiceDate);
          sheetRow.getCell(C_INVOICE_AMOUNT).setCellValue(finance.invoice.doubleValue());
          sheetRow.getCell(C_INVOICE_AMOUNT).setCellStyle(styles.money);
          text(sheetRow, C_TAX_RATE, finance.taxRate);
        }
        rowIndex++;
      }
      int end = rowIndex - 1;

      // 项目 / 批次：跨整个批次合并
      Row first = sheet.getRow(start);
      first.getCell(C_PROJECT).setCellValue(group.project);
      first.getCell(C_BATCH).setCellValue(
          StringUtils.hasText(group.batchDate) ? group.batch + " " + group.batchDate : group.batch);
      merge(sheet, start, end, C_PROJECT, C_PROJECT);
      merge(sheet, start, end, C_BATCH, C_BATCH);

      // 材料财务：按材料行数合并
      if (group.materialRowCount > 0) {
        int materialEnd = start + group.materialRowCount - 1;        merge(sheet, start, materialEnd, C_REQUESTED, C_REQUESTED);
        merge(sheet, start, materialEnd, C_PAY_DATE, C_PAY_DATE);
        merge(sheet, start, materialEnd, C_PAYEE, C_PAYEE);
        merge(sheet, start, materialEnd, C_PAID, C_PAID);
        merge(sheet, start, materialEnd, C_INVOICE_DATE, C_INVOICE_DATE);
        merge(sheet, start, materialEnd, C_INVOICE_AMOUNT, C_INVOICE_AMOUNT);
        merge(sheet, start, materialEnd, C_TAX_RATE, C_TAX_RATE);
      }

      // 总额：材料采购 + 运输费用，跨材料行与运输行合并
      first.getCell(C_TOTAL).setCellValue(group.totalAmount.doubleValue());
      first.getCell(C_TOTAL).setCellStyle(styles.money);
      merge(sheet, start, end, C_TOTAL, C_TOTAL);

      // 采购运费成本 / 销售运费两列已于 2026-11-07 撤出材料组：
      // 采购运费并入独立的「运输费用」行（口径与运费单都进该行金额），销售运费由销售区同名列表述。

      // 采购毛利金额：批次级合并（元）；无口径/无命中行留 "-"
      if (group.purchaseMarginAmount != null) {
        first.getCell(C_PURCHASE_MARGIN_AMOUNT)
            .setCellValue(group.purchaseMarginAmount.doubleValue());
        first.getCell(C_PURCHASE_MARGIN_AMOUNT).setCellStyle(styles.money);
        annotate(first.getCell(C_PURCHASE_MARGIN_AMOUNT), marginPlanNote(group));
      } else {
        text(first, C_PURCHASE_MARGIN_AMOUNT, "-");
      }
      merge(sheet, start, end, C_PURCHASE_MARGIN_AMOUNT, C_PURCHASE_MARGIN_AMOUNT);

      // 采购毛利率：批次级合并；无口径/无有效行留 "-"（单元格已按 text 样式创建）
      text(first, C_PURCHASE_MARGIN, group.purchaseMarginRate == null ? "-"
          : quantityText(group.purchaseMarginRate) + "%");
      merge(sheet, start, end, C_PURCHASE_MARGIN, C_PURCHASE_MARGIN);

      // 采购毛利差额：计划量与实际送达量不一致导致的毛利差（元），无送货单/无差异留 "-"
      writeGap(first, C_PURCHASE_AMOUNT_GAP, group.purchaseAmountGap, styles);
      writeGap(first, C_PURCHASE_FREIGHT_GAP, group.purchaseFreightGap, styles);
      writeGap(first, C_PURCHASE_GAP, group.purchaseMarginGap, styles);
      for (int column : List.of(C_PURCHASE_AMOUNT_GAP, C_PURCHASE_FREIGHT_GAP, C_PURCHASE_GAP)) {
        merge(sheet, start, end, column, column);
      }

      // 销售与收款：跨整个批次合并
      text(first, C_SALES_ORDER, StringUtils.hasText(group.salesOrder) ? group.salesOrder : "-");
      first.getCell(C_SALES_AMOUNT).setCellValue(group.sales.doubleValue());
      first.getCell(C_SALES_AMOUNT).setCellStyle(styles.money);
      // 销售运费：销售总金额里含的客户承担运费（构成拆分，不额外加总）
      if (group.salesFreight.signum() > 0) {
        first.getCell(C_SALES_FREIGHT).setCellValue(group.salesFreight.doubleValue());
        first.getCell(C_SALES_FREIGHT).setCellStyle(styles.money);
      } else {
        text(first, C_SALES_FREIGHT, "-");
      }
      text(first, C_SALES_INVOICE_DATE, group.salesInvoiceDate);
      first.getCell(C_SALES_INVOICE_AMOUNT).setCellValue(group.salesInvoiceAmount.doubleValue());
      first.getCell(C_SALES_INVOICE_AMOUNT).setCellStyle(styles.money);
      text(first, C_SALES_TAX_RATE, group.salesTaxRate);
      first.getCell(C_RECEIVED).setCellValue(group.received.doubleValue());
      first.getCell(C_RECEIVED).setCellStyle(styles.money);
      first.getCell(C_OUTSTANDING).setCellValue(group.outstanding.doubleValue());
      first.getCell(C_OUTSTANDING).setCellStyle(styles.money);
      text(first, C_RECEIPT_DATE, group.receiptDate);
      text(first, C_REQUIRED_RECEIPT_DATE, group.requiredReceiptDate);
      first.getCell(C_INTEREST).setCellValue(
          group.monthlySettlement ? group.interest.doubleValue() : 0d);
      first.getCell(C_INTEREST).setCellStyle(styles.money);
      first.getCell(C_GROSS_PROFIT).setCellValue(group.grossProfit.doubleValue());
      first.getCell(C_GROSS_PROFIT).setCellStyle(styles.money);
      text(first, C_GROSS_MARGIN, quantityText(group.grossMargin) + "%");
      text(first, C_MANAGER, StringUtils.hasText(group.projectManager) ? group.projectManager : "-");
      first.getCell(C_STATUS).setCellValue(group.status);
      first.getCell(C_STATUS).setCellStyle(
          "已对平".equals(group.status) ? styles.statusOk : styles.statusWarn);
      for (int c = C_SALES_ORDER; c <= C_STATUS; c++) {
        merge(sheet, start, end, c, c);
      }
    }
  }

  private void merge(Sheet sheet, int rowStart, int rowEnd, int colStart, int colEnd) {
    if (rowStart == rowEnd && colStart == colEnd) {
      return;
    }
    sheet.addMergedRegion(new CellRangeAddress(rowStart, rowEnd, colStart, colEnd));
  }

  private void text(Row row, int column, String value) {
    Cell cell = row.getCell(column);
    cell.setCellValue(value == null ? "" : value);
  }

  /** 差额列：0 / null 都显示「-」，避免整列 0.00 噪声（2026-11-12 定）。 */
  private void writeGap(Row row, int column, BigDecimal value, Styles styles) {
    if (value == null || value.signum() == 0) {
      text(row, column, "-");
      return;
    }
    row.getCell(column).setCellValue(value.doubleValue());
    row.getCell(column).setCellStyle(styles.money);
  }

  /**
   * 金额单元格的批注 = 页面里的「（计划 ¥X）」括号。
   *
   * <p>Excel 里把括号拼进数字会变成文本、破坏求和与金额格式，所以导出改写成批注：
   * 悬停可见，数字本身保持纯数值。无差异时不写批注。
   */
  private void annotate(Cell cell, String note) {
    if (!StringUtils.hasText(note)) {
      return;
    }
    Sheet sheet = cell.getSheet();
    Drawing<?> drawing = sheet.getDrawingPatriarch() == null
        ? sheet.createDrawingPatriarch() : sheet.getDrawingPatriarch();
    Comment comment = drawing.createCellComment(new XSSFClientAnchor(
        0, 0, 0, 0,
        cell.getColumnIndex() + 1, cell.getRowIndex(),
        cell.getColumnIndex() + 4, cell.getRowIndex() + 4));
    comment.setString(new XSSFRichTextString(note));
    cell.setCellComment(comment);
  }

  /** 材料 / 运输行金额括号说明：只在计划与实际不一致时给（一分钱以内视为一致）。 */
  private String purchaseAmountNote(ExportRow row, ExportGroup group) {
    if ("transport".equals(row.kind)) {
      BigDecimal freightGap = group.purchaseFreightGap;
      if (freightGap == null || freightGap.signum() == 0) {
        return null;
      }
      // 差额 = 计划运费 − 实际运费，所以计划 = 实际 + 差额
      BigDecimal actualFreight = row.purchaseAmount;
      BigDecimal planFreight = actualFreight.add(freightGap);
      return "实际运费 " + amountText(actualFreight) + "（元/吨口径按送达吨数计算）\n"
          + "计划运费 " + amountText(planFreight) + "（元/吨口径按采购吨数还原）\n"
          + "差额 " + amountText(freightGap) + "，见「采购运费差额」列";
    }
    BigDecimal plan = row.purchaseAmountPlan;
    if (plan == null || plan.signum() <= 0) {
      return null;
    }
    BigDecimal gap = plan.subtract(row.purchaseAmount);
    boolean tonsDiffer = row.purchaseTons.subtract(row.deliveredTons).abs()
        .compareTo(new BigDecimal("0.0001")) > 0;
    if (!tonsDiffer && gap.abs().compareTo(new BigDecimal("0.01")) < 0) {
      return null;
    }
    // 多材料大类的汇总行：各大类吨价不同，写单一吨价会算不平，改用「合计吨数」表述
    boolean aggregated = row.material.contains("大类");
    if (aggregated) {
      StringBuilder note = new StringBuilder();
      note.append("实际 ").append(amountText(row.purchaseAmount))
          .append(" = 各大类送达吨数 × 各自采购吨价（合计 ")
          .append(quantityText(row.deliveredTons)).append(" 吨）\n");
      note.append("计划 ").append(amountText(plan))
          .append(" = 采购单计划量（合计 ").append(quantityText(row.purchaseTons)).append(" 吨）\n");
      note.append("差额 ").append(amountText(gap)).append("，见「采购金额差额」列");
      return note.toString();
    }
    String unitPrice = row.purchaseTons.signum() > 0
        ? amountText(plan.divide(row.purchaseTons, 2, RoundingMode.HALF_UP))
        : "-";
    StringBuilder note = new StringBuilder();
    note.append("实际 ").append(amountText(row.purchaseAmount))
        .append(" = 送达 ").append(quantityText(row.deliveredTons))
        .append(" 吨 × 采购吨价 ").append(unitPrice).append(" 元/吨\n");
    note.append("计划 ").append(amountText(plan))
        .append(" = 采购 ").append(quantityText(row.purchaseTons))
        .append(" 吨 × ").append(unitPrice).append(" 元/吨\n");
    note.append("差额 ").append(amountText(gap)).append("，见「采购金额差额」列");
    if (row.basisFallback) {
      note.append("\n注意：本大类部分送货行按整批均价估算");
    }
    if (row.basisUnpriced) {
      note.append("\n注意：部分采购行缺吨数，金额按原值计入未摊分");
    }
    return note.toString();
  }

  /** 毛利金额括号说明（2026-11-12）：实际毛利与按计划量算的毛利，仅在两者不一致时给。 */
  private String marginPlanNote(ExportGroup group) {
    if (group.purchaseMarginPlanAmount == null || group.purchaseMarginAmount == null) {
      return null;
    }
    BigDecimal gap = group.purchaseMarginPlanAmount.subtract(group.purchaseMarginAmount);
    if (gap.abs().compareTo(new BigDecimal("0.01")) < 0) {
      return null;
    }
    return "实际毛利 " + amountText(group.purchaseMarginAmount)
        + "（销售额 + 送货运费 − 实际采购成本 − 实际运费）\n"
        + "计划毛利 " + amountText(group.purchaseMarginPlanAmount) + "（按采购单计划量与计划运费）\n"
        + "差额 " + amountText(gap) + "，见「采购毛利差额」列";
  }

  private static String amountText(BigDecimal value) {
    return String.format("%,.2f", value == null ? BigDecimal.ZERO : value);
  }

  private void applyLayout(Sheet sheet) {
    sheet.createFreezePane(2, HEADER_ROWS);
    sheet.setDefaultRowHeightInPoints(BODY_ROW_HEIGHT);
    int[] widths = {16, 18, 8, 18, 18, 24, 16, 12, 12, 12, 12, 14, 12, 12,
        22, 14, 12, 14, 10, 16, 12, 12, 12, 16, 12, 14, 16, 10, 14, 14, 12, 14, 12, 14, 10, 12, 10, 12};
    for (int i = 0; i < widths.length; i++) {
      sheet.setColumnWidth(i, widths[i] * 256);
    }
    for (int r = 0; r < HEADER_ROWS; r++) {
      if (sheet.getRow(r) != null) {
        sheet.getRow(r).setHeightInPoints(24f);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 样式
  // ---------------------------------------------------------------------------

  private static final class Styles {
    final CellStyle headGroup;
    final CellStyle text;
    final CellStyle money;
    final CellStyle statusOk;
    final CellStyle statusWarn;

    Styles(Workbook workbook) {
      Font headFont = workbook.createFont();
      headFont.setBold(true);
      headFont.setFontHeightInPoints((short) 10);
      headFont.setColor(IndexedColors.WHITE.getIndex());

      headGroup = workbook.createCellStyle();
      headGroup.setFont(headFont);
      headGroup.setAlignment(HorizontalAlignment.CENTER);
      headGroup.setVerticalAlignment(VerticalAlignment.CENTER);
      headGroup.setWrapText(true);
      headGroup.setFillForegroundColor(IndexedColors.TEAL.getIndex());
      headGroup.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      border(headGroup);

      Font bodyFont = workbook.createFont();
      bodyFont.setFontHeightInPoints((short) 10);

      text = workbook.createCellStyle();
      text.setFont(bodyFont);
      text.setVerticalAlignment(VerticalAlignment.CENTER);
      text.setAlignment(HorizontalAlignment.LEFT);
      text.setWrapText(true);
      border(text);

      money = workbook.createCellStyle();
      money.setFont(bodyFont);
      money.setVerticalAlignment(VerticalAlignment.CENTER);
      money.setAlignment(HorizontalAlignment.RIGHT);
      money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
      border(money);

      Font okFont = workbook.createFont();
      okFont.setFontHeightInPoints((short) 10);
      okFont.setColor(IndexedColors.GREEN.getIndex());
      statusOk = workbook.createCellStyle();
      statusOk.cloneStyleFrom(text);
      statusOk.setFont(okFont);
      statusOk.setAlignment(HorizontalAlignment.CENTER);

      Font warnFont = workbook.createFont();
      warnFont.setFontHeightInPoints((short) 10);
      warnFont.setColor(IndexedColors.ORANGE.getIndex());
      statusWarn = workbook.createCellStyle();
      statusWarn.cloneStyleFrom(text);
      statusWarn.setFont(warnFont);
      statusWarn.setAlignment(HorizontalAlignment.CENTER);
    }

    private void border(CellStyle style) {
      style.setBorderTop(BorderStyle.THIN);
      style.setBorderBottom(BorderStyle.THIN);
      style.setBorderLeft(BorderStyle.THIN);
      style.setBorderRight(BorderStyle.THIN);
      style.setTopBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
      style.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
      style.setLeftBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
      style.setRightBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
    }
  }

  // ---------------------------------------------------------------------------
  // 内部数据模型与工具
  // ---------------------------------------------------------------------------

  private static final class ExportGroup {
    String project;
    String batch;
    String batchDate;
    List<ExportRow> rows = new ArrayList<>();
    int materialRowCount;
    BigDecimal totalAmount = BigDecimal.ZERO;
    /** 采购毛利额/毛利率（批次级，后端现算的瞬时值；null=无口径或无有效行，导出显示 "-"） */
    BigDecimal purchaseMarginAmount;
    BigDecimal purchaseMarginRate;
    /** 毛利的计划值（按采购单计划量与计划运费算，2026-11-12）：括号里的那个数。 */
    BigDecimal purchaseMarginPlanAmount;
    BigDecimal purchaseMarginPlanRate;
    /** 采购毛利差额（批次级，2026-11-12 改语义）：按计划量的毛利 − 按实际送达量的毛利。 */
    BigDecimal purchaseMarginGap;
    /** 采购金额差额（批次级）= 采购计划金额 − 实际送达成本。 */
    BigDecimal purchaseAmountGap;
    /** 采购运费差额（批次级）= 计划运费 − 实际运费。 */
    BigDecimal purchaseFreightGap;
    /** 批次采购计划金额（括号里的值）。 */
    BigDecimal purchasePlanAmount;
    /** 是否已有送货单（false 时差额列留 "-"）。 */
    boolean hasDelivery;
    /** 采购运费成本（批次级）：运输单 + 送货单采购运费 + 批次运费口径，累加（2026-11-07 起）。 */
    BigDecimal purchaseFreightCost;
    /** 客户承担的送货运费（批次级）：材料区与销售区各显示一列，数值同源（2026-10-28）。 */
    BigDecimal salesFreight = BigDecimal.ZERO;
    String salesOrder = "";
    BigDecimal sales = BigDecimal.ZERO;
    String salesInvoiceDate = "";
    BigDecimal salesInvoiceAmount = BigDecimal.ZERO;
    String salesTaxRate = "-";
    BigDecimal received = BigDecimal.ZERO;
    BigDecimal outstanding = BigDecimal.ZERO;
    String receiptDate = "";
    String requiredReceiptDate = "";
    boolean monthlySettlement;
    BigDecimal interest = BigDecimal.ZERO;
    BigDecimal grossProfit = BigDecimal.ZERO;
    BigDecimal grossMargin = BigDecimal.ZERO;
    String projectManager = "";
    String status = "待补充";
  }

  private static final class ExportRow {
    String kind = "material";
    String material = "";
    String spec = "";
    String supplier = "";
    BigDecimal purchaseQuantity = BigDecimal.ZERO;
    String purchaseUnit = "";
    BigDecimal purchaseAmount = BigDecimal.ZERO;
    /** 括号里的计划值（按采购单计划量算出的金额）；0=无计划值可比。 */
    BigDecimal purchaseAmountPlan = BigDecimal.ZERO;
    /** 计划吨数 / 送达吨数：只用于金额单元格的批注说明。 */
    BigDecimal purchaseTons = BigDecimal.ZERO;
    BigDecimal deliveredTons = BigDecimal.ZERO;
    boolean basisFallback;
    boolean basisUnpriced;
    BigDecimal deliveredPackages = BigDecimal.ZERO;
    String deliveredPackageUnit = "";
    BigDecimal deliveredWeight = BigDecimal.ZERO;
    String deliveredWeightUnit = "";
    String deliveryStatus = "待送达";
    FinanceSummary finance = new FinanceSummary();
  }

  private static final class MaterialRow {
    String material = "";
    String spec = "";
    String supplier = "";
    BigDecimal purchaseQuantity = BigDecimal.ZERO;
    String purchaseUnit = "";
    BigDecimal purchaseAmount = BigDecimal.ZERO;
    BigDecimal purchaseAmountPlan = BigDecimal.ZERO;
    BigDecimal purchaseTons = BigDecimal.ZERO;
    BigDecimal deliveredTons = BigDecimal.ZERO;
    boolean basisFallback;
    boolean basisUnpriced;
    BigDecimal deliveredPackages = BigDecimal.ZERO;
    String deliveredPackageUnit = "";
    BigDecimal deliveredWeight = BigDecimal.ZERO;
    String deliveredWeightUnit = "吨";
    String deliveryStatus = "待送达";
  }

  private static final class DeliveryDetail {
    BigDecimal purchaseQuantity = BigDecimal.ZERO;
    String purchaseUnit = "";
    BigDecimal deliveredPackages = BigDecimal.ZERO;
    String deliveredPackageUnit = "";
    BigDecimal deliveredWeight = BigDecimal.ZERO;
    String deliveredWeightUnit = "吨";
    String status = "待送达";
  }

  private static final class FinanceSummary {
    BigDecimal requested = BigDecimal.ZERO;
    BigDecimal paid = BigDecimal.ZERO;
    BigDecimal unpaid = BigDecimal.ZERO;
    String paymentDate = "";
    String payee = "";
    BigDecimal invoice = BigDecimal.ZERO;
    String invoiceDate = "";
    String taxRate = "-";
  }

  private DocumentItem fallbackItem(Document supply) {
    DocumentItem item = new DocumentItem();
    item.setDocumentId(supply.getId());
    item.setMaterialName("未拆分采购数据");
    item.setSpec("");
    item.setPricingQuantity(BigDecimal.ZERO);
    item.setPricingUnit("");
    item.setUnit("");
    item.setQuantity(BigDecimal.ZERO);
    item.setUnitPrice(BigDecimal.ZERO);
    item.setAmount(nz(supply.getAmount()));
    return item;
  }

  private static List<Document> filterType(List<Document> documents, String docType) {
    return documents.stream().filter(doc -> docType.equals(doc.getDocType())).toList();
  }

  private static BigDecimal pricingQuantity(DocumentItem item) {
    BigDecimal value = item.getPricingQuantity() != null
        ? item.getPricingQuantity() : item.getQuantity();
    return nz(value);
  }

  private static String pricingUnit(DocumentItem item) {
    if (StringUtils.hasText(item.getPricingUnit())) {
      return item.getPricingUnit();
    }
    return valueOrEmpty(item.getUnit());
  }

  private static BigDecimal weightInTons(BigDecimal value, String unit) {
    String normalized = normalize(unit);
    return List.of("kg", "千克", "公斤").contains(normalized)
        ? value.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
        : value;
  }

  private static boolean isWeightUnit(String unit) {
    return List.of("吨", "t", "ton", "kg", "千克", "公斤").contains(normalize(unit));
  }

  /** 台账聚合键 = 材料大类（对账台账只按大类显示，规格不再参与分组）。 */
  private static String materialKey(String name, String spec) {
    return normalize(MaterialCategoryNormalizer.category(name));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String quantityText(BigDecimal value) {
    return nz(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }
}
