package com.aibot.purchase.service;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.DocumentItemMarketMapping;
import com.aibot.purchase.entity.LedgerRecord;
import com.aibot.purchase.entity.Project;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentItemMarketMappingMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.LedgerRecordMapper;
import com.aibot.purchase.mapper.ProjectMapper;
import com.aibot.purchase.service.market.DeliveryNetPriceService;
import com.aibot.purchase.service.market.PurchaseSalePriceService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 台账是确认后业务事实的投影。批次 = 一条台账骨架行（批次主台账行）。
 * 一个批次可导入多张采购单/送货单（2026-09-21 起）：骨架由先到者建立（采购单或送货单暂估），
 * 后续单据增量累加；采购侧金额在每次采购单确认/修正时按批次内全部已确认采购单重算（幂等）。
 * 对账台账页面/导出的材料行按「材料大类」聚合显示（{@link MaterialCategoryNormalizer}）。
 */
@Service
public class LedgerService {
  private final DocumentMapper documentMapper;
  private final DocumentItemMapper documentItemMapper;
  private final DocumentItemMarketMappingMapper itemNetMappingMapper;
  private final LedgerRecordMapper ledgerRecordMapper;
  private final BatchMapper batchMapper;
  private final ProjectMapper projectMapper;
  private final ObjectMapper objectMapper;
  private final BatchHintService batchHintService;
  private final PurchaseSalePriceService salePriceService;
  private final FreightService freightService;
  private final PurchaseCostBasisService costBasisService;

  public LedgerService(
      DocumentMapper documentMapper,
      DocumentItemMapper documentItemMapper,
      DocumentItemMarketMappingMapper itemNetMappingMapper,
      LedgerRecordMapper ledgerRecordMapper,
      BatchMapper batchMapper,
      ProjectMapper projectMapper,
      ObjectMapper objectMapper,
      BatchHintService batchHintService,
      PurchaseSalePriceService salePriceService,
      FreightService freightService,
      PurchaseCostBasisService costBasisService
  ) {
    this.documentMapper = documentMapper;
    this.documentItemMapper = documentItemMapper;
    this.itemNetMappingMapper = itemNetMappingMapper;
    this.ledgerRecordMapper = ledgerRecordMapper;
    this.batchMapper = batchMapper;
    this.projectMapper = projectMapper;
    this.objectMapper = objectMapper;
    this.batchHintService = batchHintService;
    this.salePriceService = salePriceService;
    this.freightService = freightService;
    this.costBasisService = costBasisService;
  }

  @Transactional
  public List<LedgerRecord> applyConfirmedDocument(Document document) {
    switch (document.getDocType()) {
      case "supply_order" -> {
        upsertSupplySkeleton(document);
        replayWaitingSupplements(document);
      }
      case "delivery_note" -> {
        // 送货单（销售侧发货单）先到时也能建立台账骨架；批次已有骨架则按补充单据归集。
        if (getLedgerInternal(document.getBatchId()).isEmpty()) {
          upsertDeliveryProvisional(document);
        } else {
          applySupplement(document);
        }
      }
      case "transport_note", "payment_request", "payment",
          "purchase_invoice", "invoice", "sales_invoice", "receipt" ->
          applySupplement(document);
      case "sales_settlement", "evidence_only" -> {
        document.setLedgerApplied(true);
        documentMapper.updateById(document);
      }
      default -> {
        // Receipt notes and unknown documents remain archived evidence until a deterministic rule is defined.
      }
    }
    recalcFreight(document.getBatchId());
    refreshBatchAndProject(document.getBatchId());
    return getLedgerInternal(document.getBatchId());
  }

  @Transactional
  public List<LedgerRecord> generateLedger(String userId, String projectId, String batchId) {
    requireOwnedBatch(userId, projectId, batchId);
    ledgerRecordMapper.delete(Wrappers.<LedgerRecord>lambdaQuery()
        .eq(LedgerRecord::getBatchId, batchId));

    List<Document> confirmed = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getUserId, userId)
        .eq(Document::getProjectId, projectId)
        .eq(Document::getBatchId, batchId)
        .in(Document::getStatus, "matched", "archived")
        .orderByAsc(Document::getCreatedAt));
    // 按确认时间重放：采购单或送货单先到者建骨架，其余单据增量补齐。
    for (Document document : confirmed) {
      switch (document.getDocType()) {
        case "supply_order" -> upsertSupplySkeleton(document);
        case "delivery_note" -> {
          if (getLedgerInternal(batchId).isEmpty()) {
            upsertDeliveryProvisional(document);
          } else {
            document.setLedgerApplied(false);
            documentMapper.updateById(document);
            applySupplement(document);
          }
        }
        default -> {
          document.setLedgerApplied(false);
          documentMapper.updateById(document);
          applySupplement(document);
        }
      }
    }
    // 骨架建立后，统一放行仍未归集的补充单据（applySupplement 对已归集的单据幂等跳过）。
    confirmed.stream()
        .filter(document -> "supply_order".equals(document.getDocType()))
        .findFirst()
        .ifPresent(this::replayWaitingSupplements);
    recalcFreight(batchId);
    refreshBatchAndProject(batchId);
    return getLedgerInternal(batchId);
  }

  /** 人工改运费行承运单位（按行改，空串=清空），返回刷新后的台账。 */
  @Transactional
  public List<LedgerRecord> updateFreightCarriers(
      String userId,
      String batchId,
      Map<String, String> carriers
  ) {
    requireOwnedBatch(userId, null, batchId);
    freightService.updateCarriers(batchId, carriers);
    return decoratePurchaseMargin(decorateMonthlySettlement(getLedgerInternal(batchId)));
  }

  public List<LedgerRecord> getLedger(String userId, String batchId) {    requireOwnedBatch(userId, null, batchId);
    return decoratePurchaseMargin(decorateMonthlySettlement(getLedgerInternal(batchId)));
  }

  public List<LedgerRecord> getLedgerByProject(String userId, String projectId) {
    requireOwnedProject(userId, projectId);
    // 仅返回项目下有效（未删除）批次的台账，避免已删批次的台账行从项目维度漏出
    List<Batch> batches = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
        .eq(Batch::getProjectId, projectId));
    if (batches.isEmpty()) {
      return List.of();
    }
    return decoratePurchaseMargin(decorateMonthlySettlement(
        ledgerRecordMapper.selectList(Wrappers.<LedgerRecord>lambdaQuery()
            .eq(LedgerRecord::getProjectId, projectId)
            .in(LedgerRecord::getBatchId, batches.stream().map(Batch::getId).toList())
            .orderByAsc(LedgerRecord::getPurchaseDate)
            .orderByAsc(LedgerRecord::getCreatedAt))));
  }

  @Transactional
  public List<LedgerRecord> updateBatchSummary(
      String userId,
      String batchId,
      Map<String, Object> edits
  ) {
    requireOwnedBatch(userId, null, batchId);
    List<LedgerRecord> records = getLedgerInternal(batchId);
    if (records.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "当前批次还没有台账数据");
    }
    LedgerRecord record = records.get(0);
    if (edits.containsKey("receiptDate")) {
      record.setReceiptDate(parseDate(edits.get("receiptDate")));
    }
    if (edits.containsKey("requiredReceiptDate")) {
      record.setRequiredReceiptDate(parseDate(edits.get("requiredReceiptDate")));
    }
    if (edits.containsKey("projectManager")) {
      record.setProjectManager(text(edits.get("projectManager")));
    }
    if (edits.containsKey("salesOrder")) {
      record.setSalesOrder(text(edits.get("salesOrder")));
    }
    if (edits.containsKey("salesAmount")) {
      record.setSalesAmount(parseDecimal(edits.get("salesAmount")));
    }
    if (edits.containsKey("isMonthlySettlement")) {
      Object value = edits.get("isMonthlySettlement");
      record.setIsMonthlySettlement(Boolean.TRUE.equals(value)
          || "true".equalsIgnoreCase(String.valueOf(value)));
    }
    record.setUpdatedAt(LocalDateTime.now());
    ledgerRecordMapper.updateById(record);
    refreshBatchAndProject(batchId);
    return decoratePurchaseMargin(decorateMonthlySettlement(getLedgerInternal(batchId)));
  }

  public List<LedgerRecord> suggestTargets(Document document) {
    // 项目 + 批次已经唯一确定台账。多条采购记录只是同一台账内的数据行，
    // 不应再次要求用户选择“目标台账”。
    return List.of();
  }

  public LedgerRecord requireTarget(String batchId, String ledgerId) {
    LedgerRecord record = ledgerRecordMapper.selectById(ledgerId);
    if (record == null || !batchId.equals(record.getBatchId())) {
      throw new ApiException(HttpStatus.NOT_FOUND, "目标台账不存在");
    }
    return record;
  }

  private void upsertSupplySkeleton(Document supply) {
    // 一个批次可有多张采购单：全部落在批次主台账行上（首条），采购侧字段按批次内
    // 全部已确认采购单重算——幂等，重复确认/修正不会重复累加。
    List<LedgerRecord> existing = getLedgerInternal(supply.getBatchId());
    LedgerRecord record;
    if (!existing.isEmpty()) {
      record = existing.get(0);
    } else {
      record = new LedgerRecord();
      record.setCreatedAt(LocalDateTime.now());
    }
    if (record.getSourceDocumentId() == null) {
      record.setSourceDocumentId(supply.getId()); // 首张采购单留痕（不再要求唯一）
    }

    record.setProjectId(supply.getProjectId());
    record.setBatchId(supply.getBatchId());
    record.setPurchaseDate(earliestSupplyDate(supply.getBatchId()));
    record.setSupplier(supplySuppliers(supply.getBatchId()));
    record.setMaterial(supplyMaterialSummary(supply.getBatchId()));
    BigDecimal purchase = purchaseActualAmount(supply.getBatchId());
    // 运输费用 = 批次内运输单 + 送货单上标注的运费条目（transportFee），
    // 收养暂估骨架时保留送货单已计入的运费。
    BigDecimal freight = nz(record.getFreight());
    record.setPurchaseAmount(purchase);
    record.setFreight(freight);
    record.setDeliveryAmount(nz(record.getDeliveryAmount()));
    record.setDeliveredPackageQuantity(nz(record.getDeliveredPackageQuantity()));
    record.setDeliveredWeight(nz(record.getDeliveredWeight()));
    record.setRequestedAmount(nz(record.getRequestedAmount()));
    record.setPaidAmount(nz(record.getPaidAmount()));
    record.setUnpaidAmount(purchase.add(freight).subtract(nz(record.getPaidAmount())).max(BigDecimal.ZERO));
    record.setInvoiceAmount(nz(record.getInvoiceAmount()));
    record.setSalesAmount(nz(record.getSalesAmount()));
    record.setReceivedAmount(nz(record.getReceivedAmount()));
    record.setOutstanding(nz(record.getSalesAmount()).subtract(nz(record.getReceivedAmount())).max(BigDecimal.ZERO));
    record.setStatus(determineStatus(record));
    record.setUpdatedAt(LocalDateTime.now());

    // 已有主键 → update；全新骨架行 → insert。
    if (record.getId() == null) {
      ledgerRecordMapper.insert(record);
    } else {
      ledgerRecordMapper.updateById(record);
    }
    supply.setLedgerApplied(true);
    documentMapper.updateById(supply);
  }

  /** 批次内全部已确认采购单（确认流程中当前单已先落库为 matched，天然包含）。 */
  private List<Document> confirmedSupplies(String batchId) {
    return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "supply_order")
        .in(Document::getStatus, "matched", "archived")
        .orderByAsc(Document::getCreatedAt));
  }

  /**
   * 批次采购金额（成本口径，2026-11-12 起）：按送货单实际送达量折算。
   *
   * <p>取数统一走 {@link PurchaseCostBasisService}——有送货单时 = 采购吨价 × 送达吨数，
   * 一张送货单都没有时退回采购单金额（与改造前完全一致）。采购单上的「保留差异」决策
   * （keep_source）在基准服务里按单据总额摊回材料行，金额与改造前一致。
   */
  private BigDecimal purchaseActualAmount(String batchId) {
    PurchaseCostBasisService.Basis basis = costBasisService.basis(batchId);
    return basis.isEmpty() ? BigDecimal.ZERO : basis.actualAmount();
  }

  /** 批次业务日期取多张采购单的最早单据日期。 */
  private LocalDate earliestSupplyDate(String batchId) {
    return confirmedSupplies(batchId).stream()
        .map(Document::getDocDate)
        .filter(java.util.Objects::nonNull)
        .min(LocalDate::compareTo)
        .orElse(null);
  }

  /** 供应商去重拼接（多张采购单可能来自不同供应商）。 */
  private String supplySuppliers(String batchId) {
    List<String> suppliers = confirmedSupplies(batchId).stream()
        .map(Document::getSupplierName)
        .filter(StringUtils::hasText)
        .distinct()
        .toList();
    return suppliers.isEmpty() ? null : String.join(" / ", suppliers);
  }

  /** 批次材料大类汇总（多张采购单累加后按大类归并，去重拼接）。 */
  private String supplyMaterialSummary(String batchId) {
    List<String> categories = new java.util.ArrayList<>();
    for (Document supply : confirmedSupplies(batchId)) {
      for (DocumentItem item : getDocumentItems(supply.getId())) {
        String category = MaterialCategoryNormalizer.category(item.getMaterialName());
        if (StringUtils.hasText(category) && !categories.contains(category)) {
          categories.add(category);
        }
      }
    }
    return categories.isEmpty() ? null : String.join(" / ", categories);
  }

  /**
   * 送货单（销售侧发货单）先于采购单确认时建立的「暂估骨架」：
   * source_document_id 留空，销售金额/运费/送达情况按送货单落账，采购侧列留 0；
   * 采购单后到时由 {@link #upsertSupplySkeleton(Document)} 归位为同一条批次主台账行。
   */
  private void upsertDeliveryProvisional(Document delivery) {
    LedgerRecord record = new LedgerRecord();
    record.setProjectId(delivery.getProjectId());
    record.setBatchId(delivery.getBatchId());
    record.setPurchaseDate(delivery.getDocDate());
    List<DocumentItem> items = getDocumentItems(delivery.getId());
    // 销售金额与确认入账同口径：网价锁定值优先，无锁定记录回退单据金额/明细合计
    record.setSalesAmount(deliveryContributedSales(delivery, items));
    // 采购运费不再挂单据：批次运费由 FreightService.rebuild 生成运费行后统一回填（2026-11-11）
    record.setPurchaseAmount(BigDecimal.ZERO);
    record.setMaterial(extractMaterial(delivery));
    applyDeliveryMeasurements(record, items, false);
    recalculate(record);
    record.setCreatedAt(LocalDateTime.now());
    record.setUpdatedAt(LocalDateTime.now());
    ledgerRecordMapper.insert(record);
    delivery.setLedgerApplied(true);
    delivery.setTargetLedgerId(record.getId());
    documentMapper.updateById(delivery);
  }

  private void applySupplement(Document document) {
    if (Boolean.TRUE.equals(document.getLedgerApplied())) {
      return;
    }
    if (getLedgerInternal(document.getBatchId()).isEmpty()) {
      return;
    }
    LedgerRecord record = findTargetLedger(document);
    applyContribution(record, document, getDocumentItems(document.getId()), false);
    recalculate(record);
    record.setUpdatedAt(LocalDateTime.now());
    ledgerRecordMapper.updateById(record);
    document.setLedgerApplied(true);
    document.setTargetLedgerId(record.getId());
    documentMapper.updateById(document);
  }

  /**
   * 删除回退涉及的补充单据类型（与 {@link #applyContribution} 支持的数值归集类型一致）。
   * sales_settlement / evidence_only 只归档不写台账数值，删除时无需回退。
   */
  private static final List<String> LEDGER_SUPPLEMENT_TYPES = List.of(
      "delivery_note", "transport_note", "payment_request", "payment",
      "purchase_invoice", "invoice", "sales_invoice", "receipt");

  /**
   * 已入库单据删除后的台账回退。调用前提：单据已完成软删（{@code confirmedSupplies} 等
   * 依赖 @TableLogic 的查询已自动排除本单），入参为删除前的单据与明细快照。
   * <ul>
   *   <li>采购单：采购侧按批次内剩余已确认采购单重算（复用骨架幂等重算，与编辑同路径）；</li>
   *   <li>送货单/运输/请款/付款/发票/收款：按删除前贡献做减法回退；</li>
   *   <li>批次内已无 matched 单据时清除台账投影行（与空批次重新生成台账的语义一致）。</li>
   * </ul>
   * 最后统一重算批次/项目金额快照。
   */
  @Transactional
  public void removeConfirmedDocument(Document document, List<DocumentItem> items) {
    List<LedgerRecord> records = getLedgerInternal(document.getBatchId());
    if (!records.isEmpty()) {
      if ("supply_order".equals(document.getDocType())) {
        removeSupplyContribution(document, records.get(0));
      } else if (LEDGER_SUPPLEMENT_TYPES.contains(document.getDocType())) {
        // 减法回退目标行 = 当初归集的目标行（targetLedgerId 精确命中，与 applySupplement 同口径）
        LedgerRecord target = findTargetLedger(document);
        applyContribution(target, document, items, true);
        recalculate(target);
        target.setUpdatedAt(LocalDateTime.now());
        ledgerRecordMapper.updateById(target);
        // updateById 默认跳过 null 字段：销项发票删空后，加法时写入的开票日期/税率残留须显式置 NULL
        if ("sales_invoice".equals(document.getDocType())
            && nz(target.getSalesInvoiceAmount()).compareTo(BigDecimal.ZERO) == 0) {
          ledgerRecordMapper.update(null, Wrappers.<LedgerRecord>lambdaUpdate()
              .eq(LedgerRecord::getId, target.getId())
              .set(LedgerRecord::getSalesInvoiceDate, null)
              .set(LedgerRecord::getSalesTaxRate, null));
        }
      }
    }
    Long confirmedCount = documentMapper.selectCount(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, document.getBatchId())
        .eq(Document::getStatus, "matched"));
    if (confirmedCount == 0) {
      ledgerRecordMapper.delete(Wrappers.<LedgerRecord>lambdaQuery()
          .eq(LedgerRecord::getBatchId, document.getBatchId()));
    }
    recalcFreight(document.getBatchId());
    refreshBatchAndProject(document.getBatchId());
  }

  /**
   * 采购单删除后的采购侧回退：
   * 仍有剩余已确认采购单 → 复用 {@link #upsertSupplySkeleton} 幂等重算并落库
   * （软删过滤已排除被删单；该方法自取最新记录，调用方传入的 record 仅用于无剩余时的归零）；
   * 已无剩余采购单 → 骨架若由采购单建立（source_document_id 非空）则采购侧归零并落库，
   * 送货单暂估骨架（source_document_id 为空）的销售/运费/送达数据不受影响。
   */
  private void removeSupplyContribution(Document deletedSupply, LedgerRecord record) {
    List<Document> remaining = confirmedSupplies(deletedSupply.getBatchId());
    if (!remaining.isEmpty()) {
      upsertSupplySkeleton(remaining.get(0));
      return;
    }
    if (StringUtils.hasText(record.getSourceDocumentId())) {
      record.setSourceDocumentId(null);
      record.setPurchaseDate(null);
      record.setSupplier(null);
      record.setMaterial(null);
      record.setPurchaseAmount(BigDecimal.ZERO);
      recalculate(record);
      record.setUpdatedAt(LocalDateTime.now());
      // updateById 默认跳过 null 字段：可空列的清空（留痕/日期/供应商/材料）必须显式置 NULL，
      // 其余非空字段（采购额/未付/状态）经实体一并写入
      ledgerRecordMapper.update(record, Wrappers.<LedgerRecord>lambdaUpdate()
          .eq(LedgerRecord::getId, record.getId())
          .set(LedgerRecord::getSourceDocumentId, null)
          .set(LedgerRecord::getPurchaseDate, null)
          .set(LedgerRecord::getSupplier, null)
          .set(LedgerRecord::getMaterial, null));
    }
  }

  @Transactional
  public List<LedgerRecord> correctConfirmedDocument(
      Document before,
      List<DocumentItem> beforeItems,
      Document after
  ) {
    return correctConfirmedDocument(before, beforeItems, after, null);
  }

  /**
   * 修正已入库单据对台账的贡献。
   *
   * @param beforeDeliverySales 送货单「编辑前」的销售贡献额（含客户承担运费）。
   *   编辑场景必须传：明细会被整表重插、锁定记录随之重建，事后再想按旧明细算回旧贡献已经算不出来
   *   （旧行 id 不在库里了），会导致台账销售额被重复叠加。传 null 时按当前锁定记录重算（删除等场景）。
   */
  @Transactional
  public List<LedgerRecord> correctConfirmedDocument(
      Document before,
      List<DocumentItem> beforeItems,
      Document after,
      BigDecimal beforeDeliverySales
  ) {
    if (!before.getDocType().equals(after.getDocType())) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "已入库数据暂不支持修改业务类型，请新建正确类型的数据");
    }
    if ("supply_order".equals(after.getDocType())) {
      upsertSupplySkeleton(after);
    } else if (List.of(
        "delivery_note", "transport_note", "payment_request", "payment",
        "purchase_invoice", "invoice", "sales_invoice", "receipt")
        .contains(after.getDocType())) {
      if ("delivery_note".equals(after.getDocType())
          && getLedgerInternal(after.getBatchId()).isEmpty()) {
        upsertDeliveryProvisional(after);
      } else {
        LedgerRecord record = findTargetLedger(before);
        if ("delivery_note".equals(after.getDocType()) && beforeDeliverySales != null) {
          // 编辑场景：销售贡献按「减去调用方算好的旧值 + 加上重锁后的新值」精确换算，
          // 不再叠加 adjustDeliverySales 的差额（否则同一次编辑会被算两遍，销售额翻倍）
          record.setSalesAmount(nonNegative(
              nz(record.getSalesAmount()).subtract(beforeDeliverySales)));
          applyDeliveryMeasurements(record, beforeItems, true);
          applyContribution(record, after, getDocumentItems(after.getId()), false);
        } else {
          applyContribution(record, before, beforeItems, true);
          applyContribution(record, after, getDocumentItems(after.getId()), false);
        }
        recalculate(record);
        record.setUpdatedAt(LocalDateTime.now());
        ledgerRecordMapper.updateById(record);
        after.setLedgerApplied(true);
        after.setTargetLedgerId(record.getId());
        documentMapper.updateById(after);
      }
    }
    recalcFreight(after.getBatchId());
    refreshBatchAndProject(after.getBatchId());
    return getLedgerInternal(after.getBatchId());
  }

  /**
   * 送货单销售金额：网价锁定值合计（unmatched 行金额为 0 不计）。
   * 无锁定记录（历史数据/锁定失败）返回 null，调用方回退旧口径。
   */
  private BigDecimal deliveryLockedSales(Document document, List<DocumentItem> items) {
    List<DocumentItemMarketMapping> mappings = itemNetMappingMapper.selectList(
        Wrappers.<DocumentItemMarketMapping>lambdaQuery()
            .eq(DocumentItemMarketMapping::getDocumentId, document.getId()));
    if (mappings.isEmpty()) {
      return null;
    }
    // 只统计「传入的这批明细行」对应的锁定记录：编辑单据时明细整表删了重插，行 id 全新，
    // 旧行锁定记录会变成孤儿；按 document_id 全量汇总会把它们重复计入，表现为编辑一次
    // 销售金额就多叠一整套（2026-11-08 修）。用调用方传入的 items 而不是重查数据库，
    // 是因为「编辑前」的旧行此刻已经不在库里，必须按 beforeItems 的 id 才能算回旧贡献。
    Set<String> itemIds = (items == null ? List.<DocumentItem>of() : items).stream()
        .map(DocumentItem::getId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    if (itemIds.isEmpty()) {
      return null;
    }
    return mappings.stream()
        .filter(mapping -> itemIds.contains(mapping.getDocumentItemId()))
        .filter(m -> !"unmatched".equals(m.getPriceBasis()))
        .map(DocumentItemMarketMapping::getAmount)
        .map(LedgerService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * 送货单当前贡献口径的**货款**（不含客户承担的运费）：锁定值优先，无锁定记录回退旧口径
   * （单据总额，缺失时取明细合计）。入账与修正差额重算共用，保证口径一致。
   *
   * <p>2026-11-11：运费不再并进这里——销售运费由 FreightService 生成运费行后统一进
   * {@code ledger_records.sales_amount}（{@link #recalcFreight}），避免「单据运费 + 运费表」两头各算一次。
   */
  public BigDecimal deliveryContributedSales(Document document, List<DocumentItem> items) {
    BigDecimal locked = deliveryLockedSales(document, items);
    if (locked != null) {
      return locked;
    }
    BigDecimal sales = nz(document.getAmount());
    if (sales.compareTo(BigDecimal.ZERO) <= 0 && items != null) {
      return items.stream()
          .map(DocumentItem::getAmount)
          .map(LedgerService::nz)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    return sales;
  }

  /**
   * AI 网价兜底后按前后差额重算送货单的销售贡献（只动差额，不重放整笔单据）。
   */
  @Transactional
  public void adjustDeliverySales(Document document, BigDecimal beforeSales, BigDecimal afterSales) {
    BigDecimal delta = nz(afterSales).subtract(nz(beforeSales));
    if (delta.compareTo(BigDecimal.ZERO) == 0) {
      return;
    }
    LedgerRecord record = findTargetLedger(document);
    if (record == null) {
      return;
    }
    record.setSalesAmount(nonNegative(nz(record.getSalesAmount()).add(delta)));
    recalculate(record);
    record.setUpdatedAt(LocalDateTime.now());
    ledgerRecordMapper.updateById(record);
    refreshBatchAndProject(document.getBatchId());
  }

  /** 内部取单（供网价服务复用，避免跨服务循环依赖 DocumentService）。 */
  public Document getDocumentInternal(String documentId) {
    return documentMapper.selectById(documentId);
  }

  private void applyContribution(
      LedgerRecord record,
      Document document,
      List<DocumentItem> items,
      boolean subtract
  ) {
    BigDecimal amount = nz(document.getAmount());
    BigDecimal signedAmount = subtract ? amount.negate() : amount;
    switch (document.getDocType()) {
      case "delivery_note" -> {
        // 送货单 = 销售侧发货单：销售金额优先读网价锁定值（明细行锁定金额合计），
        // 无锁定记录时回退旧口径（单据总额，缺失时取明细合计）；
        // 采购运费（transportFee）不再在此累加：2026-11-04 方案 B 起运输费用列
        // 由 recalcFreight 按批次统一重算（与采购毛利率同源），避免口径分裂。
        BigDecimal sales = deliveryContributedSales(document, items);
        if (sales.compareTo(BigDecimal.ZERO) > 0) {
          record.setSalesAmount(nonNegative(nz(record.getSalesAmount()).add(
              subtract ? sales.negate() : sales)));
        }
        applyDeliveryMeasurements(record, items, subtract);
      }
      case "transport_note" -> {
        // 运输单金额同样并入批次级 recalcFreight 重算，不在此累加
      }
      case "payment_request" ->
          record.setRequestedAmount(nonNegative(nz(record.getRequestedAmount()).add(signedAmount)));
      case "payment" ->
          record.setPaidAmount(nonNegative(nz(record.getPaidAmount()).add(signedAmount)));
      case "purchase_invoice", "invoice" -> {
        record.setInvoiceAmount(nonNegative(nz(record.getInvoiceAmount()).add(signedAmount)));
        String taxRate = subtract ? null : extractText(document, "taxRate");
        if (StringUtils.hasText(taxRate)) {
          record.setTaxRate(taxRate);
        }
      }
      case "sales_invoice" -> {
        record.setSalesInvoiceAmount(nonNegative(
            nz(record.getSalesInvoiceAmount()).add(signedAmount)));
        if (!subtract) {
          record.setSalesInvoiceDate(document.getDocDate());
          String taxRate = extractText(document, "taxRate");
          if (StringUtils.hasText(taxRate)) {
            record.setSalesTaxRate(taxRate);
          }
        }
      }
      case "receipt" ->
          record.setReceivedAmount(nonNegative(nz(record.getReceivedAmount()).add(signedAmount)));
      default -> {
        // 其他资料只归档，不参与台账数值。
      }
    }
  }

  private void applyDeliveryMeasurements(
      LedgerRecord record,
      List<DocumentItem> items,
      boolean subtract
  ) {
    BigDecimal packageQuantity = items.stream()
        .map(DocumentItem::getPackageQuantity)
        .map(LedgerService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    String packageUnit = commonUnit(items.stream().map(DocumentItem::getPackageUnit).toList());
    if (packageQuantity.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal signed = subtract ? packageQuantity.negate() : packageQuantity;
      record.setDeliveredPackageQuantity(nonNegative(
          nz(record.getDeliveredPackageQuantity()).add(signed)));
      record.setDeliveredPackageUnit(mergeUnit(record.getDeliveredPackageUnit(), packageUnit));
    }

    BigDecimal weightInTons = items.stream()
        .map(this::weightInTons)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (weightInTons.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal signed = subtract ? weightInTons.negate() : weightInTons;
      record.setDeliveredWeight(nonNegative(nz(record.getDeliveredWeight()).add(signed)));
      record.setDeliveredWeightUnit("吨");
    }
  }

  private List<DocumentItem> getDocumentItems(String documentId) {
    return documentItemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, documentId)
        .orderByAsc(DocumentItem::getSortOrder));
  }

  private BigDecimal weightInTons(DocumentItem item) {
    BigDecimal weight = nz(item.getTotalWeight());
    if (weight.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    String unit = normalizeUnit(item.getWeightUnit());
    if (List.of("kg", "千克", "公斤").contains(unit)) {
      return weight.divide(new BigDecimal("1000"));
    }
    if (List.of("t", "ton", "吨").contains(unit)) {
      return weight;
    }
    return BigDecimal.ZERO;
  }

  private void replayWaitingSupplements(Document supply) {
    documentMapper.selectList(Wrappers.<Document>lambdaQuery()
            .eq(Document::getUserId, supply.getUserId())
            .eq(Document::getProjectId, supply.getProjectId())
            .eq(Document::getBatchId, supply.getBatchId())
            .ne(Document::getDocType, "supply_order")
            .in(Document::getStatus, "matched", "archived")
            .eq(Document::getLedgerApplied, false)
            .orderByAsc(Document::getCreatedAt))
        .forEach(document -> {
          try {
            applySupplement(document);
          } catch (ApiException ignored) {
            // Ambiguous records stay unapplied and will surface a ledger-selection card.
          }
        });
  }

  private LedgerRecord findTargetLedger(Document document) {
    List<LedgerRecord> records = getLedgerInternal(document.getBatchId());
    if (records.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "当前批次还没有台账骨架，请先确认采购单或送货单");
    }
    if (StringUtils.hasText(document.getTargetDocumentId())) {
      Document targetDocument = documentMapper.selectById(document.getTargetDocumentId());
      if (targetDocument != null) {
        if ("supply_order".equals(targetDocument.getDocType())) {
          LedgerRecord target = records.stream()
              .filter(record -> targetDocument.getId().equals(record.getSourceDocumentId()))
              .findFirst()
              .orElse(null);
          if (target != null) {
            return target;
          }
        }
        if (StringUtils.hasText(targetDocument.getTargetLedgerId())) {
          LedgerRecord target = records.stream()
              .filter(record -> targetDocument.getTargetLedgerId().equals(record.getId()))
              .findFirst()
              .orElse(null);
          if (target != null) {
            return target;
          }
        }
      }
    }
    if (records.size() == 1) {
      return records.get(0);
    }
    if (StringUtils.hasText(document.getTargetLedgerId())) {
      LedgerRecord selected = records.stream()
          .filter(record -> document.getTargetLedgerId().equals(record.getId()))
          .findFirst()
          .orElse(null);
      if (selected != null) {
        return selected;
      }
    }
    if (StringUtils.hasText(document.getSupplierName())) {
      List<LedgerRecord> supplierMatches = records.stream()
          .filter(record -> sameParty(record.getSupplier(), document.getSupplierName()))
          .toList();
      if (supplierMatches.size() == 1) {
        return supplierMatches.get(0);
      }
    }
    // 同一批次始终只有一个业务台账。历史表中可能存在多条采购投影，
    // 批次级资金数据落在稳定的首行，前端按批次合并展示。
    return records.get(0);
  }

  private void recalculate(LedgerRecord record) {
    record.setUnpaidAmount(nz(record.getPurchaseAmount()).add(nz(record.getFreight()))
        .subtract(nz(record.getPaidAmount())).max(BigDecimal.ZERO));
    record.setOutstanding(nz(record.getSalesAmount()).subtract(nz(record.getReceivedAmount()))
        .max(BigDecimal.ZERO));
    record.setStatus(determineStatus(record));
  }

  private void refreshBatchAndProject(String batchId) {
    Batch batch = batchMapper.selectById(batchId);
    if (batch == null) {
      return;
    }
    List<LedgerRecord> records = getLedgerInternal(batchId);
    BigDecimal purchase = records.stream()
        .map(record -> nz(record.getPurchaseAmount()).add(nz(record.getFreight())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal sales = records.stream().map(LedgerRecord::getSalesAmount).map(LedgerService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal profit = sales.subtract(purchase);
    // 毛利率分母 = 销售总金额（含客户承担的送货运费，总额法，与材料区同一口径）。
    BigDecimal marginRate = sales.compareTo(BigDecimal.ZERO) > 0
        ? profit.multiply(new BigDecimal("100"))
            .divide(sales, 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;
    if (!records.isEmpty()) {
      LedgerRecord summary = records.get(0);
      summary.setGrossProfit(sales.compareTo(BigDecimal.ZERO) > 0 ? profit : BigDecimal.ZERO);
      summary.setGrossMargin(marginRate);
      summary.setUpdatedAt(LocalDateTime.now());
      ledgerRecordMapper.updateById(summary);
    }
    batch.setPurchaseAmount(purchase);
    batch.setSalesAmount(sales);
    batch.setProfitAmount(profit);
    batch.setMarginRate(marginRate);
    int progress = records.isEmpty() ? 0 : stageProgress(records);
    batch.setProgress(progress);
    batch.setStatus(records.isEmpty() ? "待确认" : progress == 100 ? "已完成" : "进行中");
    if (!StringUtils.hasText(batch.getMaterialSummary())) {
      batch.setMaterialSummary(records.stream()
          .map(LedgerRecord::getMaterial)
          .filter(StringUtils::hasText)
          .distinct()
          .collect(Collectors.joining(" / ")));
    }
    if (batch.getBusinessDate() == null) {
      batch.setBusinessDate(records.stream()
          .map(LedgerRecord::getPurchaseDate)
          .filter(java.util.Objects::nonNull)
          .findFirst()
          .orElse(null));
    }
    batch.setUpdatedAt(LocalDateTime.now());
    batchMapper.updateById(batch);

    Project project = projectMapper.selectById(batch.getProjectId());
    if (project == null) {
      return;
    }
    List<Batch> batches = batchMapper.selectList(Wrappers.<Batch>lambdaQuery()
        .eq(Batch::getProjectId, project.getId()));
    BigDecimal projectPurchase = batches.stream().map(Batch::getPurchaseAmount).map(LedgerService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal projectSales = batches.stream().map(Batch::getSalesAmount).map(LedgerService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    project.setPurchaseAmount(projectPurchase);
    project.setSalesAmount(projectSales);
    BigDecimal projectProfit = projectSales.subtract(projectPurchase);
    project.setProfitAmount(projectProfit);
    project.setMarginRate(projectSales.compareTo(BigDecimal.ZERO) > 0
        ? projectProfit.multiply(new BigDecimal("100"))
            .divide(projectSales, 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO);
    project.setProgress(batches.isEmpty() ? 0
        : (int) Math.round(batches.stream().map(Batch::getProgress).mapToInt(value -> value == null ? 0 : value).average().orElse(0)));
    project.setUpdatedAt(LocalDateTime.now());
    projectMapper.updateById(project);
  }

  private List<LedgerRecord> getLedgerInternal(String batchId) {
    return ledgerRecordMapper.selectList(Wrappers.<LedgerRecord>lambdaQuery()
        .eq(LedgerRecord::getBatchId, batchId)
        .orderByAsc(LedgerRecord::getPurchaseDate)
        .orderByAsc(LedgerRecord::getCreatedAt));
  }

  // ---------------------------------------------------------------------------
  // 采购毛利（批次级，读台账时按当前批次口径现算，不落库）
  // ---------------------------------------------------------------------------

  /**
   * 批次采购毛利估算结果（瞬时值）：估算货款、客户承担的送货运费、采购运费成本、
   * 含运费采购成本、毛利额、毛利率，以及「计划量 / 实际送达量」两套口径的差额。
   *
   * <p>{@code sales} 是<b>货款</b>（销售单价 × 吨数，不含送货运费）；
   * {@code salesFreight} 是客户承担的运费，与货款一起构成总收入（毛利率分母），
   * 同时作为毛利额加项——与销售区「销售总金额已含运费」的口径完全一致（2026-10-28 定）。
   *
   * <p>2026-11-06：{@code freight} 与毛利解耦——没有定价口径时毛利算不出（amount/rate 为 null），
   * 但采购运费成本照样有值（口径推算 / 运输单 / 送货单标注），否则「配了运费口径台账却不显示运费」。
   * {@code note} 说明毛利算不出的原因：no_policy=缺定价口径 / no_supply=无已确认采购单 /
   * no_priced_row=采购明细缺吨数或金额；能算出毛利时为 null。
   *
   * <p>2026-11-12 三组差额（都以「计划量 − 实际送达量」为方向，正数=少送）：
   * {@code costGap} 采购金额差额、{@code freightGap} 采购运费差额、
   * {@code marginGap} 毛利差额 = {@code revenueGap} − costGap − freightGap。
   */
  public record PurchaseMargin(
      BigDecimal sales,
      BigDecimal salesFreight,
      BigDecimal freight,
      BigDecimal cost,
      BigDecimal amount,
      BigDecimal rate,
      String note,
      /** 收入差：按采购量估算的货款 − 按送货量的实际货款；没有送货单时为 null（无处比较）。 */
      BigDecimal revenueGap,
      /** 采购实际成本（材料口径，不含运费）：采购吨价 × 送达吨数。 */
      BigDecimal actualCost,
      /** 采购计划金额（采购单口径）；无采购单时为 null。 */
      BigDecimal planCost,
      /** 计划运费：元/吨口径按采购吨数还原后的运费合计。 */
      BigDecimal planFreight,
      /** 采购金额差额 = 计划金额 − 实际成本。 */
      BigDecimal costGap,
      /** 采购运费差额 = 计划运费 − 实际运费（只有元/吨口径会因量差产生）。 */
      BigDecimal freightGap,
      /** 毛利差额 = 收入差 − 采购金额差额 − 运费差额（三项齐备才给值）。 */
      BigDecimal marginGap,
      /** 采购成本基准逐材料大类明细（差额详情弹框直接用它）。 */
      List<PurchaseCostBasisService.MaterialBasis> materials,
      /** 基准里是否有送货单（false=实际值等于计划值，无差额可比）。 */
      boolean hasDelivery,
      /** 是否有送货明细因算不出吨数而没计入实际成本。 */
      int unpricedDeliveryRows
  ) {}

  /**
   * 批次采购毛利（2026-10-27 起毛利率口径以采购价为基数）：
   * 采购成本 = 实际送达量成本（见 {@link PurchaseCostBasisService}）+ 采购运费成本。
   *
   * <p>2026-11-11 改销售口径：<b>以送货数量为准</b>——销售额取已确认送货单的锁定货款合计
   * （送货单是全局实际重量）；只有一张送货单都还没有时，才退回「采购明细吨数 × 销售单价」估算。
   * 采购成本同样按送货重量摊：采购单价 × 送货吨数，采购多买未发出的部分不计本批成本。
   *
   * <p>2026-11-12：三组差额与成本基准一起返回，台账「采购金额差额 / 采购运费差额 /
   * 采购毛利差额」三列与差额详情弹框全部读这里，不再各自算一遍。
   *
   * <p>毛利额 = 销售额 + 送货运费 − 采购成本，
   * 毛利率 = 毛利额 ÷（销售额 + 送货运费）× 100（总额法，与销售区同口径）。
   */
  public PurchaseMargin supplyMargin(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return null;
    }
    Map<String, Object> hints = batchHintService.deliveryNoteHints(batchMapper.selectById(batchId));
    DeliveryNetPriceService.PricingPolicy policy =
        DeliveryNetPriceService.PricingPolicy.fromBatchHints(hints);
    List<Document> supplies = confirmedSupplies(batchId);
    List<DocumentItem> supplyItems = supplies.isEmpty() ? List.of()
        : documentItemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
            .in(DocumentItem::getDocumentId, supplies.stream().map(Document::getId).toList())
            .orderByAsc(DocumentItem::getSortOrder));
    // 运费成本与定价口径正交：没有口径也要算运费（运费行由 FreightService 统一维护，2026-11-11）
    BigDecimal freight = batchFreightCost(batchId);
    BigDecimal salesFreight = batchSalesFreight(batchId);
    PurchaseCostBasisService.Basis basis = costBasisService.basis(batchId);
    boolean hasCost = !basis.isEmpty();
    BigDecimal planCost = hasCost ? basis.planAmount() : null;
    BigDecimal planFreight = batchPlanFreight(batchId, basis.planTons());
    BigDecimal costGap = hasCost && basis.hasDelivery() ? basis.amountGap() : null;
    BigDecimal freightGap = costGap == null ? null : planFreight.subtract(freight)
        .setScale(2, RoundingMode.HALF_UP);
    if (supplyItems.isEmpty() || !hasCost) {
      return new PurchaseMargin(null, salesFreight, freight, null, null, null, "no_supply",
          null, null, planCost, planFreight, costGap, freightGap, null, basis.materials(),
          basis.hasDelivery(), basis.unpricedDeliveryRows());
    }
    // 采购成本 = 采购单价 × 送货重量（2026-11-11 定）：
    // 采购与送货数量不一致时，没发出去的货不计入本批成本（留在库存里）；
    // 一张送货单都没有时才退回采购单金额（基准服务内部已处理）。
    BigDecimal purchase = basis.actualAmount();

    // 销售口径（2026-11-11）：以送货数量为准——实际锁定货款优先；
    // 一张送货单都还没有时，才退回「采购明细吨数 × 推算销售单价」的估算
    BigDecimal deliverySales = deliveryGoodsTotal(batchId);
    BigDecimal estimatedSales = BigDecimal.ZERO;
    if (policy != null) {
      Map<String, PurchaseSalePriceService.Price> prices =
          salePriceService.pricesFor(supplyItems, policy);
      for (DocumentItem item : supplyItems) {
        BigDecimal tons = salePriceService.pricingTons(item);
        BigDecimal itemAmount = nz(item.getAmount());
        PurchaseSalePriceService.Price price = prices.get(item.getId());
        BigDecimal unit = price == null ? null : price.effective();
        if (tons == null || tons.signum() <= 0 || unit == null || unit.signum() <= 0
            || itemAmount.signum() <= 0) {
          continue;
        }
        estimatedSales = estimatedSales
            .add(unit.multiply(tons).setScale(2, RoundingMode.HALF_UP));
      }
    }
    BigDecimal sales = deliverySales.signum() > 0 ? deliverySales : estimatedSales;
    // 收入差：按采购量估算的货款 − 按送货量的实际货款（没有送货单时无处比较，留空）
    BigDecimal revenueGap = deliverySales.signum() > 0 && estimatedSales.signum() > 0
        ? estimatedSales.subtract(deliverySales).setScale(2, RoundingMode.HALF_UP)
        : null;
    // 毛利差额 = 收入差 − 采购金额差额 − 运费差额（三项齐备才有意义）
    BigDecimal marginGap = revenueGap == null || costGap == null || freightGap == null
        ? null
        : revenueGap.subtract(costGap).subtract(freightGap).setScale(2, RoundingMode.HALF_UP);
    if (sales.signum() <= 0) {
      // 有采购单但没有送货销售额也算不出估算（缺定价口径 / 明细缺吨数金额）
      String note = policy == null ? "no_policy" : "no_priced_row";
      return new PurchaseMargin(null, salesFreight, freight, null, null, null, note,
          revenueGap, purchase, planCost, planFreight, costGap, freightGap, marginGap,
          basis.materials(), basis.hasDelivery(), basis.unpricedDeliveryRows());
    }
    BigDecimal cost = purchase.add(freight);
    BigDecimal amount = sales.add(salesFreight).subtract(cost);
    // 毛利率 = 毛利 ÷ 总收入，分母 = 销售额 + 客户承担送货运费（总额法，2026-10-28 定）。
    BigDecimal salesTotal = sales.add(salesFreight);
    BigDecimal rate = salesTotal.signum() > 0
        ? amount.multiply(BigDecimal.valueOf(100)).divide(salesTotal, 2, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;
    return new PurchaseMargin(sales, salesFreight, freight, cost, amount, rate, null,
        revenueGap, purchase, planCost, planFreight, costGap, freightGap, marginGap,
        basis.materials(), basis.hasDelivery(), basis.unpricedDeliveryRows());
  }

  /**
   * 计划运费：元/吨口径行按「采购吨数」还原，其余（元/趟、运输单、单据写明）金额不随量变。
   * 与实际运费的差就是「采购运费差额」。
   */
  private BigDecimal batchPlanFreight(String batchId, BigDecimal planTons) {
    BigDecimal total = BigDecimal.ZERO;
    for (com.aibot.purchase.entity.FreightItem item
        : freightService.list(batchId, FreightService.SIDE_PURCHASE)) {
      BigDecimal amount = nz(item.getAmount());
      if (FreightService.SOURCE_POLICY_TON.equals(item.getSource()) && planTons.signum() > 0) {
        total = total.add(nz(item.getUnitPrice()).multiply(planTons));
      } else {
        total = total.add(amount);
      }
    }
    return total.setScale(2, RoundingMode.HALF_UP);
  }

  /** 批次已确认送货单的货款合计（不含客户承担的运费）：销售口径以送货量为准的取数入口。 */
  private BigDecimal deliveryGoodsTotal(String batchId) {
    BigDecimal total = BigDecimal.ZERO;
    for (Document delivery : documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "delivery_note")
        .in(Document::getStatus, "matched", "archived"))) {
      total = total.add(deliveryContributedSales(delivery, getDocumentItems(delivery.getId())));
    }
    return total.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * 批次客户承担的送货运费：取运费表 sales 侧合计（2026-11-11 改）。
   *
   * <p>口径行与「单据写明」行并列累加；「含运费」口径（显式 0）不生成行，自然为 0。
   * 这笔钱同时进毛利率分子和分母：客户付的运费是收入，我们付的采购运费在成本侧扣。
   */
  private BigDecimal batchSalesFreight(String batchId) {
    return freightService.sum(batchId, FreightService.SIDE_SALES);
  }

  /**
   * 批次采购侧统一重算（2026-11-12 扩为「运费 + 采购金额」）：先重建运费行，再回填台账。
   *
   * <ul>
   *   <li>{@code freight}（采购运费成本）= 运费表 purchase 侧合计；</li>
   *   <li>{@code purchase_amount} = 采购成本基准的实际送达量成本（{@link PurchaseCostBasisService}）——
   *       送货单确认/修正/删除后必须重算，否则「总额按计划、毛利按实际」两套口径打架；</li>
   *   <li>销售运费不再挂单据：{@code sales_amount} 按「新 sales 侧合计 − 旧合计」做差额同步，
   *       所以口径或单据变化只需调本方法，金额自洽且幂等。</li>
   * </ul>
   *
   * <p>所有确认 / 修正 / 删除路径最后都会调到这里，是采购侧金额的单一触发点。
   */
  public void recalcFreight(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return;
    }
    List<LedgerRecord> records = getLedgerInternal(batchId);
    if (records.isEmpty()) {
      return;
    }
    // 重建前先记下销售运费合计，用于把差额同步进销售金额
    BigDecimal beforeSalesFreight = freightService.sum(batchId, FreightService.SIDE_SALES);
    freightService.rebuild(batchId);
    BigDecimal freight = freightService.sum(batchId, FreightService.SIDE_PURCHASE);
    BigDecimal afterSalesFreight = freightService.sum(batchId, FreightService.SIDE_SALES);
    BigDecimal purchase = purchaseActualAmount(batchId);

    LedgerRecord record = records.get(0);
    BigDecimal salesDelta = afterSalesFreight.subtract(beforeSalesFreight);
    boolean purchaseChanged = nz(record.getPurchaseAmount()).compareTo(purchase) != 0;
    if (nz(record.getFreight()).compareTo(freight) == 0
        && salesDelta.signum() == 0
        && !purchaseChanged) {
      return;
    }
    record.setFreight(freight);
    record.setPurchaseAmount(purchase);
    if (salesDelta.signum() != 0) {
      record.setSalesAmount(nonNegative(nz(record.getSalesAmount()).add(salesDelta)));
    }
    recalculate(record);
    record.setUpdatedAt(LocalDateTime.now());
    ledgerRecordMapper.updateById(record);
  }

  /**
   * 批次采购运费成本：直接取运费表 purchase 侧合计（2026-11-11 改）。
   *
   * <p>运费行由 {@link FreightService#rebuild(String)} 按批次重建，四类来源并列累加：
   * 运输单、送货单写明的运费、元/吨口径、元/趟口径。同一份钱不会再因为
   * 「AI 按口径把 transportFee 填进单据 + 后端再按口径算一份」而重复计入。
   */
  private BigDecimal batchFreightCost(String batchId) {
    return freightService.sum(batchId, FreightService.SIDE_PURCHASE);
  }

  /** 明细吨数：优先重量（换算成吨），无重量时按计价单位=吨的计价数量兜底。 */
  private BigDecimal tonnageOf(DocumentItem item) {
    BigDecimal tons = weightInTons(item);
    if (tons.signum() > 0) {
      return tons;
    }
    BigDecimal quantity = item.getPricingQuantity();
    if (quantity == null || quantity.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    String unit = normalizeUnit(item.getPricingUnit());
    return List.of("t", "ton", "吨").contains(unit) ? quantity : BigDecimal.ZERO;
  }

  /** 台账返回装饰：采购毛利额/毛利率挂批次首条记录（与月结利息同模式，瞬时计算不落库）。 */
  private List<LedgerRecord> decoratePurchaseMargin(List<LedgerRecord> records) {
    if (records == null || records.isEmpty()) {
      return records;
    }
    Map<String, List<LedgerRecord>> byBatch = new java.util.LinkedHashMap<>();
    for (LedgerRecord record : records) {
      if (StringUtils.hasText(record.getBatchId())) {
        byBatch.computeIfAbsent(record.getBatchId(), key -> new java.util.ArrayList<>()).add(record);
      }
    }
    for (Map.Entry<String, List<LedgerRecord>> entry : byBatch.entrySet()) {
      PurchaseMargin margin = supplyMargin(entry.getKey());
      LedgerRecord summary = entry.getValue().get(0);
      if (margin == null) {
        continue;
      }
      // 送货运费与采购毛利无关也可能存在（只有送货单、没有采购单），单独给值，
      // 供前端展示材料区毛利额构成（销售额已含运费，毛利率按总额法算）。
      summary.setPurchaseSalesFreight(margin.salesFreight());
      // 采购运费成本与定价口径正交（2026-11-06）：没配定价口径也要显示运费，
      // 否则「配了运费口径/导了运输单，台账却看不到运费」。
      summary.setPurchaseFreightCost(margin.freight());
      if (margin.amount() != null) {
        summary.setPurchaseMarginAmount(margin.amount());
        summary.setPurchaseMarginRate(margin.rate());
      }
      // 毛利算不出的原因（缺定价口径/无采购单/采购明细缺吨数金额），前端据此显示提示而非空白
      summary.setPurchaseMarginNote(margin.note());
      // 采购金额口径以成本基准为准（2026-11-12）：读接口时一并覆写成「实际送达量成本」，
      // 存量批次不必先重跑校准也能与差额列、毛利列自洽（落库值仍由 recalcFreight 维护）。
      if (margin.actualCost() != null
          && nz(summary.getPurchaseAmount()).compareTo(margin.actualCost()) != 0) {
        summary.setPurchaseAmount(margin.actualCost());
        // 未付款/未回款/状态都是采购金额的派生值，覆写后必须一起重算，否则同一行两个口径
        recalculate(summary);
      }
      // 2026-11-12 计划量 / 实际送达量两套口径的差额（三个列 + 详情弹框都读这里）：
      // 收入差不单独成列，只在详情里作为毛利差额的算式第一项。
      summary.setPurchaseMarginGap(margin.marginGap());
      summary.setPurchaseRevenueGap(margin.revenueGap());
      summary.setPurchasePlanAmount(margin.planCost());
      summary.setPurchaseAmountGap(margin.costGap());
      summary.setPurchaseFreightPlan(margin.planFreight());
      summary.setPurchaseFreightGap(margin.freightGap());
      summary.setPurchaseHasDelivery(margin.hasDelivery());
      summary.setPurchaseUnpricedDeliveryRows(margin.unpricedDeliveryRows());
      summary.setMaterialCostBasis(margin.materials());
      // 毛利的计划值（2026-11-12）：实际毛利 + 毛利差额；毛利率分母用「按计划量的估算货款 + 送货运费」，
      // 与「毛利差额 = 收入差 − 金额差 − 运费差」同一套算式，括号里的数因此能和差额列对上。
      if (margin.amount() != null && margin.marginGap() != null && margin.revenueGap() != null) {
        BigDecimal planAmount = margin.amount().add(margin.marginGap())
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal planSalesTotal = margin.sales().add(margin.salesFreight())
            .add(margin.revenueGap());
        summary.setPurchaseMarginPlanAmount(planAmount);
        summary.setPurchaseMarginPlanRate(planSalesTotal.signum() > 0
            ? planAmount.multiply(BigDecimal.valueOf(100))
                .divide(planSalesTotal, 2, RoundingMode.HALF_UP)
            : null);
      }
      // 运费明细行（口径/运输单/单据写明并列），页面与导出的构成明细直接读它
      summary.setFreightItems(freightService.list(entry.getKey(), null));
      summary.setDeliveryCostPendingCount(deliveryCostPendingCount(entry.getKey()));
    }
    return records;
  }

  /** 批次内「待采购价」的送货明细行数：这些行金额为 0，未计入销售金额（对账单会少算）。 */
  private int deliveryCostPendingCount(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return 0;
    }
    List<String> documentIds = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
            .eq(Document::getBatchId, batchId)
            .eq(Document::getDocType, "delivery_note")
            .in(Document::getStatus, "matched", "archived"))
        .stream().map(Document::getId).toList();
    if (documentIds.isEmpty()) {
      return 0;
    }
    Long count = itemNetMappingMapper.selectCount(
        Wrappers.<DocumentItemMarketMapping>lambdaQuery()
            .in(DocumentItemMarketMapping::getDocumentId, documentIds)
            .eq(DocumentItemMarketMapping::getPriceBasis, "cost_pending"));
    return count == null ? 0 : count.intValue();
  }

  /**
   * 月结加价利息装饰（对外返回时调用）：
   * 批次级数据挂在批次首条台账记录上（与 salesAmount/receiptDate 等一致），
   * 前端按批次合并展示；非月结批次不计算利息，字段为空。
   */
  private List<LedgerRecord> decorateMonthlySettlement(List<LedgerRecord> records) {
    if (records == null || records.isEmpty()) {
      return records;
    }
    // 按批次分组：首条记录作为批次代表（同一批次业务口径一致）
    Map<String, List<LedgerRecord>> byBatch = new java.util.LinkedHashMap<>();
    for (LedgerRecord record : records) {
      byBatch.computeIfAbsent(record.getBatchId(), key -> new java.util.ArrayList<>())
          .add(record);
    }
    LocalDate today = LocalDate.now();
    for (List<LedgerRecord> batchRecords : byBatch.values()) {
      LedgerRecord summary = batchRecords.get(0);
      // 非月结：清空瞬时字段
      if (!Boolean.TRUE.equals(summary.getIsMonthlySettlement())) {
        summary.setInterest(null);
        summary.setInterestDetails(null);
        summary.setReceiptEntries(null);
        summary.setOverdueFlag(null);
        summary.setAmountCapFlag(null);
        summary.setInterestSummary(null);
        continue;
      }
      BigDecimal sales = batchRecords.stream().map(LedgerRecord::getSalesAmount)
          .map(LedgerService::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal received = batchRecords.stream().map(LedgerRecord::getReceivedAmount)
          .map(LedgerService::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
      LocalDate requiredDate = summary.getRequiredReceiptDate();
      List<MonthlySettlementCalculator.ReceiptEntry> receipts = new java.util.ArrayList<>();
      if (StringUtils.hasText(summary.getBatchId())) {
        documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                .eq(Document::getBatchId, summary.getBatchId())
                .eq(Document::getDocType, "receipt")
                .in(Document::getStatus, "matched", "archived")
                .orderByAsc(Document::getDocDate))
            .forEach(document -> {
              if (document.getDocDate() != null) {
                receipts.add(new MonthlySettlementCalculator.ReceiptEntry(
                    document.getDocDate(), nz(document.getAmount())));
              }
            });
      }
      MonthlySettlementCalculator.InterestResult result = MonthlySettlementCalculator.calculate(
          requiredDate, sales, received, receipts, today);
      summary.setInterest(result.totalInterest());
      summary.setInterestDetails(result.details());
      summary.setReceiptEntries(receipts.stream()
          .map(entry -> Map.<String, Object>of(
              "date", entry.date().toString(), "amount", entry.amount()))
          .toList());
      summary.setOverdueFlag(result.overdueFlag());
      summary.setAmountCapFlag(result.amountCapFlag());
      // 未回款列补充口径：未回款本金 + 利息（不是销售总额 + 利息）
      BigDecimal outstanding = sales.subtract(received).max(BigDecimal.ZERO);
      summary.setInterestSummary(
          outstanding.toPlainString() + " + " + result.totalInterest().toPlainString() + " = "
              + outstanding.add(result.totalInterest()).toPlainString());
    }
    return records;
  }

  private void requireOwnedBatch(String userId, String projectId, String batchId) {
    Batch batch = batchMapper.selectById(batchId);
    if (batch == null || (StringUtils.hasText(projectId) && !projectId.equals(batch.getProjectId()))) {
      throw new ApiException(HttpStatus.NOT_FOUND, "批次不存在");
    }
    requireOwnedProject(userId, batch.getProjectId());
  }

  private void requireOwnedProject(String userId, String projectId) {
    Long count = projectMapper.selectCount(Wrappers.<Project>lambdaQuery()
        .eq(Project::getId, projectId)
        .eq(Project::getOwnerId, userId));
    if (count == 0) {
      throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在");
    }
  }

  /** 台账行的材料字段按大类显示（识别与原始明细数据不受影响）。 */
  private String extractMaterial(Document document) {
    List<String> categories = getDocumentItems(document.getId()).stream()
        .map(item -> MaterialCategoryNormalizer.category(item.getMaterialName()))
        .filter(category -> StringUtils.hasText(category)
            && !"未标注材料".equals(category))
        .distinct()
        .toList();
    if (!categories.isEmpty()) {
      return String.join(" / ", categories);
    }

    // 兼容尚未迁移出 document_items 的历史识别数据。
    Object items = structuredData(document).get("items");
    if (!(items instanceof List<?> list)) {
      return null;
    }
    return list.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(item -> MaterialCategoryNormalizer.category(
            String.valueOf(item.get("materialName"))))
        .filter(category -> StringUtils.hasText(category)
            && !"未标注材料".equals(category))
        .distinct()
        .collect(Collectors.joining(" / "));
  }

  private String extractText(Document document, String field) {
    Object value = structuredData(document).get(field);
    return value == null ? null : String.valueOf(value);
  }

  private Map<String, Object> structuredData(Document document) {
    if (!StringUtils.hasText(document.getStructuredData())) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(document.getStructuredData(), new TypeReference<>() {
      });
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  private String determineStatus(LedgerRecord record) {
    if (nz(record.getPaidAmount()).compareTo(BigDecimal.ZERO) == 0
        || nz(record.getInvoiceAmount()).compareTo(BigDecimal.ZERO) == 0) {
      return "pending";
    }
    if (nz(record.getUnpaidAmount()).compareTo(BigDecimal.ZERO) > 0) {
      return "pending_payment";
    }
    return "balanced";
  }

  /**
   * 批次业务进度（2026-10-11 口径）：六环节等分，各占 100/6——
   * 采购确认 → 进项发票 → 付款 → 到货确认 → 销项发票 → 收款。
   * 请款（requestedAmount）退出进度口径；到货确认有送达进度即算（部分送达也算）。
   */
  private int stageProgress(List<LedgerRecord> records) {
    int stages = 0;
    if (sum(records, LedgerRecord::getPurchaseAmount).compareTo(BigDecimal.ZERO) > 0) stages++;
    if (sum(records, LedgerRecord::getInvoiceAmount).compareTo(BigDecimal.ZERO) > 0) stages++;
    if (sum(records, LedgerRecord::getPaidAmount).compareTo(BigDecimal.ZERO) > 0) stages++;
    boolean delivered = sum(records, LedgerRecord::getDeliveryAmount).compareTo(BigDecimal.ZERO) > 0
        || sum(records, LedgerRecord::getDeliveredPackageQuantity).compareTo(BigDecimal.ZERO) > 0
        || sum(records, LedgerRecord::getDeliveredWeight).compareTo(BigDecimal.ZERO) > 0;
    if (delivered) stages++;
    if (sum(records, LedgerRecord::getSalesInvoiceAmount).compareTo(BigDecimal.ZERO) > 0) stages++;
    if (sum(records, LedgerRecord::getReceivedAmount).compareTo(BigDecimal.ZERO) > 0) stages++;
    return (int) Math.round(stages * 100.0 / 6);
  }

  private BigDecimal sum(
      List<LedgerRecord> records,
      java.util.function.Function<LedgerRecord, BigDecimal> getter
  ) {
    return records.stream().map(getter).map(LedgerService::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private boolean sameParty(String left, String right) {
    if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) return false;
    return left.replaceAll("\\s+", "").equalsIgnoreCase(right.replaceAll("\\s+", ""));
  }

  private String commonUnit(List<String> units) {
    List<String> normalized = units.stream()
        .filter(StringUtils::hasText)
        .map(this::normalizeUnit)
        .distinct()
        .toList();
    return normalized.size() == 1 ? normalized.get(0) : normalized.isEmpty() ? null : "混合单位";
  }

  private String mergeUnit(String current, String incoming) {
    if (!StringUtils.hasText(current)) return incoming;
    if (!StringUtils.hasText(incoming)) return current;
    return normalizeUnit(current).equals(normalizeUnit(incoming)) ? current : "混合单位";
  }

  private String normalizeUnit(String unit) {
    return unit == null ? "" : unit.trim().toLowerCase();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal nonNegative(BigDecimal value) {
    return value.max(BigDecimal.ZERO);
  }

  private static LocalDate parseDate(Object value) {
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(String.valueOf(value).trim().substring(0, 10));
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "日期格式不正确");
    }
  }

  private static BigDecimal parseDecimal(Object value) {
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(String.valueOf(value).replaceAll("[^\\d.-]", ""));
    } catch (Exception ex) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "金额或比例格式不正确");
    }
  }

  private static String text(Object value) {
    if (value == null) return null;
    String result = String.valueOf(value).trim();
    return result.isEmpty() ? null : result;
  }
}
