package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.BatchMaterial;
import com.aibot.purchase.entity.ChatMessage;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.MarketProduct;
import com.aibot.purchase.entity.MarketQuote;
import com.aibot.purchase.mapper.BatchMaterialMapper;
import com.aibot.purchase.mapper.ChatMessageMapper;
import com.aibot.purchase.mapper.MarketProductMapper;
import com.aibot.purchase.mapper.MarketQuoteMapper;
import com.aibot.purchase.service.MaterialCategoryNormalizer;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 权威行情导入（2026-11-04，P3）。
 *
 * <p>链路：聊天上传行情截图 → AI 识别（intent=market_quote_card）→ 待确认卡片
 * （预览行 + 「确认导入行情/放弃」选项）→ 用户确认后落库 market_quotes
 * （<b>source='mysteel'，与爬取数据共存、可回溯</b>）→ 受影响批次重绑重算。
 *
 * <p>「受影响批次」= 材料级网价绑定（quote_product_code + quote_spec_name）命中本次导入
 * 产品+规格的批次：这些批次的取价基准变了，auto 绑定按最新行情重选、送货单销售价重算
 * （人工绑定行不动）。确认前必须人过一眼预览——截图识别可能不准，错价进库会污染取价。
 */
@Service
public class MarketQuoteImportService {
  private static final Logger log = LoggerFactory.getLogger(MarketQuoteImportService.class);

  /** 权威行情的数据源标记：与爬取的 gtgqw 共存，取价排序时优先（见对比查询）。 */
  public static final String SOURCE_AUTHORITATIVE = "mysteel";
  public static final String OPTION_CONFIRM = "确认导入行情";
  public static final String OPTION_CANCEL = "放弃";

  private final MarketQuoteMapper quoteMapper;
  private final MarketProductMapper productMapper;
  private final BatchMaterialMapper batchMaterialMapper;
  private final MaterialQuoteBindingService bindingService;
  private final DeliveryNetPriceService deliveryNetPriceService;
  private final ChatMessageMapper chatMessageMapper;
  private final ObjectMapper objectMapper;
  /** 行情城市维度：识别出的城市必须过白名单，否则会把产地（如「中天」）当城市写进唯一键。 */
  private final MarketCities marketCities;

  public MarketQuoteImportService(
      MarketQuoteMapper quoteMapper,
      MarketProductMapper productMapper,
      BatchMaterialMapper batchMaterialMapper,
      MaterialQuoteBindingService bindingService,
      DeliveryNetPriceService deliveryNetPriceService,
      ChatMessageMapper chatMessageMapper,
      ObjectMapper objectMapper,
      MarketCities marketCities
  ) {
    this.quoteMapper = quoteMapper;
    this.productMapper = productMapper;
    this.batchMaterialMapper = batchMaterialMapper;
    this.bindingService = bindingService;
    this.deliveryNetPriceService = deliveryNetPriceService;
    this.chatMessageMapper = chatMessageMapper;
    this.objectMapper = objectMapper;
    this.marketCities = marketCities;
  }

  /** 导入结果摘要（供聊天卡片回显）。 */
  public record ImportOutcome(int imported, int skipped, String city, LocalDate quoteDate,
                              int refreshedBatches, int backgroundBatches) {
  }

  /** 后台刷新队列（单线程顺序执行）：发起批次之外的受影响批次异步刷新，不阻塞用户操作。 */
  private final java.util.concurrent.ExecutorService refreshExecutor =
      java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "market-quote-refresh-worker");
        thread.setDaemon(true);
        return thread;
      });

  /** 刷新结果：total=受影响批次总数，background=转后台异步的个数（发起批次之外的）。 */
  private record RefreshResult(int total, int background) {
  }

  /** 批次内待确认的行情导入卡（最新一条）。 */
  public ChatMessage findPending(String userId, String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return null;
    }
    return chatMessageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
            .eq(ChatMessage::getUserId, userId)
            .eq(ChatMessage::getBatchId, batchId)
            .eq(ChatMessage::getBusinessType, "market_quote_card")
            .eq(ChatMessage::getStatus, "pending")
            .orderByDesc(ChatMessage::getCreatedAt))
        .stream().findFirst().orElse(null);
  }

  /** 用户输入是否指向待确认卡的操作项。 */
  public boolean isConfirmOption(ChatMessage card, String text) {
    return card != null && OPTION_CONFIRM.equals(text == null ? "" : text.trim());
  }

  public boolean isCancelOption(ChatMessage card, String text) {
    return card != null && OPTION_CANCEL.equals(text == null ? "" : text.trim());
  }

  /** 归档待确认卡（确认/放弃都要做，避免后续输入被误判成回答）。 */
  public void archivePending(ChatMessage card, String answered) {
    Map<String, Object> payload = fromJson(card.getPayload());
    payload.put("answered", answered);
    card.setPayload(toJson(payload));
    card.setStatus("archived");
    chatMessageMapper.updateById(card);
  }

  /**
   * 确认导入：把预览行落库（source=mysteel，按唯一键 upsert），刷新受影响批次。
   */
  @Transactional
  public ImportOutcome confirm(String userId, String batchId) {
    ChatMessage card = findPending(userId, batchId);
    if (card == null) {
      return new ImportOutcome(0, 0, null, null, 0, 0);
    }
    Map<String, Object> payload = fromJson(card.getPayload());
    List<Map<String, Object>> rows = castRows(payload.get("rows"));
    // 城市必须过白名单（2026-11-12）：截图里的「产地/钢厂」列曾被识别成城市（如「中天」），
    // 而唯一键含 city → 同规格同产地插入第二条，取价再平均成两边都不靠的价。
    // 认不出来就当未标注，落本批次行情城市。
    String rawCity = text(payload.get("city"));
    String city = marketCities.resolve(rawCity);
    if (rawCity != null && !rawCity.equals(city)) {
      log.warn("行情导入：城市「{}」不在已知城市内，按未标注处理 → 使用默认城市「{}」", rawCity, city);
    }
    LocalDate quoteDate = parseDate(text(payload.get("quoteDate")));

    int imported = 0;
    int skipped = 0;
    Set<String> productCodes = new LinkedHashSet<>();
    Set<String> specNames = new LinkedHashSet<>();
    if (!rows.isEmpty()) {
      Map<String, String[]> productIndex = productIndex();
      for (Map<String, Object> row : rows) {
        // 品类逐行优先（截图混多品类时每行自己的品名列），其次整卡 category
        String category = text(row.get("name"));
        if (category == null) {
          category = text(row.get("productName"));
        }
        if (category == null) {
          category = text(payload.get("category"));
        }
        String spec = text(row.get("spec"));
        if (spec == null) {
          skipped++;
          continue;
        }
        String[] codeAndName = resolveProduct(productIndex, category);
        if (codeAndName == null) {
          skipped++;
          continue;
        }
        String grade = text(row.get("grade"));
        // spec_name 与行情库统一格式：品名 规格 材质（如「盘螺 Φ8-10 HRB400」）。
        // 少拼品名前缀会让绑定匹配（quote_spec_name 精确比对）与取价（specName 相等过滤）全部落空
        String specName = codeAndName[1] + " " + spec + (grade != null ? " " + grade : "");
        String origin = text(row.get("origin")) == null ? "" : text(row.get("origin"));
        BigDecimal price = decimal(row.get("price"));
        if (price == null || price.signum() <= 0) {
          // 没有价格的行（停牌/空格）不入库
          skipped++;
          continue;
        }
        upsertQuote(quoteDate, codeAndName[0], codeAndName[1], specName, spec, grade,
            origin, city, price, decimal(row.get("priceChange")));
        productCodes.add(codeAndName[0]);
        specNames.add(specName);
        imported++;
      }
    }
    // 发起批次（导入/改价的来源）同步刷新——用户眼前立即生效；其余批次后台队列刷
    RefreshResult refresh = imported > 0
        ? refreshAffected(productCodes, specNames, batchId)
        : new RefreshResult(0, 0);
    log.info("用户 {} 批次 {} 权威行情导入：{} 条（跳过 {}），同步刷新 1 个来源批次、后台 {} 个批次",
        userId, batchId, imported, skipped, refresh.background());
    return new ImportOutcome(imported, skipped,
        city == null ? "未标注城市" : city, quoteDate, refresh.total(), refresh.background());
  }

  /**
   * 人工改价（行情中心，2026-11-04）：改某条行情（爬取或权威均可）的价格，
   * 改完按该条的「产品+规格」刷新受影响批次——取价日解析到该日的材料自动换用新价，
   * 与截图导入同一条全链路刷新通道。currentBatchId=发起改动的批次（同步优先刷新）。
   */
  @Transactional
  public ImportOutcome updateQuotePrice(String quoteId, BigDecimal price, String currentBatchId) {
    if (price == null || price.signum() <= 0) {
      throw new com.aibot.purchase.common.ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "价格必须大于 0");
    }
    MarketQuote quote = quoteMapper.selectById(quoteId);
    if (quote == null) {
      throw new com.aibot.purchase.common.ApiException(
          org.springframework.http.HttpStatus.NOT_FOUND, "行情条目不存在");
    }
    if (price.compareTo(quote.getPrice() == null ? BigDecimal.ZERO : quote.getPrice()) == 0) {
      return new ImportOutcome(0, 0, quote.getCity(), quote.getQuoteDate(), 0, 0);
    }
    quoteMapper.update(null, Wrappers.<MarketQuote>lambdaUpdate()
        .eq(MarketQuote::getId, quoteId)
        .set(MarketQuote::getPrice, price)
        .set(MarketQuote::getCapturedAt, LocalDateTime.now()));
    log.info("行情 {}（{} {} {} {}）人工改价为 {}，刷新受影响批次",
        quoteId, quote.getQuoteDate(), quote.getProductName(), quote.getSpecName(), quote.getCity(), price);
    RefreshResult refresh = refreshAffected(
        Set.of(quote.getProductCode()), Set.of(quote.getSpecName()), currentBatchId);
    return new ImportOutcome(1, 0, quote.getCity(), quote.getQuoteDate(),
        refresh.total(), refresh.background());
  }

  /**
   * 刷新受影响批次（2026-11-04 补两个边界）：
   * ① 绑定命中：材料绑定（产品+规格）命中本次导入的批次——重绑后取价日解析自然落到新日期，
   *    不需要任何遍历（绑定列有索引，日期是取价时现解析的）；
   * ② 未绑定材料的批次：材料大类能对上本次导入品类也纳入，bindBatch 会按最新行情补绑；
   * 刷新动作 = 重绑 auto + 重锁采购单行情价 + 送货单重算（此前漏了采购侧）；人工绑定不动。
   * preferredBatchId（发起改动的批次）同步执行——用户眼前立即生效；其余批次提交后台队列。
   */
  private RefreshResult refreshAffected(Set<String> productCodes, Set<String> specNames,
                                        String preferredBatchId) {
    if (productCodes.isEmpty() || specNames.isEmpty()) {
      return new RefreshResult(0, 0);
    }
    Set<String> batchIds = new LinkedHashSet<>();
    for (BatchMaterial material : batchMaterialMapper.selectList(
        Wrappers.<BatchMaterial>lambdaQuery()
            .in(BatchMaterial::getQuoteProductCode, productCodes))) {
      if (specNames.contains(material.getQuoteSpecName())
          && StringUtils.hasText(material.getBatchId())) {
        batchIds.add(material.getBatchId());
      }
    }
    // 未绑定材料的批次：材料大类（螺纹钢/盘螺/高线）对上本次导入品类即可，绑不绑得上由 bindBatch 决定
    Set<String> categories = importedCategories(productCodes);
    if (!categories.isEmpty()) {
      for (BatchMaterial material : batchMaterialMapper.selectList(
          Wrappers.<BatchMaterial>lambdaQuery()
              .isNull(BatchMaterial::getQuoteProductCode)
              .in(BatchMaterial::getCategory, categories))) {
        if (StringUtils.hasText(material.getBatchId())) {
          batchIds.add(material.getBatchId());
        }
      }
    }
    List<String> background = new ArrayList<>();
    for (String batchId : batchIds) {
      if (batchId.equals(preferredBatchId)) {
        refreshOneBatch(batchId);
      } else {
        background.add(batchId);
      }
    }
    if (!background.isEmpty()) {
      refreshExecutor.submit(() -> background.forEach(this::refreshOneBatch));
    }
    return new RefreshResult(batchIds.size(), background.size());
  }

  /** 单批次刷新（重绑 + 采购单重锁 + 送货单重算）；后台线程与同步路径共用，异常只记日志。 */
  private void refreshOneBatch(String batchId) {
    try {
      bindingService.bindBatch(batchId, true);
      for (Document supply : deliveryNetPriceService.confirmedSupplies(batchId)) {
        deliveryNetPriceService.lockSupplyPrices(supply);
      }
      deliveryNetPriceService.resyncDeliverySales(batchId, false);
    } catch (RuntimeException ex) {
      log.warn("权威行情导入后刷新批次 {} 失败：{}", batchId, ex.toString());
    }
  }

  /** 供其他服务（材料级改价）复用的刷新入口：按产品+规格刷新受影响批次，发起批次可指定同步优先。 */
  public ImportOutcome refreshAffectedBatches(Set<String> productCodes, Set<String> specNames,
                                              String preferredBatchId) {
    RefreshResult refresh = refreshAffected(productCodes, specNames, preferredBatchId);
    return new ImportOutcome(0, 0, null, null, refresh.total(), refresh.background());
  }

  /** 本次导入产品对应的标准材料大类（螺纹钢/盘螺/高线），供未绑定材料的批次匹配。 */
  private Set<String> importedCategories(Set<String> productCodes) {
    Set<String> categories = new LinkedHashSet<>();
    for (MarketProduct product : productMapper.selectList(
        Wrappers.<MarketProduct>lambdaQuery().in(MarketProduct::getCode, productCodes))) {
      String normalized = MaterialCategoryNormalizer.category(product.getName());
      if (StringUtils.hasText(normalized)) {
        categories.add(normalized);
      }
    }
    return categories;
  }

  /** 唯一键 (date, source, product, specName, city, origin) 命中则更新价格，否则插入。 */
  private void upsertQuote(LocalDate quoteDate, String productCode, String productName,
                           String specName, String spec, String grade, String origin,
                           String city, BigDecimal price, BigDecimal priceChange) {
    MarketQuote existing = quoteMapper.selectOne(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, quoteDate)
        .eq(MarketQuote::getSource, SOURCE_AUTHORITATIVE)
        .eq(MarketQuote::getProductCode, productCode)
        .eq(MarketQuote::getSpecName, specName)
        .eq(MarketQuote::getCity, city)
        .eq(MarketQuote::getOrigin, origin)
        .last("LIMIT 1"));
    if (existing != null) {
      quoteMapper.update(null, Wrappers.<MarketQuote>lambdaUpdate()
          .eq(MarketQuote::getId, existing.getId())
          .set(MarketQuote::getPrice, price)
          .set(MarketQuote::getPriceChange, priceChange)
          .set(MarketQuote::getCapturedAt, LocalDateTime.now()));
      return;
    }
    MarketQuote quote = new MarketQuote();
    quote.setQuoteDate(quoteDate);
    quote.setSource(SOURCE_AUTHORITATIVE);
    quote.setProductCode(productCode);
    quote.setProductName(productName);
    quote.setSpecName(specName);
    quote.setSpec(spec);
    quote.setGrade(grade);
    quote.setCity(city);
    quote.setOrigin(origin);
    quote.setPrice(price);
    quote.setPriceChange(priceChange);
    quote.setCapturedAt(LocalDateTime.now());
    quoteMapper.insert(quote);
  }

  /** 品类名 → (产品码, 产品名)：只认行情库里启用的产品（螺纹钢/盘螺/高线/圆钢…）。 */
  private Map<String, String[]> productIndex() {
    Map<String, String[]> index = new LinkedHashMap<>();
    for (MarketProduct product : productMapper.selectList(Wrappers.<MarketProduct>lambdaQuery()
        .eq(MarketProduct::getEnabled, true))) {
      String normalized = MaterialCategoryNormalizer.category(product.getName());
      if (StringUtils.hasText(normalized)) {
        index.putIfAbsent(normalized, new String[]{product.getCode(), product.getName()});
      }
    }
    return index;
  }

  /** 行业叫法归一到标准品类再查产品（盘元→盘螺、线材→高线、三级螺纹钢→螺纹钢）。 */
  private String[] resolveProduct(Map<String, String[]> index, String category) {
    if (category == null) {
      return null;
    }
    String normalized = MaterialCategoryNormalizer.category(category);
    return index.get(normalized);
  }

  private LocalDate parseDate(String text) {
    if (text == null) {
      return LocalDate.now();
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException ex) {
      return LocalDate.now();
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> castRows(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> map) {
        rows.add((Map<String, Object>) map);
      }
    }
    return rows;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String textValue = String.valueOf(value).trim();
    return textValue.isEmpty() || "null".equalsIgnoreCase(textValue) ? null : textValue;
  }

  private static BigDecimal decimal(Object value) {
    if (value instanceof Number number) {
      return new BigDecimal(number.toString());
    }
    if (value instanceof String stringValue && !stringValue.isBlank()) {
      try {
        return new BigDecimal(stringValue.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private Map<String, Object> fromJson(String payload) {
    if (payload == null || payload.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(payload, new TypeReference<LinkedHashMap<String, Object>>() {
      });
    } catch (Exception ex) {
      return new LinkedHashMap<>();
    }
  }

  private String toJson(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception ex) {
      return null;
    }
  }
}
