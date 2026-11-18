package com.aibot.purchase.service;

import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.service.market.PurchaseSalePriceService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 采购成本基准：批次「采购单计划量」与「送货单实际送达量」两套金额的唯一取数入口
 * （2026-11-12 建立）。
 *
 * <p>背景：采购金额原先一直按采购单明细金额（= 计划量）落库与展示，而采购毛利早在
 * 2026-11-11 就改成「采购吨价 × 送达吨数」。于是同一批次在台账上出现两套口径
 * （总额按计划、毛利按实际），「销售 − 总额」与「采购毛利金额」本应相等却对不上。
 *
 * <p>本服务把两套口径一次算清，供毛利、台账金额列、差额列、导出共用：
 *
 * <ul>
 *   <li>{@code planAmount} 采购单金额合计（保留「单据金额优先」决策，见下）；</li>
 *   <li>{@code actualAmount} 采购吨价 × 送货单实际送达吨数（按材料大类聚合）；</li>
 *   <li>{@code amountGap = planAmount − actualAmount}，正数表示少送、这部分货不计本批成本。</li>
 * </ul>
 *
 * <p>计划金额尊重采购单上的「保留差异」决策（{@code structuredData.amountMismatchDecision =
 * keep_source}）：此时以单据总额为准，按明细金额（无金额则按吨数）比例摊到材料行，
 * 与改造前台账采购金额完全一致；缺明细的采购单归入「未拆分采购数据」大类。
 *
 * <p>三种降级都不会把成本算成 0（宁可按计划值保留）：
 *
 * <ul>
 *   <li>一张送货单都没有 → 实际 = 计划（行为与改造前完全一致）；</li>
 *   <li>送货明细全部算不出吨数（按件计价且无重量）→ 整批回退计划值；</li>
 *   <li>采购行缺吨数 → 该行金额按原值计入实际成本，不参与吨价摊分（unpriced=true）。</li>
 * </ul>
 */
@Service
public class PurchaseCostBasisService {

  /** 送货行配对不上采购行：按本批次整批加权均价估算。 */
  public static final String NOTE_BATCH_AVERAGE = "batch_average";
  /** 采购行缺吨数：金额按原值计入，未按吨摊分。 */
  public static final String NOTE_UNPRICED = "unpriced";
  /** 采购单没有明细时的材料归类（与前端占位行同一名称）。 */
  public static final String CATEGORY_UNSPLIT = "未拆分采购数据";

  private final DocumentMapper documentMapper;
  private final DocumentItemMapper documentItemMapper;
  private final PurchaseSalePriceService salePriceService;
  private final ObjectMapper objectMapper;

  public PurchaseCostBasisService(
      DocumentMapper documentMapper,
      DocumentItemMapper documentItemMapper,
      PurchaseSalePriceService salePriceService,
      ObjectMapper objectMapper
  ) {
    this.documentMapper = documentMapper;
    this.documentItemMapper = documentItemMapper;
    this.salePriceService = salePriceService;
    this.objectMapper = objectMapper;
  }

  /**
   * 单个材料大类的计划 / 实际。
   *
   * @param category       材料大类（与台账材料行同一口径）
   * @param planTons       采购单吨数（只含有吨数、参与摊分的行）
   * @param planAmount     采购单金额（含缺吨数行按原值计入的部分）
   * @param deliveredTons  送货单吨数
   * @param actualAmount   实际成本（采购吨价 × 送达吨数 + 缺吨数行原值）
   * @param unitPrice      采购吨价（参与摊分金额 ÷ 计划吨数；无吨数时为 null）
   * @param fallbackPriced 含按整批均价估算的送货行
   * @param unpriced       含缺吨数、按原值计入的采购行
   */
  public record MaterialBasis(
      String category,
      BigDecimal planTons,
      BigDecimal planAmount,
      BigDecimal deliveredTons,
      BigDecimal actualAmount,
      BigDecimal unitPrice,
      boolean fallbackPriced,
      boolean unpriced
  ) {
    public BigDecimal amountGap() {
      return planAmount.subtract(actualAmount).setScale(2, RoundingMode.HALF_UP);
    }
  }

  /**
   * 批次采购成本基准。
   *
   * @param hasDelivery          批次内是否有已确认送货单（false 时实际值等于计划值）
   * @param planTons             采购吨数合计
   * @param planAmount           采购金额合计
   * @param deliveredTons        送货吨数合计
   * @param actualAmount         实际成本合计
   * @param unpricedDeliveryRows 送货明细里算不出吨数的行数（没有计入实际成本）
   * @param fallbackToPlan       是否因送货吨数全缺而整批回退计划值
   * @param materials            逐材料大类明细
   */
  public record Basis(
      boolean hasDelivery,
      BigDecimal planTons,
      BigDecimal planAmount,
      BigDecimal deliveredTons,
      BigDecimal actualAmount,
      int unpricedDeliveryRows,
      boolean fallbackToPlan,
      List<MaterialBasis> materials
  ) {
    public BigDecimal amountGap() {
      return planAmount.subtract(actualAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /** 计划与实际是否有值得展示的差异：吨数差超 0.0001 或金额差 ≥ 一分钱。 */
    public boolean amountDiffers() {
      return planTons.subtract(deliveredTons).abs().compareTo(new BigDecimal("0.0001")) > 0
          || amountGap().abs().compareTo(new BigDecimal("0.01")) >= 0;
    }

    public boolean isEmpty() {
      return materials.isEmpty();
    }
  }

  private static final Basis EMPTY = new Basis(
      false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, false, List.of());

  /** 批次采购成本基准（只读，随时可算）。 */
  public Basis basis(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return EMPTY;
    }
    List<Document> supplies = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "supply_order")
        .in(Document::getStatus, "matched", "archived")
        .orderByAsc(Document::getCreatedAt));
    if (supplies.isEmpty()) {
      return EMPTY;
    }

    // 采购行级：配对键 → [吨数, 参与摊分的金额]
    Map<String, BigDecimal[]> planByKey = new LinkedHashMap<>();
    Map<String, String> categoryByKey = new LinkedHashMap<>();
    // 大类级：category → [计划吨数, 参与摊分金额, 缺吨数行金额]
    Map<String, BigDecimal[]> planByCategory = new LinkedHashMap<>();
    // 大类级标记：[0]=有送货行按整批均价估算，[1]=有缺吨数采购行
    Map<String, boolean[]> flagsByCategory = new LinkedHashMap<>();

    BigDecimal planTonsTotal = BigDecimal.ZERO;
    BigDecimal pricedAmountTotal = BigDecimal.ZERO;
    BigDecimal planAmountTotal = BigDecimal.ZERO;

    for (Document supply : supplies) {
      List<DocumentItem> items = itemsOf(supply.getId());
      if (items.isEmpty()) {
        // 只有表头金额、没有明细的采购单：整单归入「未拆分采购数据」，不参与吨价摊分
        BigDecimal amount = nz(supply.getAmount());
        if (amount.signum() <= 0) {
          continue;
        }
        BigDecimal[] agg = planByCategory.computeIfAbsent(
            CATEGORY_UNSPLIT, name -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        flagsByCategory.computeIfAbsent(CATEGORY_UNSPLIT, name -> new boolean[]{false, false})[1] = true;
        agg[2] = agg[2].add(amount);
        planAmountTotal = planAmountTotal.add(amount);
        continue;
      }
      // 计划金额池：保留差异决策时以单据总额为准，其余按明细金额合计
      BigDecimal itemSum = items.stream()
          .map(DocumentItem::getAmount).map(PurchaseCostBasisService::nz)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal docAmount = nz(supply.getAmount());
      boolean keepSource = "keep_source".equals(structuredText(supply, "amountMismatchDecision"));
      BigDecimal pool = keepSource && docAmount.signum() > 0 ? docAmount
          : itemSum.signum() > 0 ? itemSum : docAmount;
      boolean apportion = pool.signum() > 0 && pool.compareTo(itemSum) != 0;

      List<BigDecimal> lineAmounts = apportion
          ? apportion(pool, items, itemSum)
          : items.stream().map(item -> nz(item.getAmount())).toList();

      for (int index = 0; index < items.size(); index++) {
        DocumentItem item = items.get(index);
        BigDecimal amount = lineAmounts.get(index);
        String category = MaterialCategoryNormalizer.category(item.getMaterialName());
        String key = keyOf(item);
        BigDecimal tons = salePriceService.pricingTons(item);
        BigDecimal[] agg = planByCategory.computeIfAbsent(
            category, name -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        flagsByCategory.computeIfAbsent(category, name -> new boolean[]{false, false});
        planAmountTotal = planAmountTotal.add(amount);
        if (tons == null || tons.signum() <= 0) {
          // 缺吨数的采购行：金额按原值保留，不参与吨价摊分（否则吨价被抬高、金额被算两遍）
          agg[2] = agg[2].add(amount);
          flagsByCategory.get(category)[1] = true;
          continue;
        }
        BigDecimal[] planAgg = planByKey.computeIfAbsent(
            key, value -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        planAgg[0] = planAgg[0].add(tons);
        planAgg[1] = planAgg[1].add(amount);
        categoryByKey.putIfAbsent(key, category);
        agg[0] = agg[0].add(tons);
        agg[1] = agg[1].add(amount);
        planTonsTotal = planTonsTotal.add(tons);
        pricedAmountTotal = pricedAmountTotal.add(amount);
      }
    }

    List<Document> deliveries = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "delivery_note")
        .in(Document::getStatus, "matched", "archived")
        .orderByAsc(Document::getCreatedAt));
    boolean hasDelivery = !deliveries.isEmpty();

    // 大类级：category → [送达吨数, 摊分成本]
    Map<String, BigDecimal[]> deliveredByCategory = new LinkedHashMap<>();
    int unpricedDeliveryRows = 0;
    BigDecimal deliveredTonsTotal = BigDecimal.ZERO;
    BigDecimal deliveredCostTotal = BigDecimal.ZERO;

    if (hasDelivery) {
      BigDecimal batchUnitPrice = planTonsTotal.signum() > 0
          ? pricedAmountTotal.divide(planTonsTotal, 4, RoundingMode.HALF_UP)
          : null;
      for (Document delivery : deliveries) {
        for (DocumentItem item : itemsOf(delivery.getId())) {
          BigDecimal tons = salePriceService.pricingTons(item);
          if (tons == null || tons.signum() <= 0) {
            unpricedDeliveryRows++;
            continue;
          }
          String key = keyOf(item);
          String category = categoryByKey.getOrDefault(
              key, MaterialCategoryNormalizer.category(item.getMaterialName()));
          BigDecimal[] planAgg = planByKey.get(key);
          boolean fallback = planAgg == null || planAgg[0].signum() <= 0;
          BigDecimal unitPrice = fallback
              ? batchUnitPrice
              : planAgg[1].divide(planAgg[0], 4, RoundingMode.HALF_UP);
          BigDecimal[] delivered = deliveredByCategory.computeIfAbsent(
              category, name -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
          delivered[0] = delivered[0].add(tons);
          deliveredTonsTotal = deliveredTonsTotal.add(tons);
          if (fallback) {
            flagsByCategory.computeIfAbsent(category, name -> new boolean[]{false, false})[0] = true;
          }
          if (unitPrice == null || unitPrice.signum() <= 0) {
            // 采购单里完全没有这个材料：无价可摊，只计吨数不记成本（差额列会暴露）
            continue;
          }
          BigDecimal cost = unitPrice.multiply(tons);
          delivered[1] = delivered[1].add(cost);
          deliveredCostTotal = deliveredCostTotal.add(cost);
        }
      }
    }

    BigDecimal unpricedPlanAmount = unpricedAmountTotal(planByCategory);

    // 送货吨数全缺（按件计价且无重量）或压根没有送货单：整批回退计划值
    if (!hasDelivery || deliveredCostTotal.signum() <= 0) {
      List<MaterialBasis> materials = new ArrayList<>();
      for (Map.Entry<String, BigDecimal[]> entry : planByCategory.entrySet()) {
        BigDecimal[] agg = entry.getValue();
        BigDecimal planAmount = agg[1].add(agg[2]);
        materials.add(new MaterialBasis(
            entry.getKey(),
            agg[0].setScale(3, RoundingMode.HALF_UP),
            planAmount.setScale(2, RoundingMode.HALF_UP),
            agg[0].setScale(3, RoundingMode.HALF_UP),
            planAmount.setScale(2, RoundingMode.HALF_UP),
            unitPriceOf(agg),
            false,
            flagsByCategory.getOrDefault(entry.getKey(), new boolean[]{false, false})[1]));
      }
      return new Basis(hasDelivery, planTonsTotal, planAmountTotal,
          hasDelivery ? deliveredTonsTotal : planTonsTotal,
          planAmountTotal, unpricedDeliveryRows, hasDelivery, materials);
    }

    List<MaterialBasis> materials = new ArrayList<>();
    for (Map.Entry<String, BigDecimal[]> entry : planByCategory.entrySet()) {
      String category = entry.getKey();
      BigDecimal[] agg = entry.getValue();
      BigDecimal[] delivered = deliveredByCategory.get(category);
      BigDecimal deliveredTons = delivered == null ? BigDecimal.ZERO : delivered[0];
      BigDecimal actual = (delivered == null ? BigDecimal.ZERO : delivered[1]).add(agg[2]);
      materials.add(new MaterialBasis(
          category,
          agg[0].setScale(3, RoundingMode.HALF_UP),
          agg[1].add(agg[2]).setScale(2, RoundingMode.HALF_UP),
          deliveredTons.setScale(3, RoundingMode.HALF_UP),
          actual.setScale(2, RoundingMode.HALF_UP),
          unitPriceOf(agg),
          flagsByCategory.getOrDefault(category, new boolean[]{false, false})[0],
          flagsByCategory.getOrDefault(category, new boolean[]{false, false})[1]));
    }
    // 只有送货、没有采购单的大类：计划 0、实际按整批均价（超供 / 未建采购单）
    for (Map.Entry<String, BigDecimal[]> entry : deliveredByCategory.entrySet()) {
      if (planByCategory.containsKey(entry.getKey())) {
        continue;
      }
      materials.add(new MaterialBasis(
          entry.getKey(),
          BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP),
          BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
          entry.getValue()[0].setScale(3, RoundingMode.HALF_UP),
          entry.getValue()[1].setScale(2, RoundingMode.HALF_UP),
          null,
          true,
          false));
    }

    return new Basis(true, planTonsTotal, planAmountTotal, deliveredTonsTotal,
        deliveredCostTotal.add(unpricedPlanAmount).setScale(2, RoundingMode.HALF_UP),
        unpricedDeliveryRows, false, materials);
  }

  /** 单据金额池按明细摊分：优先金额占比，明细全无金额时按吨数占比，末行吸收舍入残差。 */
  private List<BigDecimal> apportion(
      BigDecimal pool,
      List<DocumentItem> items,
      BigDecimal itemSum
  ) {
    List<BigDecimal> weights = items.stream()
        .map(item -> itemSum.signum() > 0
            ? nz(item.getAmount())
            : nz(salePriceService.pricingTons(item)))
        .toList();
    BigDecimal weightSum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    List<BigDecimal> result = new ArrayList<>();
    BigDecimal allocated = BigDecimal.ZERO;
    for (int index = 0; index < items.size(); index++) {
      BigDecimal amount;
      if (index == items.size() - 1) {
        amount = pool.subtract(allocated);
      } else if (weightSum.signum() > 0) {
        amount = pool.multiply(weights.get(index))
            .divide(weightSum, 2, RoundingMode.HALF_UP);
        allocated = allocated.add(amount);
      } else {
        // 既无金额又无吨数：均分
        amount = pool.divide(BigDecimal.valueOf(items.size()), 2, RoundingMode.HALF_UP);
        allocated = allocated.add(amount);
      }
      result.add(amount);
    }
    return result;
  }

  private static BigDecimal unitPriceOf(BigDecimal[] agg) {
    return agg[0].signum() > 0 ? agg[1].divide(agg[0], 2, RoundingMode.HALF_UP) : null;
  }

  private static BigDecimal unpricedAmountTotal(Map<String, BigDecimal[]> planByCategory) {
    return planByCategory.values().stream()
        .map(agg -> agg[2])
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private List<DocumentItem> itemsOf(String documentId) {
    if (!StringUtils.hasText(documentId)) {
      return List.of();
    }
    return documentItemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, documentId)
        .orderByAsc(DocumentItem::getSortOrder));
  }

  private String structuredText(Document document, String field) {
    if (!StringUtils.hasText(document.getStructuredData())) {
      return null;
    }
    try {
      Map<String, Object> data = objectMapper.readValue(
          document.getStructuredData(), new TypeReference<Map<String, Object>>() {
          });
      Object value = data.get(field);
      return value == null ? null : String.valueOf(value);
    } catch (Exception ignored) {
      return null;
    }
  }

  /** 采购/送货明细配对键：材料行 id 优先（同一材料两侧共用），没有才退到名称+规格。 */
  private static String keyOf(DocumentItem item) {
    if (StringUtils.hasText(item.getMaterialId())) {
      return "M:" + item.getMaterialId();
    }
    return "S:" + (item.getMaterialName() == null ? "" : item.getMaterialName())
        + "|" + (item.getSpec() == null ? "" : item.getSpec());
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
