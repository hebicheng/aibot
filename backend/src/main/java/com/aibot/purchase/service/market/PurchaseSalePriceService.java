package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.Batch;
import com.aibot.purchase.entity.BatchMaterial;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.DocumentItemMarketMapping;
import com.aibot.purchase.mapper.BatchMapper;
import com.aibot.purchase.mapper.BatchMaterialMapper;
import com.aibot.purchase.mapper.DocumentItemMarketMappingMapper;
import com.aibot.purchase.service.BatchHintService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 采购单销售价（元/计价单位）：批次默认口径 × 采购成本价推算。
 *
 * <p>与送货单的区别（2026-10-27 定）：送货单销售价从网价倒推（{@code PricingPolicy#salesUnitPrice}），
 * 采购单的毛利率口径改成从<b>采购价</b>倒推——「毛利率 2.5 个点」的业务含义是采购成本之上加价，
 * 用网价当基数会让采购价与网价的差白白吃掉毛利。上浮口径仍走网价 + 上浮（没有网价就没有销售价）。
 *
 * <p>取价优先级：材料级人工价 {@code batch_materials.manual_sales_price}（2026-11-06 接入，与送货单锁价同源）
 * ＞ 明细行人工值 {@code document_items.sales_unit_price}（历史口径）＞ 按当前批次口径自动推算。
 * 人工值独立于批次口径——没有口径也能有销价；自动值读时现算，因此改批次口径对未人工改过的行即时生效。
 */
@Service
public class PurchaseSalePriceService {

  private final BatchMapper batchMapper;
  private final BatchHintService batchHintService;
  private final DocumentItemMarketMappingMapper mappingMapper;
  private final BatchMaterialMapper batchMaterialMapper;

  public PurchaseSalePriceService(
      BatchMapper batchMapper,
      BatchHintService batchHintService,
      DocumentItemMarketMappingMapper mappingMapper,
      BatchMaterialMapper batchMaterialMapper
  ) {
    this.batchMapper = batchMapper;
    this.batchHintService = batchHintService;
    this.mappingMapper = mappingMapper;
    this.batchMaterialMapper = batchMaterialMapper;
  }

  /** 单行销售价：auto=按口径推算（可能为 null），manual=人工指定值，effective=最终取值。 */
  public record Price(BigDecimal auto, BigDecimal manual, BigDecimal effective) {
    public boolean isManual() {
      return manual != null;
    }
  }

  private static final Price EMPTY = new Price(null, null, null);

  /** 批次默认销售口径；无口径返回 null（采购单不产生销售价）。 */
  public DeliveryNetPriceService.PricingPolicy policyFor(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return null;
    }
    Batch batch = batchMapper.selectById(batchId);
    return DeliveryNetPriceService.PricingPolicy.fromBatchHints(
        batchHintService.deliveryNoteHints(batch));
  }

  public DeliveryNetPriceService.PricingPolicy policyFor(Document document) {
    return document == null ? null : policyFor(document.getBatchId());
  }

  /** 批量取价：一次载入单据明细的行情锁定行（上浮口径需要网价）+ 材料级人工销售价。 */
  public Map<String, Price> pricesFor(List<DocumentItem> items, DeliveryNetPriceService.PricingPolicy policy) {
    Map<String, Price> result = new HashMap<>();
    if (items == null || items.isEmpty()) {
      return result;
    }
    Map<String, BatchMaterial> materials = materialOverrides(items);
    if (policy == null) {
      // 没有批次口径不产生自动销价，但材料级人工价是「人给定的」，不受口径约束
      for (DocumentItem item : items) {
        BigDecimal manual = manualOf(item, materials);
        result.put(item.getId(), manual == null ? EMPTY : new Price(null, manual, manual));
      }
      return result;
    }
    List<String> itemIds = items.stream().map(DocumentItem::getId).toList();
    Map<String, DocumentItemMarketMapping> mappingByItem = new HashMap<>();
    // 毛利率口径按采购价倒推，不需要网价；只有上浮口径才要读锁定行情价
    if ("uplift".equals(policy.mode())) {
      for (DocumentItemMarketMapping mapping : mappingMapper.selectList(
          Wrappers.<DocumentItemMarketMapping>lambdaQuery()
              .in(DocumentItemMarketMapping::getDocumentItemId, itemIds))) {
        mappingByItem.putIfAbsent(mapping.getDocumentItemId(), mapping);
      }
    }
    for (DocumentItem item : items) {
      BatchMaterial material = materialOf(item, materials);
      // 人工网价是取价最高一层（2026-11-12 补齐消费方）：没有锁定行情行时用它推销售价。
      // 否则「设了人工网价」的批次 autoPrice 拿不到 netPrice → 上浮口径算不出价 → 估算货款为 0，
      // 表现为台账「采购毛利差额 / 计划毛利」整列空白。
      BigDecimal manualNetPrice = material == null ? null : material.getManualQuotePrice();
      result.put(item.getId(), priceOf(item, policy, mappingByItem.get(item.getId()),
          manualOf(item, material), manualNetPrice));
    }
    return result;
  }

  /** 材料行覆盖值：一次批量载入（人工销售价 + 人工网价），避免逐行查库。 */
  private Map<String, BatchMaterial> materialOverrides(List<DocumentItem> items) {
    Set<String> materialIds = new LinkedHashSet<>();
    for (DocumentItem item : items) {
      if (item != null && StringUtils.hasText(item.getMaterialId())) {
        materialIds.add(item.getMaterialId());
      }
    }
    Map<String, BatchMaterial> materials = new HashMap<>();
    if (materialIds.isEmpty()) {
      return materials;
    }
    for (BatchMaterial material : batchMaterialMapper.selectBatchIds(materialIds)) {
      materials.put(material.getId(), material);
    }
    return materials;
  }

  private static BatchMaterial materialOf(DocumentItem item, Map<String, BatchMaterial> materials) {
    return item != null && StringUtils.hasText(item.getMaterialId())
        ? materials.get(item.getMaterialId()) : null;
  }

  /** 行的人工价：材料级（新口径）＞ 行级 sales_unit_price（历史口径）。 */
  private static BigDecimal manualOf(DocumentItem item, Map<String, BatchMaterial> materials) {
    return manualOf(item, materialOf(item, materials));
  }

  private static BigDecimal manualOf(DocumentItem item, BatchMaterial material) {
    if (item == null) {
      return null;
    }
    BigDecimal materialManual = material == null ? null : material.getManualSalesPrice();
    if (materialManual != null && materialManual.signum() > 0) {
      return materialManual;
    }
    return item.getSalesUnitPrice();
  }

  /** 单行取价：人工值优先，其次按口径推算。 */
  public Price priceOf(DocumentItem item, DeliveryNetPriceService.PricingPolicy policy,
                       DocumentItemMarketMapping mapping) {
    // 便捷入口无材料行上下文，拿不到人工网价，传 null 回退到锁定行情价（与批量入口一致）
    return priceOf(item, policy, mapping, item == null ? null : item.getSalesUnitPrice(), null);
  }

  private Price priceOf(DocumentItem item, DeliveryNetPriceService.PricingPolicy policy,
                        DocumentItemMarketMapping mapping, BigDecimal manual,
                        BigDecimal manualNetPrice) {
    BigDecimal auto = policy == null ? null : autoPrice(item, policy, mapping, manualNetPrice);
    return new Price(auto, manual, manual != null ? manual : auto);
  }

  /**
   * 自动销价：网价优先取锁定行情行，没有锁定行时用材料行的人工网价（2026-11-12 补）。
   * 人工网价与行情价同为「网价」，上浮口径直接在其上加价；毛利率口径不用网价，不受影响。
   */
  private BigDecimal autoPrice(DocumentItem item, DeliveryNetPriceService.PricingPolicy policy,
                               DocumentItemMarketMapping mapping, BigDecimal manualNetPrice) {
    BigDecimal netPrice = mapping == null ? null : mapping.getQuotePrice();
    if (netPrice == null || netPrice.signum() <= 0) {
      netPrice = manualNetPrice;
    }
    return policy.salesUnitPriceFromCost(costPrice(item), netPrice);
  }

  /**
   * 采购成本单价（元/吨）：优先 采购金额 ÷ 计价吨数（覆盖件数计价、重量与数量不一致的单据），
   * 退化到计价单位为吨时的单据单价；都取不到返回 null（该行不参与毛利统计）。
   */
  public BigDecimal costPrice(DocumentItem item) {
    if (item == null) {
      return null;
    }
    BigDecimal tons = pricingTons(item);
    BigDecimal amount = item.getAmount();
    if (tons != null && tons.signum() > 0 && amount != null && amount.signum() > 0) {
      return amount.divide(tons, 2, RoundingMode.HALF_UP);
    }
    if (tons != null && isTon(item.getPricingUnit()) && item.getUnitPrice() != null
        && item.getUnitPrice().signum() > 0) {
      return item.getUnitPrice();
    }
    return null;
  }

  /** 计价吨数：优先计价数量（计价单位为吨），其次重量换算成吨；都不是吨返回 null。 */
  public BigDecimal pricingTons(DocumentItem item) {
    if (item == null) {
      return null;
    }
    if (item.getPricingQuantity() != null && item.getPricingQuantity().signum() > 0
        && isTon(item.getPricingUnit())) {
      return item.getPricingQuantity();
    }
    BigDecimal weight = item.getTotalWeight();
    if (weight != null && weight.signum() > 0 && isTonOrDefault(item.getWeightUnit())) {
      return weight;
    }
    return null;
  }

  private static boolean isTon(String unit) {
    if (!StringUtils.hasText(unit)) {
      return false;
    }
    String value = unit.trim().toLowerCase();
    return "吨".equals(value) || "t".equals(value) || "ton".equals(value);
  }

  /** 重量单位缺省按吨处理（历史数据大量只有数字没有单位）。 */
  private static boolean isTonOrDefault(String unit) {
    return !StringUtils.hasText(unit) || isTon(unit);
  }
}
