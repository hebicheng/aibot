package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.BatchMaterial;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.mapper.BatchMaterialMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.service.MaterialCategoryNormalizer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 送货单明细行 → 采购吨价（毛利率口径的定价基数，2026-10-28）。

 * <p>业务口径：毛利率 = (销售价 − 采购价) ÷ 销售价，销售价按「采购吨价 ÷ (1−毛利率)」倒推，
 * 所以送货单每一行必须先拿到同批次采购单里对应材料的吨价。
 *
 * <p>匹配层级（同批次、只认已确认采购单）：材料大类 + 直径 → 同牌号优先 → 产地优先 →
 * 多行按吨数加权均价。**规格对不上不退化到大类均价**——宁可空着并给出原因，
 * 因为不同直径的采购价差可能很大，猜出来的售价会直接错进对账单。
 *
 * <p>匹配结果带 level 供界面原地提示：{@code no_supply}（批次还没有采购单）与
 * {@code spec_missing}（有采购单但没这个规格）是两种不同的话术，不能混为一谈。
 */
@Service
public class PurchaseCostResolver {

  /** 匹配结果：unitPrice=采购吨价（元/吨，null 表示拿不到），supplyItemId=留痕的采购明细行。 */
  public record Cost(BigDecimal unitPrice, String supplyItemId, String level) {
    public boolean priced() {
      return unitPrice != null && unitPrice.signum() > 0;
    }
  }

  public static final String LEVEL_EXACT = "exact";
  public static final String LEVEL_ORIGIN = "origin";
  public static final String LEVEL_WEIGHTED = "weighted";
  public static final String LEVEL_MATERIAL = "material";
  public static final String LEVEL_SPEC_MISSING = "spec_missing";
  public static final String LEVEL_NO_SUPPLY = "no_supply";

  private static final Cost NO_SUPPLY = new Cost(null, null, LEVEL_NO_SUPPLY);
  private static final Cost SPEC_MISSING = new Cost(null, null, LEVEL_SPEC_MISSING);

  /** 直径范围上限（「Φ6-10」最多展开 40 个桶，防止异常串把索引撑爆）。 */
  private static final int RANGE_LIMIT = 40;

  private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
  private static final Pattern RANGE = Pattern.compile(
      "(\\d+(?:\\.\\d+)?)\\s*[-–~至]\\s*(\\d+(?:\\.\\d+)?)");

  private final DocumentMapper documentMapper;
  private final DocumentItemMapper itemMapper;
  private final BatchMaterialMapper batchMaterialMapper;
  private final PurchaseSalePriceService purchaseSalePriceService;

  public PurchaseCostResolver(
      DocumentMapper documentMapper,
      DocumentItemMapper itemMapper,
      BatchMaterialMapper batchMaterialMapper,
      PurchaseSalePriceService purchaseSalePriceService
  ) {
    this.documentMapper = documentMapper;
    this.itemMapper = itemMapper;
    this.batchMaterialMapper = batchMaterialMapper;
    this.purchaseSalePriceService = purchaseSalePriceService;
  }

  /** 采购明细行摊平后的取价素材：吨数 + 吨价 + 产地 + 牌号。 */
  private record SupplyRow(BigDecimal tons, BigDecimal unitPrice, String origin, String grade,
                           String itemId) {
  }

  /** 批次索引：规格维度 + 大类维度（判「有没有这个大类」）+ 大类下的已知规格集 + 材料捷径。 */
  private record Index(
      Map<String, List<SupplyRow>> bySpec,
      Map<String, List<SupplyRow>> byCategory,
      Map<String, Set<String>> specsOfCategory,
      Map<String, SupplyRow> byMaterial
  ) {
    static Index empty() {
      return new Index(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
    }
  }

  /**
   * 批量解析：一次载入批次内全部已确认采购明细，按行返回采购吨价。
   * 返回 key = document_items.id。
   */
  public Map<String, Cost> resolve(String batchId, List<DocumentItem> items) {
    Map<String, Cost> result = new HashMap<>();
    if (items == null || items.isEmpty()) {
      return result;
    }
    Index index = StringUtils.hasText(batchId) ? loadIndex(batchId) : Index.empty();
    if (index.bySpec().isEmpty()) {
      for (DocumentItem item : items) {
        result.put(item.getId(), NO_SUPPLY);
      }
      return result;
    }
    for (DocumentItem item : items) {
      result.put(item.getId(), resolveOne(item, index));
    }
    return result;
  }

  /** 单行解析：材料捷径 → 牌号/产地优先 → 吨数加权；未命中按「有没有这个大类」区分原因。 */
  private Cost resolveOne(DocumentItem item, Index index) {
    // 材料层捷径（2026-11-04）：归并后的材料直接引用它的采购行（supply_item_id），
    // 不再按「大类+直径」猜——采购/送货两侧本来就是同一种材料，采购价天然一致。
    if (StringUtils.hasText(item.getMaterialId()) && index.byMaterial().containsKey(item.getMaterialId())) {
      SupplyRow row = index.byMaterial().get(item.getMaterialId());
      return new Cost(row.unitPrice(), row.itemId(), LEVEL_MATERIAL);
    }
    if (item == null || !StringUtils.hasText(item.getMaterialName())) {
      return SPEC_MISSING;
    }
    MarketNameNormalizer.Parsed parsed =
        MarketNameNormalizer.parse(item.getMaterialName(), item.getSpec());
    List<String> categories = categoryKeys(parsed, item.getMaterialName());

    // 送货单可能写范围规格（Φ6-10）：所有命中的直径桶合起来参与加权，不能只取第一个桶
    List<SupplyRow> rows = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    boolean categoryKnown = false;
    List<String> keys = specKeys(parsed.spec());
    for (String category : categories) {
      if (index.byCategory().containsKey(category)) {
        categoryKnown = true;
      }
      for (String key : keys) {
        List<SupplyRow> bucket = index.bySpec().get(category + "|" + key);
        if (bucket == null) {
          continue;
        }
        for (SupplyRow row : bucket) {
          if (seen.add(row.itemId())) {
            rows.add(row);
          }
        }
      }
    }
    // 送货单没写规格（不是「写了但没对上」）：大类下只有一种规格时才敢套用，
    // 多种规格就明说「没这个规格」——写了 Φ22 而采购单只有 Φ20 属于对不上，不猜。
    if (rows.isEmpty() && keys.isEmpty()) {
      for (String category : categories) {
        Set<String> known = index.specsOfCategory().get(category);
        if (known == null || known.size() != 1) {
          continue;
        }
        List<SupplyRow> bucket = index.bySpec().get(category + "|" + known.iterator().next());
        if (bucket != null) {
          for (SupplyRow row : bucket) {
            if (seen.add(row.itemId())) {
              rows.add(row);
            }
          }
        }
      }
    }
    if (rows.isEmpty()) {
      return categoryKnown ? SPEC_MISSING : NO_SUPPLY;
    }

    // 牌号一致的行更可信（HRB400E 与 HRB500 同直径价不同），有就只在这些行里取
    List<SupplyRow> sameGrade = filterByGrade(rows, parsed.grade());
    List<SupplyRow> candidates = sameGrade.isEmpty() ? rows : sameGrade;
    // 产地优先：送货单行写了产地，就先在产地一致（互相包含）的采购行里取，与行情链路同习惯
    boolean originHit = false;
    List<SupplyRow> byOrigin = filterByOrigin(candidates, item.getOrigin());
    if (!byOrigin.isEmpty()) {
      candidates = byOrigin;
      originHit = true;
    }

    BigDecimal tonsSum = BigDecimal.ZERO;
    BigDecimal amountSum = BigDecimal.ZERO;
    SupplyRow heaviest = null;
    for (SupplyRow row : candidates) {
      tonsSum = tonsSum.add(row.tons());
      amountSum = amountSum.add(row.unitPrice().multiply(row.tons()));
      if (heaviest == null || row.tons().compareTo(heaviest.tons()) > 0) {
        heaviest = row;
      }
    }
    if (tonsSum.signum() <= 0 || heaviest == null) {
      return SPEC_MISSING;
    }
    BigDecimal unitPrice = amountSum.divide(tonsSum, 2, RoundingMode.HALF_UP);
    String level = originHit ? LEVEL_ORIGIN
        : candidates.size() > 1 ? LEVEL_WEIGHTED : LEVEL_EXACT;
    return new Cost(unitPrice, heaviest.itemId(), level);
  }

  private static List<SupplyRow> filterByGrade(List<SupplyRow> rows, String grade) {
    if (!StringUtils.hasText(grade)) {
      return List.of();
    }
    List<SupplyRow> matched = new ArrayList<>();
    for (SupplyRow row : rows) {
      if (StringUtils.hasText(row.grade()) && row.grade().equalsIgnoreCase(grade)) {
        matched.add(row);
      }
    }
    return matched;
  }

  /** 产地互相包含即命中（「江苏」与「江苏永钢」视为同一产地）。 */
  private static List<SupplyRow> filterByOrigin(List<SupplyRow> rows, String origin) {
    if (!StringUtils.hasText(origin)) {
      return List.of();
    }
    String target = origin.trim();
    List<SupplyRow> matched = new ArrayList<>();
    for (SupplyRow row : rows) {
      String rowOrigin = row.origin() == null ? "" : row.origin().trim();
      if (!rowOrigin.isEmpty()
          && (rowOrigin.contains(target) || target.contains(rowOrigin))) {
        matched.add(row);
      }
    }
    return matched;
  }

  /** 批次内全部已确认采购明细 → 按「大类|直径」建索引 + 大类兜底索引（判原因用）+ 材料捷径。 */
  private Index loadIndex(String batchId) {
    List<Document> supplies = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "supply_order")
        .in(Document::getStatus, "matched", "archived"));
    // 明细行先收集再算吨价：同一采购行可能命中多个直径桶（范围规格），对象只建一次
    Map<String, List<SupplyRow>> bySpec = new LinkedHashMap<>();
    Map<String, List<SupplyRow>> byCategory = new LinkedHashMap<>();
    Map<String, Set<String>> specsOfCategory = new LinkedHashMap<>();
    Map<String, SupplyRow> byItem = new LinkedHashMap<>();
    if (supplies.isEmpty()) {
      return new Index(bySpec, byCategory, specsOfCategory, Map.of());
    }
    List<String> documentIds = supplies.stream().map(Document::getId).toList();
    List<DocumentItem> supplyItems = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .in(DocumentItem::getDocumentId, documentIds)
        .orderByAsc(DocumentItem::getSortOrder));
    for (DocumentItem item : supplyItems) {
      BigDecimal tons = purchaseSalePriceService.pricingTons(item);
      BigDecimal unitPrice = purchaseSalePriceService.costPrice(item);
      if (tons == null || tons.signum() <= 0 || unitPrice == null || unitPrice.signum() <= 0) {
        // 算不出吨数的采购行（件计价且无重量）无法折算吨价，不参与匹配
        continue;
      }
      MarketNameNormalizer.Parsed parsed =
          MarketNameNormalizer.parse(item.getMaterialName(), item.getSpec());
      SupplyRow row = new SupplyRow(tons, unitPrice, item.getOrigin(), parsed.grade(), item.getId());
      byItem.put(item.getId(), row);
      List<String> keys = specKeys(parsed.spec());
      for (String category : categoryKeys(parsed, item.getMaterialName())) {
        byCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(row);
        if (keys.isEmpty()) {
          // 采购行本身没有直径（如只写「螺纹钢」）：只能参与大类判定，不参与规格定价
          continue;
        }
        for (String key : keys) {
          bySpec.computeIfAbsent(category + "|" + key, k -> new ArrayList<>()).add(row);
          specsOfCategory.computeIfAbsent(category, k -> new LinkedHashSet<>()).add(key);
        }
      }
    }
    // 材料捷径：batch_materials.supply_item_id → 该材料的权威采购行（材料层刷新后总是最新）
    Map<String, SupplyRow> byMaterial = new HashMap<>();
    for (BatchMaterial material : batchMaterialMapper.selectList(
        Wrappers.<BatchMaterial>lambdaQuery().eq(BatchMaterial::getBatchId, batchId))) {
      if (!StringUtils.hasText(material.getSupplyItemId())) {
        continue;
      }
      SupplyRow row = byItem.get(material.getSupplyItemId());
      if (row != null) {
        byMaterial.put(material.getId(), row);
      }
    }
    return new Index(bySpec, byCategory, specsOfCategory, byMaterial);
  }

  /**
   * 材料大类标识（可多个）：同时按行情产品码与材料名归一化建索引。
   *
   * <p>只用产品码会在「一侧能解析出产品码、另一侧解析不出」时假性匹配不上（如采购单写
   * 「盘元」、送货单写「盘螺」两边都命中 143，但某个侧面写「热轧带肋钢筋」就没有产品码），
   * 双索引让两侧用哪个维度都能对上；同名重复由调用方按行 id 去重。
   */
  static List<String> categoryKeys(MarketNameNormalizer.Parsed parsed, String materialName) {
    Set<String> keys = new LinkedHashSet<>();
    if (parsed != null && StringUtils.hasText(parsed.productCode())) {
      keys.add("P" + parsed.productCode());
    }
    String category = MaterialCategoryNormalizer.category(materialName);
    if (StringUtils.hasText(category)) {
      keys.add(category);
    }
    if (keys.isEmpty()) {
      keys.add("未标注材料");
    }
    return new ArrayList<>(keys);
  }

  /**
   * 规格 → 直径桶（可多个）：单值取第一个数字（「Φ20」「20*9」都落到 20）；
   * 范围规格「Φ6-10」「Φ28-32」展开成 6,7,8,9,10（盘螺一张单就写一档价，不展开会整行匹配不上）；
   * 逗号列举「Φ12,14」按枚举处理。无数字返回空列表。
   */
  static List<String> specKeys(String spec) {
    if (!StringUtils.hasText(spec)) {
      return List.of();
    }
    String text = spec.replace('，', ',').replace('－', '-').replace('—', '-').trim();
    Matcher range = RANGE.matcher(text);
    if (range.find()) {
      BigDecimal low = new BigDecimal(range.group(1));
      BigDecimal high = new BigDecimal(range.group(2));
      if (high.compareTo(low) > 0
          && high.subtract(low).compareTo(BigDecimal.valueOf(RANGE_LIMIT)) <= 0) {
        Set<String> keys = new LinkedHashSet<>();
        for (BigDecimal value = low.setScale(0, RoundingMode.CEILING);
             value.compareTo(high) <= 0; value = value.add(BigDecimal.ONE)) {
          keys.add(plain(value));
        }
        if (keys.isEmpty()) {
          keys.add(plain(low));
          keys.add(plain(high));
        }
        return new ArrayList<>(keys);
      }
    }
    if (text.contains(",")) {
      Set<String> keys = new LinkedHashSet<>();
      for (String part : text.split(",")) {
        Matcher matcher = NUMBER.matcher(part);
        if (matcher.find()) {
          keys.add(matcher.group(1));
        }
      }
      return new ArrayList<>(keys);
    }
    Matcher matcher = NUMBER.matcher(text);
    return matcher.find() ? List.of(matcher.group(1)) : List.of();
  }

  private static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
