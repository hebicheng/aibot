package com.aibot.purchase.service.market;

import com.aibot.purchase.common.InsufficientCreditsException;
import com.aibot.purchase.entity.BatchMaterial;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.MarketProduct;
import com.aibot.purchase.entity.MarketQuote;
import com.aibot.purchase.mapper.BatchMaterialMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.MarketProductMapper;
import com.aibot.purchase.mapper.MarketQuoteMapper;
import com.aibot.purchase.service.BillingService;
import com.aibot.purchase.service.ai.QwenClient;
import com.aibot.purchase.service.ai.QwenClient.QwenResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 材料级网价绑定（2026-10-30）。
 *
 * <p>要解决的问题：网价原本按「单据行的材料名+规格」各自匹配，采购单写「三级螺纹钢 12*9 中天」、
 * 送货单写「螺纹钢HRB400 12*9」，两条路径解析出的产品/规格可能不同 → 同一个材料两个网价。
 *
 * <p><b>一个材料只绑一次，绑在材料上</b>（{@code quote_product_code}/{@code quote_spec_name}/
 * {@code quote_source}），采购行与送货行都按这条绑定取价。
 *
 * <p>选哪条行情：<b>交给模型</b>。给它「这个材料是什么」（五维 + 原始写法）和「那天行情里有哪些
 * 产品/规格」，由它选一个；模型只做选择，<b>价格一律从行情表取</b>——价格是事实，不允许模型生成。
 * 模型不可用/选不出时退回按写法解析的规则匹配，保证不会因为没配模型就没网价。
 *
 * <p>钉住而不是每次现算：行情是"某一天的价格"这个事实，每次重算会让已对账的数字随行情库补录漂移。
 * 要更新就重跑批次（本类按 auto 刷新、manual 不动）。
 */
@Service
public class MaterialQuoteBindingService {

  private static final Logger log = LoggerFactory.getLogger(MaterialQuoteBindingService.class);

  public static final String SOURCE_AUTO = "auto";
  public static final String SOURCE_MANUAL = "manual";

  private static final String DOC_PURCHASE = "supply_order";
  private static final String DOC_DELIVERY = "delivery_note";
  private static final List<String> CONFIRMED_STATUS = List.of("matched", "archived");
  /** 候选行情的上限：一个取价日通常几十到几百条（产品×规格×产地）。 */
  private static final int CANDIDATE_LIMIT = 400;

  private final BatchMaterialMapper materialMapper;
  private final DocumentMapper documentMapper;
  private final DocumentItemMapper itemMapper;
  private final MarketQuoteMapper quoteMapper;
  private final MarketProductMapper productMapper;
  private final MaterialMarketMatchService matchService;
  private final QwenClient qwenClient;
  private final BillingService billingService;
  private final ObjectMapper objectMapper;

  public MaterialQuoteBindingService(
      BatchMaterialMapper materialMapper,
      DocumentMapper documentMapper,
      DocumentItemMapper itemMapper,
      MarketQuoteMapper quoteMapper,
      MarketProductMapper productMapper,
      MaterialMarketMatchService matchService,
      QwenClient qwenClient,
      BillingService billingService,
      ObjectMapper objectMapper
  ) {
    this.materialMapper = materialMapper;
    this.documentMapper = documentMapper;
    this.itemMapper = itemMapper;
    this.quoteMapper = quoteMapper;
    this.productMapper = productMapper;
    this.matchService = matchService;
    this.qwenClient = qwenClient;
    this.billingService = billingService;
    this.objectMapper = objectMapper;
  }

  /** 一条明细行 + 是否采购侧。 */
  private record Row(DocumentItem item, Document document, boolean purchase) {
  }

  /** 选定的行情：产品码 + 规格名（价格不在这里，取价时从行情表取）。 */
  private record Chosen(String productCode, String specName) {
  }

  /**
   * 整批刷新材料级网价绑定，返回本次新绑定/变更的材料数。
   *
   * <p>默认（{@code refreshAuto=false}）只处理<b>还没绑过</b>的材料：已按 auto 绑好的不再重复问模型，
   * 否则每确认一张单据都要为整批材料付一次 AI 费用。要按最新行情刷新 auto 绑定，走重跑批次。
   */
  public int bindBatch(String batchId) {
    return bindBatch(batchId, false);
  }

  public int bindBatch(String batchId, boolean refreshAuto) {
    if (!StringUtils.hasText(batchId)) {
      return 0;
    }
    try {
      return doBind(batchId, refreshAuto);
    } catch (RuntimeException e) {
      log.warn("批次 {} 材料级网价绑定失败（不影响单据入库）：{}", batchId, e.toString());
      return 0;
    }
  }

  private int doBind(String batchId, boolean refreshAuto) {
    List<BatchMaterial> materials = materialMapper.selectList(
        Wrappers.<BatchMaterial>lambdaQuery().eq(BatchMaterial::getBatchId, batchId));
    if (materials.isEmpty()) {
      return 0;
    }
    List<Document> documents = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .in(Document::getDocType, DOC_PURCHASE, DOC_DELIVERY)
        .in(Document::getStatus, CONFIRMED_STATUS));
    if (documents.isEmpty()) {
      return 0;
    }
    Map<String, List<Row>> rowsByMaterial = loadRows(documents);
    if (rowsByMaterial.isEmpty()) {
      return 0;
    }

    Map<LocalDate, LocalDate> resolvedDays = new HashMap<>();
    Map<String, List<MarketQuote>> quoteCache = new HashMap<>();
    // ① 先让模型选（主路径）：材料是什么 + 那天有哪些行情 → 选一条
    Map<String, Chosen> chosenByAi =
        chooseViaAi(batchId, materials, rowsByMaterial, resolvedDays, refreshAuto);
    int changed = 0;
    for (BatchMaterial material : materials) {
      if (!writable(material, refreshAuto)) {
        continue;
      }
      List<Row> rows = rowsByMaterial.get(material.getId());
      Chosen chosen = chosenByAi.get(material.getId());
      // ② 模型没选出（未配置/没命中候选）时退回按写法解析的规则匹配
      if (chosen == null) {
        chosen = chooseByRule(material, rows, resolvedDays, quoteCache);
      }
      if (chosen == null) {
        continue;
      }
      if (apply(material, chosen.productCode(), chosen.specName())) {
        changed++;
      }
    }
    if (changed > 0) {
      log.info("批次 {} 材料级网价自动绑定：更新 {} 种材料（AI 选出 {}）",
          batchId, changed, chosenByAi.size());
    }
    return changed;
  }

  /**
   * 这个材料的绑定是否可以被自动流程改写。
   *
   * <p>人工绑定（quote_source=manual）永远不动；quote_source 为空但有绑定值的属于 V24 之前
   * 人工绑的（当时没有来源列），也按人工处理——宁可少刷新，也不能把人选的价冲掉。
   *
   * <p>auto 绑好的默认也不动（只在重跑批次时刷新）：材料身份一改就是新行、没有绑定，
   * 自然会重新绑；已绑的重复问模型只会白花钱。
   */
  private static boolean writable(BatchMaterial material, boolean refreshAuto) {
    if (SOURCE_MANUAL.equals(material.getQuoteSource())) {
      return false;
    }
    boolean bound = StringUtils.hasText(material.getQuoteProductCode())
        && StringUtils.hasText(material.getQuoteSpecName());
    if (!bound) {
      return true;
    }
    return SOURCE_AUTO.equals(material.getQuoteSource()) && refreshAuto;
  }

  // ---------------------------------------------------------------------------
  // 主路径：模型选行情规格
  // ---------------------------------------------------------------------------

  /**
   * 让模型给每个待绑定材料选一条行情规格。
   *
   * <p>按"取价日"分组各发一次（同一批的材料取价日通常相同，一般就一次调用）。
   * 模型返回的产品+规格必须落在本次给定的候选集合里，落不进去的一律丢弃——
   * 这样即使模型编造，也不会把绑定写到不存在的行情上。
   *
   * <p>任何异常（未配置、超时、积分不足、返回不合法）都只记 warn 并返回已拿到的部分，
   * 由调用方退回规则匹配。
   */
  private Map<String, Chosen> chooseViaAi(String batchId, List<BatchMaterial> materials,
                                          Map<String, List<Row>> rowsByMaterial,
                                          Map<LocalDate, LocalDate> resolvedDays,
                                          boolean refreshAuto) {
    Map<String, Chosen> result = new HashMap<>();
    if (!qwenClient.isConfigured()) {
      return result;
    }
    // 待绑定材料按取价日分组
    Map<LocalDate, List<BatchMaterial>> byDay = new LinkedHashMap<>();
    for (BatchMaterial material : materials) {
      if (!writable(material, refreshAuto)) {
        continue;
      }
      List<Row> rows = rowsByMaterial.get(material.getId());
      if (rows == null || rows.isEmpty()) {
        continue;
      }
      LocalDate wanted = material.getQuoteDate() != null
          ? material.getQuoteDate() : earliestDate(rows);
      LocalDate day = resolveQuoteDay(wanted, resolvedDays);
      if (day == null) {
        continue;
      }
      byDay.computeIfAbsent(day, key -> new ArrayList<>()).add(material);
    }
    for (Map.Entry<LocalDate, List<BatchMaterial>> entry : byDay.entrySet()) {
      try {
        result.putAll(askOneDay(batchId, entry.getKey(), entry.getValue(), rowsByMaterial));
      } catch (RuntimeException ex) {
        log.warn("批次 {} 网价 AI 选择失败（{}）：{}", batchId, entry.getKey(), ex.toString());
      }
    }
    return result;
  }

  private Map<String, Chosen> askOneDay(String batchId, LocalDate day, List<BatchMaterial> materials,
                                        Map<String, List<Row>> rowsByMaterial) {
    List<MarketQuote> quotes = quoteRows(day);
    if (quotes.isEmpty()) {
      return Map.of();
    }
    // 候选：产品码 + 规格名去重（同规格多产地合成一条候选，产地留到取价时再定）
    Map<String, String> specNames = new LinkedHashMap<>();
    Set<String> pairs = new LinkedHashSet<>();
    for (MarketQuote quote : quotes) {
      if (!StringUtils.hasText(quote.getProductCode()) || !StringUtils.hasText(quote.getSpecName())) {
        continue;
      }
      String key = quote.getProductCode() + "@" + quote.getSpecName();
      if (pairs.add(key)) {
        specNames.put(key, quote.getSpecName());
      }
    }
    if (pairs.isEmpty()) {
      return Map.of();
    }
    Map<String, String> productNames = productNames();

    QwenResult reply;
    try {
      reply = qwenClient.completeJson(quoteSystemPrompt(), quoteUserPrompt(day, materials, pairs,
          specNames, productNames, rowsByMaterial));
    } catch (RuntimeException ex) {
      log.warn("批次 {} 网价 AI 调用失败：{}", batchId, ex.toString());
      return Map.of();
    }
    try {
      // 先计费：token 已花。积分不足则不落库（不能"没扣钱却写了绑定"）
      billingService.chargeMaterialQuote(batchId, reply.usage(), null);
    } catch (InsufficientCreditsException ex) {
      log.warn("批次 {} 网价 AI 选择积分不足，结果未落库：{}", batchId, ex.getMessage());
      return Map.of();
    }

    Map<String, Chosen> result = new HashMap<>();
    List<String> ordered = new ArrayList<>(pairs);
    try {
      JsonNode mappings = objectMapper.readTree(reply.content()).path("mappings");
      if (!mappings.isArray()) {
        return Map.of();
      }
      for (JsonNode node : mappings) {
        int index = node.path("index").asInt(-1);
        if (index < 1 || index > materials.size()) {
          continue;
        }
        String productCode = text(node, "productCode");
        String specName = text(node, "specName");
        if (productCode == null || specName == null) {
          continue;
        }
        // 必须在候选里：模型只能"选"，不能"造"
        if (!ordered.contains(productCode + "@" + specName)) {
          continue;
        }
        result.put(materials.get(index - 1).getId(), new Chosen(productCode, specName));
      }
    } catch (Exception ex) {
      log.warn("网价 AI 结果解析失败（{}）：{}", day, ex.toString());
      return result;
    }
    return result;
  }

  private String quoteSystemPrompt() {
    return """
        你是钢材贸易的行情对照助手。给定「本批次待定价的材料」和「某个取价日的行情候选」，
        请为每种材料选出对应的行情「产品 + 规格」。

        判断依据：
        1. 品类要一致：螺纹钢 / 盘螺 / 高线 / 圆钢 不能混（盘元=盘螺，线材=高线）。
        2. 直径要一致：12 / Φ12 / 12mm 是同一个；「12*9」前一个数是直径。
        3. 定尺只对螺纹钢、圆钢有意义：9m / 12m；盘螺、高线、盘圆成盘供货，没有定尺。
        4. 牌号不同就是不同品种（HRB400 与 HRB400E、HRB500 不能混）。
        5. 产地不影响选哪条规格（单价按材料产地另取），只看产品与规格。

        铁律：
        - productCode 与 specName 必须从给定候选里原样选，禁止编造。
        - 找不到对应行情就省略这条，不要硬选。
        - 只返回 JSON，不要解释。

        返回格式：
        {"mappings":[{"index":1,"productCode":"RB","specName":"Φ12*9"}]}
        """;
  }

  private String quoteUserPrompt(LocalDate day, List<BatchMaterial> materials, Set<String> pairs,
                                 Map<String, String> specNames, Map<String, String> productNames,
                                 Map<String, List<Row>> rowsByMaterial) {
    List<Map<String, Object>> materialList = new ArrayList<>();
    for (int i = 0; i < materials.size(); i++) {
      BatchMaterial material = materials.get(i);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", i + 1);
      row.put("category", material.getCategory());
      row.put("grade", material.getGrade());
      row.put("diameter", material.getDiameter());
      row.put("lengthM", material.getLengthM());
      row.put("origin", material.getOrigin());
      // 原始写法：五维可能不全（采购单常不写牌号），写法里往往有线索
      List<Row> rows = rowsByMaterial.getOrDefault(material.getId(), List.of());
      if (!rows.isEmpty()) {
        DocumentItem sample = rows.get(0).item();
        row.put("writtenAs", sample.getMaterialName()
            + (StringUtils.hasText(sample.getSpec()) ? " / " + sample.getSpec() : ""));
      }
      materialList.add(row);
    }
    List<Map<String, Object>> candidates = new ArrayList<>();
    for (String pair : pairs) {
      String code = pair.substring(0, pair.indexOf('@'));
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("productCode", code);
      row.put("productName", productNames.get(code));
      row.put("specName", specNames.get(pair));
      candidates.add(row);
    }
    try {
      return "取价日：" + day
          + "\n\n待定价材料：\n" + objectMapper.writeValueAsString(materialList)
          + "\n\n行情候选：\n" + objectMapper.writeValueAsString(candidates)
          + "\n\n请逐条给出 index 对应的 productCode 与 specName。";
    } catch (Exception ex) {
      return "取价日：" + day + "\n待定价材料：" + materialList + "\n行情候选：" + candidates;
    }
  }

  private List<MarketQuote> quoteRows(LocalDate day) {
    return quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, day)
        .orderByAsc(MarketQuote::getProductCode)
        .last("LIMIT " + CANDIDATE_LIMIT));
  }

  private Map<String, String> productNames() {
    Map<String, String> names = new HashMap<>();
    for (MarketProduct product : productMapper.selectList(null)) {
      names.put(product.getCode(), product.getName());
    }
    return names;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText("").trim();
    return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
  }

  // ---------------------------------------------------------------------------
  // 降级路径：按写法解析（模型不可用时保证有网价）
  // ---------------------------------------------------------------------------

  /** 能解析出产品码与规格的候选写法。 */
  private record Candidate(String productCode, String spec, String grade) {
  }

  /**
   * 规则兜底：拿材料下"能解析出产品码+规格"的代表写法去匹配当天行情。
   *
   * <p>只为"模型不可用"这条降级路径服务，所以保持极简：采购行优先（成本权威、与取价日同源），
   * 解析不出再退送货行。
   */
  private Chosen chooseByRule(BatchMaterial material, List<Row> rows,
                              Map<LocalDate, LocalDate> resolvedDays,
                              Map<String, List<MarketQuote>> quoteCache) {
    Candidate candidate = pickCandidate(rows);
    if (candidate == null) {
      return null;
    }
    LocalDate wanted = material.getQuoteDate() != null ? material.getQuoteDate() : earliestDate(rows);
    LocalDate day = resolveQuoteDay(wanted, resolvedDays);
    if (day == null) {
      return null;
    }
    List<MarketQuote> quotes = quotesOf(day, candidate.productCode(), quoteCache);
    if (quotes.isEmpty()) {
      return null;
    }
    MarketQuote quote = matchService.matchFromRows(quotes, candidate.spec(), candidate.grade());
    if (quote == null || !StringUtils.hasText(quote.getSpecName())) {
      return null;
    }
    return new Chosen(quote.getProductCode(), quote.getSpecName());
  }

  /** 选代表写法：采购行优先（按单据日期升序），其次送货行。 */
  private static Candidate pickCandidate(List<Row> rows) {
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    List<Row> ordered = rows.stream()
        .sorted((left, right) -> {
          if (left.purchase() != right.purchase()) {
            return left.purchase() ? -1 : 1;
          }
          LocalDate a = left.document().getDocDate();
          LocalDate b = right.document().getDocDate();
          if (a == null || b == null) {
            return a == null && b == null ? 0 : (a == null ? 1 : -1);
          }
          return a.compareTo(b);
        })
        .toList();
    for (Row row : ordered) {
      MarketNameNormalizer.Parsed parsed = MarketNameNormalizer.parse(
          row.item().getMaterialName(), row.item().getSpec());
      if (parsed.productCode() != null && parsed.spec() != null) {
        return new Candidate(parsed.productCode(), parsed.spec(), parsed.grade());
      }
    }
    return null;
  }

  /** 该材料下的明细行（材料 id → 行）。 */
  private Map<String, List<Row>> loadRows(List<Document> documents) {
    Map<String, Document> byId = new HashMap<>();
    for (Document document : documents) {
      byId.put(document.getId(), document);
    }
    List<DocumentItem> items = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .in(DocumentItem::getDocumentId, byId.keySet())
        .orderByAsc(DocumentItem::getSortOrder));
    Map<String, List<Row>> rows = new HashMap<>();
    for (DocumentItem item : items) {
      Document document = byId.get(item.getDocumentId());
      if (document == null || !StringUtils.hasText(item.getMaterialId())) {
        continue;
      }
      rows.computeIfAbsent(item.getMaterialId(), key -> new ArrayList<>())
          .add(new Row(item, document, DOC_PURCHASE.equals(document.getDocType())));
    }
    return rows;
  }

  private static LocalDate earliestDate(List<Row> rows) {
    LocalDate earliest = null;
    for (Row row : rows) {
      LocalDate date = row.document().getDocDate();
      if (date != null && (earliest == null || date.isBefore(earliest))) {
        earliest = date;
      }
    }
    return earliest;
  }

  /**
   * 取价日：≤ 期望日期最近的有行情日；期望日期早于行情起点时取最早行情日。
   * 与 {@code DeliveryNetPriceService#resolveQuoteDay} 同口径——绑定时用的日子和锁价时用的
   * 必须是同一天，否则绑定存的是 A 日行情、锁价去 B 日取，永远匹配不上。
   */
  private LocalDate resolveQuoteDay(LocalDate wanted, Map<LocalDate, LocalDate> cache) {
    LocalDate target = wanted != null ? wanted : LocalDate.now();
    if (cache.containsKey(target)) {
      return cache.get(target);
    }
    List<MarketQuote> hit = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .le(MarketQuote::getQuoteDate, target)
        .orderByDesc(MarketQuote::getQuoteDate)
        .last("LIMIT 1"));
    LocalDate day;
    if (!hit.isEmpty()) {
      day = hit.get(0).getQuoteDate();
    } else {
      List<MarketQuote> earliest = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
          .orderByAsc(MarketQuote::getQuoteDate)
          .last("LIMIT 1"));
      day = earliest.isEmpty() ? null : earliest.get(0).getQuoteDate();
    }
    cache.put(target, day);
    return day;
  }

  private List<MarketQuote> quotesOf(LocalDate day, String productCode,
                                     Map<String, List<MarketQuote>> cache) {
    String key = day + "@" + productCode;
    return cache.computeIfAbsent(key, ignored -> quoteMapper.selectList(
        Wrappers.<MarketQuote>lambdaQuery()
            .eq(MarketQuote::getQuoteDate, day)
            .eq(MarketQuote::getProductCode, productCode)
            .last("LIMIT 500")));
  }

  /** 写回绑定参数（空列显式 set：重绑到别的规格时旧值必须被覆盖）。 */
  private boolean apply(BatchMaterial material, String productCode, String specName) {
    boolean unchanged = productCode.equals(material.getQuoteProductCode())
        && specName.equals(material.getQuoteSpecName())
        && SOURCE_AUTO.equals(material.getQuoteSource());
    if (unchanged) {
      return false;
    }
    materialMapper.update(null, Wrappers.<BatchMaterial>lambdaUpdate()
        .set(BatchMaterial::getQuoteProductCode, productCode)
        .set(BatchMaterial::getQuoteSpecName, specName)
        .set(BatchMaterial::getQuoteSource, SOURCE_AUTO)
        .set(BatchMaterial::getUpdatedAt, LocalDateTime.now())
        .eq(BatchMaterial::getId, material.getId()));
    material.setQuoteProductCode(productCode);
    material.setQuoteSpecName(specName);
    material.setQuoteSource(SOURCE_AUTO);
    return true;
  }
}
