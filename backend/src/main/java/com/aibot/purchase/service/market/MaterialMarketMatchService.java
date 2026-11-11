package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.Material;
import com.aibot.purchase.entity.MaterialMarketMapping;
import com.aibot.purchase.entity.MarketProduct;
import com.aibot.purchase.entity.MarketQuote;
import com.aibot.purchase.mapper.MaterialMapper;
import com.aibot.purchase.mapper.MaterialMarketMappingMapper;
import com.aibot.purchase.mapper.MarketProductMapper;
import com.aibot.purchase.mapper.MarketQuoteMapper;
import com.aibot.purchase.service.BillingService;
import com.aibot.purchase.service.ai.QwenClient;
import com.aibot.purchase.service.ai.QwenClient.QwenResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 材料 ↔ 行情匹配服务（分层匹配，业界最佳实践）。
 *
 * <p>L1 精确/规则 → L2 模糊（编辑距离/包含）→ L3 AI 兜底（千问 JSON）。
 * 命中即停；结果持久化到 material_market_mappings，页面查询零 AI 调用。
 * 触发时机：采购单确认回填台账后（DocumentService）异步调用。
 */
@Service
public class MaterialMarketMatchService {
  private static final Logger log = LoggerFactory.getLogger(MaterialMarketMatchService.class);

  private final MaterialMapper materialMapper;
  private final MaterialMarketMappingMapper mappingMapper;
  private final MarketProductMapper productMapper;
  private final MarketQuoteMapper quoteMapper;
  private final QwenClient qwenClient;
  private final ObjectMapper objectMapper;
  private final BillingService billingService;

  public MaterialMarketMatchService(
      MaterialMapper materialMapper,
      MaterialMarketMappingMapper mappingMapper,
      MarketProductMapper productMapper,
      MarketQuoteMapper quoteMapper,
      QwenClient qwenClient,
      ObjectMapper objectMapper,
      BillingService billingService
  ) {
    this.materialMapper = materialMapper;
    this.mappingMapper = mappingMapper;
    this.productMapper = productMapper;
    this.quoteMapper = quoteMapper;
    this.qwenClient = qwenClient;
    this.objectMapper = objectMapper;
    this.billingService = billingService;
  }

  /**
   * 批次内全部材料匹配（采购单确认后调用，可异步）。
   */
  public int matchBatch(String batchId) {
    List<Material> materials = materialMapper.selectList(Wrappers.<Material>lambdaQuery()
        .eq(Material::getBatchId, batchId));
    int matched = 0;
    for (Material material : materials) {
      if (matchSingle(material) != null) {
        matched++;
      }
    }
    return matched;
  }

  /**
   * 单条材料匹配：分层执行，命中即落库。
   *
   * @return 落库的 mapping（未匹配则 status=unmatched）
   */
  public MaterialMarketMapping matchSingle(String materialId) {
    Material material = materialMapper.selectById(materialId);
    if (material == null) {
      return null;
    }
    return matchSingle(material);
  }

  private MaterialMarketMapping matchSingle(Material material) {
    // 已有人工修正则跳过
    MaterialMarketMapping existing = mappingMapper.selectOne(Wrappers.<MaterialMarketMapping>lambdaQuery()
        .eq(MaterialMarketMapping::getMaterialId, material.getId())
        .last("LIMIT 1"));
    if (existing != null && "manual".equals(existing.getStatus())) {
      return existing;
    }

    MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse(material.getName(), material.getSpec());
    String productCode = parsed.productCode();
    String spec = parsed.spec();
    String grade = parsed.grade();
    LocalDate date = latestQuoteDate();

    // L1 精确：产品 + 规格 + 材质完全一致
    if (productCode != null && spec != null) {
      MarketQuote exact = findExact(date, productCode, spec, grade);
      if (exact != null) {
        return persist(material, exact, "exact", new BigDecimal("100"), "auto", null);
      }
    }

    // L2 模糊：同产品下规格包含关系（如 Φ25 匹配 Φ25×9）
    if (productCode != null && spec != null) {
      MarketQuote fuzzy = findFuzzy(date, productCode, spec, grade);
      if (fuzzy != null) {
        return persist(material, fuzzy, "fuzzy", new BigDecimal("90"), "auto", null);
      }
    }

    // L2.5 范围包含：业务单规格落入市场范围报价（如 Φ25 命中 Φ16-25，gtgqw 常见）
    if (productCode != null && spec != null) {
      MarketQuote range = findRange(date, productCode, spec, grade);
      if (range != null) {
        return persist(material, range, "range", new BigDecimal("85"), "auto", null);
      }
    }

    // L3 AI 兜底
    MaterialMarketMapping aiResult = matchByAi(material, productCode, spec, grade);
    if (aiResult != null) {
      return aiResult;
    }

    // 未匹配
    return persist(material, null, "unmatched", null, "unmatched", null);
  }

  // ---------------------------------------------------------------------------
  // L1/L2 规则匹配
  // ---------------------------------------------------------------------------

  /**
   * 分层匹配（内存行版本）：供送货单网价等调用方一次性载入行情后复用，
   * 链路 exact 单规格 → exact 任意 → fuzzy 定尺兼容 → range 范围包含。
   */
  public MarketQuote matchFromRows(List<MarketQuote> rows, String spec, String grade) {
    if (spec == null) {
      return null;
    }
    MarketQuote single = firstWithGrade(rows,
        row -> !isRangeSpec(row.getSpec()) && specEqual(spec, row.getSpec()), grade);
    if (single != null) {
      return single;
    }
    MarketQuote any = firstWithGrade(rows, row -> specEqual(spec, row.getSpec()), grade);
    if (any != null) {
      return any;
    }
    MarketQuote fuzzy = firstWithGrade(rows, row -> fuzzySpecMatch(spec, row.getSpec()), grade);
    if (fuzzy != null) {
      return fuzzy;
    }
    BigDecimal value = singleSpecValue(spec);
    if (value != null) {
      return firstWithGrade(rows,
          row -> isRangeSpec(row.getSpec()) && inRange(value, row.getSpec()), grade);
    }
    return null;
  }

  private MarketQuote findExact(LocalDate date, String productCode, String spec, String grade) {
    List<MarketQuote> rows = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, date)
        .eq(MarketQuote::getProductCode, productCode)
        .last("LIMIT 500"));
    // 1) 优先单规格精确（排除组合规格 Φ16-25 之类）
    MarketQuote single = firstWithGrade(rows,
        row -> !isRangeSpec(row.getSpec()) && specEqual(spec, row.getSpec()), grade);
    if (single != null) {
      return single;
    }
    // 2) 组合规格兜底（仅当无单规格精确命中）
    return firstWithGrade(rows, row -> specEqual(spec, row.getSpec()), grade);
  }

  private MarketQuote findFuzzy(LocalDate date, String productCode, String spec, String grade) {
    List<MarketQuote> rows = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, date)
        .eq(MarketQuote::getProductCode, productCode)
        .like(MarketQuote::getSpecName, baseSpec(spec))
        .last("LIMIT 200"));
    // 模糊仅接受定尺兼容（Φ25 vs Φ25×9），不接受跨范围规格
    return firstWithGrade(rows, row -> fuzzySpecMatch(spec, row.getSpec()), grade);
  }

  /**
   * 范围包含匹配：业务为单规格（Φ25）且市场存在覆盖它的范围报价（Φ16-25）。
   * 仅当无精确/定尺命中时使用，置信度降级。
   */
  private MarketQuote findRange(LocalDate date, String productCode, String spec, String grade) {
    BigDecimal value = singleSpecValue(spec);
    if (value == null) {
      return null;
    }
    List<MarketQuote> rows = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, date)
        .eq(MarketQuote::getProductCode, productCode)
        .last("LIMIT 500"));
    return firstWithGrade(rows,
        row -> isRangeSpec(row.getSpec()) && inRange(value, row.getSpec()), grade);
  }

  /**
   * 在候选行中按材质优先级选第一条：先严格材质相等（HRB400E→HRB400E），
   * 再宽松材质近似（HRB400E ↔ HRB400）。避免有精确材质时却匹配到近似材质。
   */
  static MarketQuote firstWithGrade(List<MarketQuote> rows,
      java.util.function.Predicate<MarketQuote> specPredicate, String grade) {
    MarketQuote strict = rows.stream()
        .filter(specPredicate)
        .filter(row -> gradeStrict(grade, row.getGrade()))
        .findFirst().orElse(null);
    if (strict != null) {
      return strict;
    }
    return rows.stream()
        .filter(specPredicate)
        .filter(row -> gradeOk(grade, row.getGrade()))
        .findFirst().orElse(null);
  }

  /** 严格材质相等（忽略大小写/空白）：HRB400E == HRB400E。 */
  static boolean gradeStrict(String businessGrade, String marketGrade) {
    if (businessGrade == null || marketGrade == null) {
      return true;
    }
    return businessGrade.toUpperCase().replaceAll("\\s+", "")
        .equals(marketGrade.toUpperCase().replaceAll("\\s+", ""));
  }

  /**
   * 单数值规格：Φ25 / 25 / 12*10（取主规格 12）→ 25 / 12；范围/定尺/非数字返回 null。
   * 先经 baseSpec 去掉定尺/乘号后缀（25x9、12*10 → 25、12），与 baseSpec/specEqual 行为对齐，
   * 否则带 * 后缀的业务规格（如 12*10）会被原样解析为 BigDecimal 失败而退回 AI 兜底。
   */
  static BigDecimal singleSpecValue(String spec) {
    if (spec == null) {
      return null;
    }
    // 先取主规格（去掉 ×/* 后缀），再判断是否为单个数值
    String base = baseSpec(normalizeSpec(spec));
    if (base.isEmpty() || base.contains("-") || base.contains(",") || base.contains(".")) {
      return null;
    }
    try {
      return new BigDecimal(base);
    } catch (Exception ex) {
      return null;
    }
  }

  /** 市场范围规格（如 Φ16-25 / Φ28-32）是否覆盖给定单数值。 */
  static boolean inRange(BigDecimal value, String marketSpec) {
    String s = normalizeSpec(marketSpec);
    String[] parts = s.split("[-–,]");
    if (parts.length < 2) {
      return false;
    }
    try {
      BigDecimal lo = new BigDecimal(parts[0].trim());
      BigDecimal hi = new BigDecimal(parts[1].trim());
      return value.compareTo(lo) >= 0 && value.compareTo(hi) <= 0;
    } catch (Exception ex) {
      return false;
    }
  }

  /** 规格是否组合范围（Φ16-25 / Φ12,14 / Φ10-12 等）。 */
  static boolean isRangeSpec(String spec) {
    return spec != null && (spec.contains("-") || spec.contains(","));
  }

  /** 规格完全相等（统一符号后比较，支持定尺兼容：Φ25 ≡ Φ25×9）。 */
  private static boolean specEqual(String businessSpec, String marketSpec) {
    if (businessSpec == null || marketSpec == null) {
      return false;
    }
    String b = normalizeSpec(businessSpec);
    String m = normalizeSpec(marketSpec);
    if (b.equals(m)) {
      return true;
    }
    // 定尺兼容：一个去定尺后等于另一个
    String bBase = baseSpec(b);
    String mBase = baseSpec(m);
    return bBase.equals(m) || mBase.equals(b);
  }

  /** 模糊：仅定尺兼容（Φ25 vs Φ25×9），基础规格一致才认可。 */
  private static boolean fuzzySpecMatch(String businessSpec, String marketSpec) {
    if (businessSpec == null || marketSpec == null) {
      return false;
    }
    String b = normalizeSpec(businessSpec);
    String m = normalizeSpec(marketSpec);
    if (b.equals(m)) {
      return true;
    }
    String bBase = baseSpec(b);
    String mBase = baseSpec(m);
    return bBase.equals(m) || mBase.equals(b);
  }

  /** 统一规格符号：去 Φ/Ф、空白，小写。 */
  private static String normalizeSpec(String spec) {
    if (spec == null) {
      return "";
    }
    return spec.replace('Φ', ' ').replace('Ф', ' ').replace('φ', ' ')
        .replaceAll("\\s+", "").toLowerCase();
  }

  /** 取第一个 x 之前的主规格（25x9 → 25；108x4 → 108）。 */
  private static String baseSpec(String spec) {
    if (spec == null) {
      return "";
    }
    String s = spec.replaceAll("[×xX*]", "x");
    int idx = s.indexOf('x');
    return (idx > 0 ? s.substring(0, idx) : s).trim();
  }

  private static boolean gradeOk(String businessGrade, String marketGrade) {
    if (businessGrade == null || marketGrade == null) {
      return true;
    }
    return marketGrade.contains(businessGrade) || businessGrade.contains(marketGrade);
  }

  // ---------------------------------------------------------------------------
  // L3 AI 匹配
  // ---------------------------------------------------------------------------

  private MaterialMarketMapping matchByAi(Material material, String productCode, String spec, String grade) {
    if (!qwenClient.isConfigured()) {
      log.warn("千问未配置，跳过 AI 匹配：{}", material.getName());
      return null;
    }
    // 候选集：同产品下当日全部规格（收窄到 ≤60 条）
    LocalDate date = latestQuoteDate();
    String product = productCode;
    List<MarketQuote> candidates;
    if (product != null) {
      candidates = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
          .eq(MarketQuote::getQuoteDate, date)
          .eq(MarketQuote::getProductCode, product)
          // 需带上 origin：AI 命中后 mapping 要记录产地，否则对比页无产地信息
          .select(MarketQuote::getSpecName, MarketQuote::getSpec, MarketQuote::getGrade,
              MarketQuote::getOrigin, MarketQuote::getSource)
          .last("LIMIT 60"));
    } else {
      candidates = List.of();
    }
    if (candidates.isEmpty()) {
      return null;
    }
    MarketQuote target = aiMatchQuote(material.getName(), material.getSpec(), grade,
        material.getBatchId(), candidates);
    if (target == null) {
      return null;
    }
    return persist(material, target, "ai", new BigDecimal("80"), "auto", null);
  }

  /**
   * AI 匹配（通用版本）：调用方传入业务材料描述与候选行情行，返回命中行情或 null。
   * 不做持久化；计费按传入 batchId 记录。
   */
  public MarketQuote aiMatchQuote(
      String materialName, String spec, String grade, String batchId, List<MarketQuote> candidates) {
    if (!qwenClient.isConfigured() || candidates.isEmpty()) {
      return null;
    }
    List<String> specNames = candidates.stream()
        .map(MarketQuote::getSpecName)
        .distinct()
        .toList();

    String systemPrompt = """
        你是钢材行业材料名称匹配助手。业务侧材料名称与行情侧规格列表可能表述不同（顺序、符号、简写、同义词）。
        从候选列表中选择与业务材料最匹配的一条行情规格。规则：
        1. 产品类型必须一致（螺纹钢对螺纹钢等）；
        2. 规格尽量一致（Φ25 与 Φ25×9 视为兼容，优先选无定尺的）；
        3. 材质尽量一致（HRB400E 与 HRB400E 完全匹配；HRB400E 与 HRB400 可视为近似）；
        4. 无法确定时 matched=false。
        只返回 JSON，不要多余文字。
        """;
    String userPrompt = """
        业务材料名称：%s
        业务规格：%s
        业务材质：%s
        候选行情规格列表（JSON 数组）：
        %s
        请返回：{"matched": true/false, "specName": "完全匹配的候选规格原文（无匹配则为null）", "confidence": 0-100, "reason": "简短理由"}
        """.formatted(materialName, spec, grade, toJsonList(specNames));

    try {
      QwenResult result = qwenClient.completeJson(systemPrompt, userPrompt);
      String raw = result.content();
      // 记积分（余额不足时内部降级跳过，不影响匹配主流程）
      billingService.chargeMarketMatch(batchId, result.usage(), null);
      JsonNode node = objectMapper.readTree(raw);
      boolean matched = node.path("matched").asBoolean(false);
      if (!matched || node.path("specName").isMissingNode() || node.path("specName").asText().isBlank()) {
        return null;
      }
      String specName = node.path("specName").asText().trim();
      // 校验返回的 specName 确实在候选里
      return candidates.stream()
          .filter(c -> c.getSpecName() != null && c.getSpecName().equals(specName))
          .findFirst().orElse(null);
    } catch (Exception ex) {
      log.warn("AI 匹配失败 {}：{}", materialName, ex.getMessage());
      return null;
    }
  }

  private String toJsonList(List<String> items) {
    try {
      return objectMapper.writeValueAsString(items);
    } catch (Exception ex) {
      return items.toString();
    }
  }

  // ---------------------------------------------------------------------------
  // 持久化
  // ---------------------------------------------------------------------------

  private MaterialMarketMapping persist(
      Material material,
      MarketQuote quote,
      String method,
      BigDecimal confidence,
      String status,
      String aiRaw
  ) {
    MaterialMarketMapping mapping = mappingMapper.selectOne(Wrappers.<MaterialMarketMapping>lambdaQuery()
        .eq(MaterialMarketMapping::getMaterialId, material.getId())
        .last("LIMIT 1"));
    if (mapping == null) {
      mapping = new MaterialMarketMapping();
      mapping.setMaterialId(material.getId());
      mapping.setBatchId(material.getBatchId());
      mapping.setCreatedAt(LocalDateTime.now());
    }
    if (quote != null) {
      mapping.setProductCode(quote.getProductCode());
      mapping.setSpecName(quote.getSpecName());
      mapping.setSource(quote.getSource());
      // 产地是筛选维度，不写入绑定关系（取价时由对比筛选器决定）
      mapping.setOrigin(null);
    } else {
      // 未匹配：清空行情关联，避免历史脏数据残留
      mapping.setProductCode(null);
      mapping.setSpecName(null);
      mapping.setSource(null);
      mapping.setOrigin(null);
    }
    mapping.setMatchMethod(method);
    mapping.setConfidence(confidence);
    mapping.setStatus(status);
    mapping.setMatchedAt(LocalDateTime.now());
    mapping.setAiRaw(aiRaw);
    mapping.setUpdatedAt(LocalDateTime.now());
    if (StringUtils.hasText(mapping.getId())) {
      mappingMapper.updateById(mapping);
    } else {
      mappingMapper.insert(mapping);
    }
    return mapping;
  }

  /**
   * 人工修正映射（覆盖写，status=manual）。
   */
  public MaterialMarketMapping manualMatch(String materialId, String productCode, String specName,
                                           String origin, String city, String status) {
    Material material = materialMapper.selectById(materialId);
    if (material == null) {
      throw new IllegalArgumentException("材料不存在：" + materialId);
    }
    MaterialMarketMapping mapping = mappingMapper.selectOne(Wrappers.<MaterialMarketMapping>lambdaQuery()
        .eq(MaterialMarketMapping::getMaterialId, materialId)
        .last("LIMIT 1"));
    if (mapping == null) {
      mapping = new MaterialMarketMapping();
      mapping.setMaterialId(materialId);
      mapping.setBatchId(material.getBatchId());
      mapping.setCreatedAt(LocalDateTime.now());
    }
    boolean isUnmatched = "unmatched".equals(status);
    mapping.setProductCode(isUnmatched ? null : productCode);
    mapping.setSpecName(isUnmatched ? null : specName);
    mapping.setSource(isUnmatched ? null : "gtgqw");
    // 产地是筛选维度，不写入绑定关系
    mapping.setOrigin(null);
    mapping.setCity(StringUtils.hasText(city) ? city : null);
    mapping.setMatchMethod(isUnmatched ? "unmatched" : "manual");
    mapping.setConfidence(isUnmatched ? null : new BigDecimal("100"));
    mapping.setStatus(isUnmatched ? "unmatched" : "manual");
    mapping.setMatchedAt(LocalDateTime.now());
    mapping.setUpdatedAt(LocalDateTime.now());
    if (StringUtils.hasText(mapping.getId())) {
      mappingMapper.updateById(mapping);
    } else {
      mappingMapper.insert(mapping);
    }
    return mapping;
  }

  private LocalDate latestQuoteDate() {
    List<MarketQuote> rows = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .orderByDesc(MarketQuote::getQuoteDate)
        .last("LIMIT 1"));
    return rows.isEmpty() ? LocalDate.now() : rows.get(0).getQuoteDate();
  }

  /** 候选规格清单（人工绑定下拉用）。 */
  public List<Map<String, Object>> candidateSpecs(String productCode) {
    if (!StringUtils.hasText(productCode)) {
      return List.of();
    }
    LocalDate date = latestQuoteDate();
    // 同一规格可能同时有 mysteel（权威/人工导入）和 gtgqw（爬取）两份，绑定只钉产品+规格，
    // 取价日由单据日期自动解析——候选里能帮用户区分的只有来源渠道，不是日期（2026-11-11）
    List<MarketQuote> quotes = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, date)
        .eq(MarketQuote::getProductCode, productCode)
        .select(MarketQuote::getSpecName, MarketQuote::getSource, MarketQuote::getPrice)
        .last("LIMIT 400"));
    Map<String, Map<String, Object>> bySpec = new LinkedHashMap<>();
    for (MarketQuote quote : quotes) {
      if (!StringUtils.hasText(quote.getSpecName())) {
        continue;
      }
      Map<String, Object> row = bySpec.computeIfAbsent(quote.getSpecName(), spec -> {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("spec", spec);
        item.put("sources", new LinkedHashSet<String>());
        item.put("price", null);
        return item;
      });
      if (StringUtils.hasText(quote.getSource())) {
        @SuppressWarnings("unchecked")
        Set<String> sources = (Set<String>) row.get("sources");
        sources.add(quote.getSource());
      }
      if (row.get("price") == null && quote.getPrice() != null) {
        row.put("price", quote.getPrice());
      }
    }
    return new ArrayList<>(bySpec.values());
  }

  /**
   * 批次匹配状态列表。
   *
   * <p>规格候选不在此返回：人工绑定时前端按「用户选中的行情产品」实时调
   * {@code /api/market/products/{code}/specs}，避免按材料预推导产品码产生的空候选/改产品不刷新。
   */
  public List<com.aibot.purchase.dto.MarketDtos.MappingRow> mappings(String batchId) {
    List<Material> materials = materialMapper.selectList(Wrappers.<Material>lambdaQuery()
        .eq(Material::getBatchId, batchId)
        .orderByAsc(Material::getCreatedAt));
    if (materials.isEmpty()) {
      return new ArrayList<>();
    }
    // 产品字典一次性载入（原按材料逐个 selectOne 造成 N+1）
    Map<String, String> productNames = new HashMap<>();
    for (MarketProduct product : productMapper.selectList(null)) {
      productNames.put(product.getCode(), product.getName());
    }
    // 已有绑定一次性载入；每张材料保留首条（与原先 LIMIT 1 的语义一致）
    Map<String, MaterialMarketMapping> mappingByMaterial = new HashMap<>();
    List<String> materialIds = materials.stream().map(Material::getId).toList();
    for (MaterialMarketMapping mapping : mappingMapper.selectList(
        Wrappers.<MaterialMarketMapping>lambdaQuery()
            .in(MaterialMarketMapping::getMaterialId, materialIds))) {
      mappingByMaterial.putIfAbsent(mapping.getMaterialId(), mapping);
    }
    List<com.aibot.purchase.dto.MarketDtos.MappingRow> result = new ArrayList<>();
    for (Material material : materials) {
      MaterialMarketMapping mapping = mappingByMaterial.get(material.getId());
      MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse(material.getName(), material.getSpec());
      String productCode = mapping != null && mapping.getProductCode() != null
          ? mapping.getProductCode() : parsed.productCode();
      result.add(new com.aibot.purchase.dto.MarketDtos.MappingRow(
          mapping == null ? null : mapping.getId(),
          material.getId(),
          material.getName(),
          material.getSpec(),
          productCode,
          productCode == null ? null : productNames.get(productCode),
          mapping == null ? null : mapping.getSpecName(),
          mapping == null ? null : mapping.getOrigin(),
          mapping == null ? null : mapping.getCity(),
          mapping == null ? null : mapping.getMatchMethod(),
          mapping == null ? null : mapping.getConfidence(),
          mapping == null ? "pending" : mapping.getStatus(),
          null,
          null
      ));
    }
    return result;
  }
}
