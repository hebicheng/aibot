package com.aibot.purchase.service;

import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.FreightItem;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.FreightItemMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 批次运费：一行一笔，四类来源并列存在、计算时累加（2026-11-11）。
 *
 * <ul>
 *   <li>{@code policy_ton} 元/吨口径：单价 × 实际重量（送货单重量优先，没有送货单才借采购重量）；</li>
 *   <li>{@code policy_trip} 元/趟口径：整批一趟包干价，不乘单据张数；</li>
 *   <li>{@code transport_note} 单独导入的运输单金额；</li>
 *   <li>{@code document_stated} 单据上明确写的运费（送货单 transportFee / salesFreight）。</li>
 * </ul>
 *
 * <p>口径行不再写进任何单据字段，也不再挂「第一张送货单」——它是系统按批次口径生成的独立一笔，
 * 与运输单、单据写明的运费同时存在，采购成本/销售额都要把它们全部计入。
 *
 * <p>{@link #rebuild(String)} 按批次幂等重建（先删后插），单据增删改、口径变更后统一调用即可，
 * 调用方不需要关心每一行怎么维护。存量批次不回填。
 */
@Service
public class FreightService {
  private static final Logger log = LoggerFactory.getLogger(FreightService.class);

  public static final String SIDE_PURCHASE = "purchase";
  public static final String SIDE_SALES = "sales";

  public static final String SOURCE_POLICY_TON = "policy_ton";
  public static final String SOURCE_POLICY_TRIP = "policy_trip";
  public static final String SOURCE_TRANSPORT_NOTE = "transport_note";
  public static final String SOURCE_DOCUMENT_STATED = "document_stated";

  private final FreightItemMapper freightItemMapper;
  private final DocumentMapper documentMapper;
  private final DocumentItemMapper documentItemMapper;
  private final BatchMapper batchMapper;
  private final BatchHintService batchHintService;
  private final ObjectMapper objectMapper;

  public FreightService(
      FreightItemMapper freightItemMapper,
      DocumentMapper documentMapper,
      DocumentItemMapper documentItemMapper,
      BatchMapper batchMapper,
      BatchHintService batchHintService,
      ObjectMapper objectMapper
  ) {
    this.freightItemMapper = freightItemMapper;
    this.documentMapper = documentMapper;
    this.documentItemMapper = documentItemMapper;
    this.batchMapper = batchMapper;
    this.batchHintService = batchHintService;
    this.objectMapper = objectMapper;
  }

  /** 按批次重建全部运费行（幂等：先删后插）。 */
  @Transactional
  public void rebuild(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return;
    }
    Batch batch = batchMapper.selectById(batchId);
    if (batch == null) {
      return;
    }
    List<Document> documents = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .in(Document::getStatus, "matched", "archived"));
    List<Document> transportNotes = byType(documents, "transport_note");
    List<Document> deliveries = byType(documents, "delivery_note");
    List<Document> supplies = byType(documents, "supply_order");

    // 实际重量：送货单是全局实际重量，只有一张送货单都没有时才借采购单的重量
    BigDecimal actualTons = tonsOf(deliveries);
    if (actualTons.signum() <= 0) {
      actualTons = tonsOf(supplies);
    }

    // 旧行按「身份键」建索引：重建后 id 会变，人工改过的承运单位要靠它带过去
    Map<String, FreightItem> previous = new LinkedHashMap<>();
    for (FreightItem old : list(batchId, null)) {
      previous.put(identity(old.getSide(), old.getSource(), old.getSourceDocumentId()), old);
    }

    freightItemMapper.delete(Wrappers.<FreightItem>lambdaQuery()
        .eq(FreightItem::getBatchId, batchId));

    List<FreightItem> rows = new ArrayList<>();
    // 采购侧：运输单 + 送货单写明的运费（我方付给承运方）
    for (Document note : transportNotes) {
      BigDecimal amount = nz(note.getAmount());
      if (amount.signum() > 0) {
        rows.add(row(batch, SIDE_PURCHASE, SOURCE_TRANSPORT_NOTE, note.getId(),
            null, null, amount, "运输单" + codeSuffix(note),
            note.getSupplierName(), previous));
      }
    }
    for (Document delivery : deliveries) {
      BigDecimal stated = statedPurchaseFreight(delivery);
      if (stated.signum() > 0) {
        rows.add(row(batch, SIDE_PURCHASE, SOURCE_DOCUMENT_STATED, delivery.getId(),
            null, null, stated, "送货单写明运费" + codeSuffix(delivery),
            structuredText(delivery, "carrierName"), previous));
      }
    }
    // 销售侧：客户承担的运费（单据上人工填写的实际值）
    for (Document delivery : deliveries) {
      BigDecimal stated = nz(delivery.getSalesFreight());
      if (stated.signum() > 0) {
        rows.add(row(batch, SIDE_SALES, SOURCE_DOCUMENT_STATED, delivery.getId(),
            null, null, stated, "送货单写明运费" + codeSuffix(delivery),
            structuredText(delivery, "carrierName"), previous));
      }
    }
    Map<String, Object> hints = batchHintService.deliveryNoteHints(batch);
    addPolicyRow(rows, batch, hints, previous,
        SIDE_PURCHASE,
        BatchHintService.KEY_SUPPLY_FREIGHT_PER_TRIP,
        BatchHintService.KEY_SUPPLY_FREIGHT_PER_TON,
        actualTons);
    addPolicyRow(rows, batch, hints, previous,
        SIDE_SALES,
        BatchHintService.KEY_SALES_FREIGHT_PER_TRIP,
        BatchHintService.KEY_SALES_FREIGHT_PER_TON,
        actualTons);

    for (FreightItem item : rows) {
      freightItemMapper.insert(item);
    }
    log.debug("批次 {} 运费行重建完成：{} 条（实际吨数 {}）", batchId, rows.size(), actualTons);
  }

  /**
   * 人工改运费行的承运单位（按行改）。
   *
   * @param carrierByItemId 运费行 id → 承运单位（空串表示清空，回到「待补充」）
   * @return 实际更新的行数
   */
  @Transactional
  public int updateCarriers(String batchId, Map<String, String> carrierByItemId) {
    if (!StringUtils.hasText(batchId) || carrierByItemId == null || carrierByItemId.isEmpty()) {
      return 0;
    }
    int updated = 0;
    for (FreightItem item : list(batchId, null)) {
      if (!carrierByItemId.containsKey(item.getId())) {
        continue;
      }
      String raw = carrierByItemId.get(item.getId());
      String carrier = StringUtils.hasText(raw) ? raw.trim() : null;
      freightItemMapper.update(null, Wrappers.<FreightItem>lambdaUpdate()
          .eq(FreightItem::getId, item.getId())
          .eq(FreightItem::getBatchId, batchId)
          .set(FreightItem::getCarrierName, carrier)
          .set(FreightItem::getCarrierManual, true)
          .set(FreightItem::getUpdatedAt, LocalDateTime.now()));
      updated++;
    }
    return updated;
  }

  public List<FreightItem> list(String batchId, String side) {
    if (!StringUtils.hasText(batchId)) {
      return List.of();
    }
    return freightItemMapper.selectList(Wrappers.<FreightItem>lambdaQuery()
        .eq(FreightItem::getBatchId, batchId)
        .eq(StringUtils.hasText(side), FreightItem::getSide, side)
        .orderByAsc(FreightItem::getSource)
        .orderByAsc(FreightItem::getCreatedAt));
  }

  /** 某一侧运费合计（采购侧=成本，销售侧=客户承担的收入）。 */
  public BigDecimal sum(String batchId, String side) {
    return list(batchId, side).stream()
        .map(item -> nz(item.getAmount()))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(2, RoundingMode.HALF_UP);
  }

  /** 批次实际吨数：送货明细重量合计，无送货单时回退采购明细。 */
  public BigDecimal actualTons(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return BigDecimal.ZERO;
    }
    List<Document> documents = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .in(Document::getStatus, "matched", "archived"));
    BigDecimal tons = tonsOf(byType(documents, "delivery_note"));
    return tons.signum() > 0 ? tons : tonsOf(byType(documents, "supply_order"));
  }

  private void addPolicyRow(
      List<FreightItem> rows,
      Batch batch,
      Map<String, Object> hints,
      Map<String, FreightItem> previous,
      String side,
      String perTripKey,
      String perTonKey,
      BigDecimal actualTons
  ) {
    if (hints == null || hints.isEmpty()) {
      return;
    }
    BigDecimal perTrip = BatchHintService.decimal(hints.get(perTripKey));
    if (perTrip != null && perTrip.signum() > 0) {
      // 元/趟 = 整批一趟包干价：这个批次这一趟多少钱就是多少，不乘单据张数
      rows.add(row(batch, side, SOURCE_POLICY_TRIP, null, null, perTrip, perTrip,
          "按口径 " + strip(perTrip) + " 元/趟", null, previous));
      return;
    }
    BigDecimal perTon = BatchHintService.decimal(hints.get(perTonKey));
    if (perTon != null && perTon.signum() > 0 && actualTons.signum() > 0) {
      BigDecimal amount = perTon.multiply(actualTons).setScale(2, RoundingMode.HALF_UP);
      rows.add(row(batch, side, SOURCE_POLICY_TON, null, actualTons, perTon, amount,
          "按口径 " + strip(perTon) + " 元/吨 × " + strip(actualTons) + " 吨", null, previous));
    }
  }

  private FreightItem row(
      Batch batch,
      String side,
      String source,
      String sourceDocumentId,
      BigDecimal tons,
      BigDecimal unitPrice,
      BigDecimal amount,
      String note,
      String autoCarrier,
      Map<String, FreightItem> previous
  ) {
    FreightItem item = new FreightItem();
    item.setProjectId(batch.getProjectId());
    item.setBatchId(batch.getId());
    item.setSide(side);
    item.setSource(source);
    item.setSourceDocumentId(sourceDocumentId);
    item.setTons(tons);
    item.setUnitPrice(unitPrice);
    item.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
    item.setNote(note);
    item.setCarrierName(StringUtils.hasText(autoCarrier) ? autoCarrier.trim() : null);
    item.setCarrierManual(false);
    // 人工改过的承运单位优先：重建后 id 会变，只能按身份键把旧值带过来
    FreightItem old = previous.get(identity(side, source, sourceDocumentId));
    if (old != null && Boolean.TRUE.equals(old.getCarrierManual())) {
      item.setCarrierName(old.getCarrierName());
      item.setCarrierManual(true);
    }
    item.setCreatedAt(LocalDateTime.now());
    item.setUpdatedAt(LocalDateTime.now());
    return item;
  }

  /** 运费行身份：重建前后同一笔运费的稳定标识（口径行不带单据，按 side+source 区分）。 */
  private static String identity(String side, String source, String sourceDocumentId) {
    return side + "|" + source + "|" + (StringUtils.hasText(sourceDocumentId)
        ? sourceDocumentId : "policy");
  }

  private static List<Document> byType(List<Document> documents, String docType) {
    return documents.stream()
        .filter(document -> docType.equals(document.getDocType()))
        .toList();
  }

  private BigDecimal tonsOf(List<Document> documents) {
    if (documents == null || documents.isEmpty()) {
      return BigDecimal.ZERO;
    }
    List<String> ids = documents.stream().map(Document::getId).toList();
    return documentItemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
            .in(DocumentItem::getDocumentId, ids))
        .stream()
        .map(this::tonnageOf)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** 明细吨数：优先重量（换算成吨），无重量时按计价单位=吨的计价数量兜底。 */
  private BigDecimal tonnageOf(DocumentItem item) {
    BigDecimal weight = nz(item.getTotalWeight());
    if (weight.signum() > 0) {
      String unit = normalizeUnit(item.getWeightUnit());
      if (List.of("kg", "千克", "公斤").contains(unit)) {
        return weight.divide(new BigDecimal("1000"));
      }
      if (List.of("t", "ton", "吨").contains(unit)) {
        return weight;
      }
    }
    BigDecimal quantity = item.getPricingQuantity();
    if (quantity == null || quantity.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    String unit = normalizeUnit(item.getPricingUnit());
    return List.of("t", "ton", "吨").contains(unit) ? quantity : BigDecimal.ZERO;
  }

  /** 送货单上写明的采购运费（structuredData.transportFee/freight）；amount 是销售额。 */
  private BigDecimal statedPurchaseFreight(Document document) {
    Map<String, Object> structured = structuredData(document);
    Object fee = structured.get("transportFee");
    if (fee == null) {
      fee = structured.get("freight");
    }
    if (fee == null) {
      return BigDecimal.ZERO;
    }
    if (fee instanceof Number number) {
      return BigDecimal.valueOf(number.doubleValue()).max(BigDecimal.ZERO);
    }
    try {
      return new BigDecimal(String.valueOf(fee).replaceAll("[^\\d.-]", "")).max(BigDecimal.ZERO);
    } catch (Exception ex) {
      return BigDecimal.ZERO;
    }
  }

  private String structuredText(Document document, String field) {
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
      return new LinkedHashMap<>();
    }
  }

  private static String codeSuffix(Document document) {
    return StringUtils.hasText(document.getDocCode()) ? " " + document.getDocCode() : "";
  }

  private static String strip(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private static String normalizeUnit(String unit) {
    return unit == null ? "" : unit.trim().toLowerCase();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
