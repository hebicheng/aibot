package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.BatchMaterial;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.DocumentItemMarketMapping;
import com.aibot.purchase.mapper.BatchMaterialMapper;
import com.aibot.purchase.mapper.DocumentItemMarketMappingMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.MarketProductMapper;
import com.aibot.purchase.mapper.MarketQuoteMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 材料层查询与人工操作（2026-11-04，P2）。
 *
 * <p>材料清单是「一个材料一个入口」的操作面：批次内每种材料一行，
 * 展示两侧的原始行（数量/金额聚合）与共享参数（网价绑定）。
 * 人工操作两类：
 * <ul>
 *   <li>绑定/解绑网价：写在材料行上（quote_source=manual，自动流程永不覆盖），
 *       绑定后立即重锁该批次取价；</li>
 *   <li>手动校准：AI 判同未登记写法 → 重绑 auto → 重算销售价（reconcileBatch alsoPrice 通道）。</li>
 * </ul>
 */
@Service
public class MaterialLayerService {
  private static final Logger log = LoggerFactory.getLogger(MaterialLayerService.class);

  private static final List<String> CONFIRMED_STATUS = List.of("matched", "archived");
  private static final String DOC_PURCHASE = "supply_order";
  private static final String DOC_DELIVERY = "delivery_note";

  private final BatchMaterialMapper materialMapper;
  private final DocumentMapper documentMapper;
  private final DocumentItemMapper itemMapper;
  private final DocumentItemMarketMappingMapper mappingMapper;
  private final MarketProductMapper productMapper;
  private final MarketQuoteMapper quoteMapper;
  private final PurchaseSalePriceService purchaseSalePriceService;
  private final DeliveryNetPriceService deliveryNetPriceService;
  private final MaterialReconcileService reconcileService;
  private final com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService;
  private final MarketCities marketCities;

  public MaterialLayerService(
      BatchMaterialMapper materialMapper,
      DocumentMapper documentMapper,
      DocumentItemMapper itemMapper,
      DocumentItemMarketMappingMapper mappingMapper,
      MarketProductMapper productMapper,
      MarketQuoteMapper quoteMapper,
      PurchaseSalePriceService purchaseSalePriceService,
      DeliveryNetPriceService deliveryNetPriceService,
      MaterialReconcileService reconcileService,
      com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService,
      MarketCities marketCities
  ) {
    this.materialMapper = materialMapper;
    this.documentMapper = documentMapper;
    this.itemMapper = itemMapper;
    this.mappingMapper = mappingMapper;
    this.productMapper = productMapper;
    this.quoteMapper = quoteMapper;
    this.purchaseSalePriceService = purchaseSalePriceService;
    this.deliveryNetPriceService = deliveryNetPriceService;
    this.reconcileService = reconcileService;
    this.marketQuoteImportService = marketQuoteImportService;
    this.marketCities = marketCities;
  }

  /** 材料清单行：身份 + 绑定 + 两侧聚合 + 锁定网价（取价日 = ≤单据日期最近有行情日）。 */
  public record MaterialRow(
      String id,
      String category,
      String grade,
      String diameter,
      String lengthM,
      String origin,
      String matchLevel,
      String matchSource,
      String quoteProductCode,
      String quoteProductName,
      String quoteSpecName,
      String quoteSource,
      String supplyItemId,
      BigDecimal manualSalesPrice,
      /** 人工网价（元/吨）：非空时锁定网价直接用它，优先于行情库取价；只影响本材料/本批次。 */
      BigDecimal manualQuotePrice,
      BigDecimal purchaseTons,
      BigDecimal purchasePrice,
      int purchaseRows,
      BigDecimal deliveryTons,
      BigDecimal deliveryAmount,
      int deliveryRows,
      // 锁定网价口径（2026-11-04）：真实取价日 + 绑定规格当天的网价
      String quoteDate,
      BigDecimal lockedNetPrice,
      /**
       * 销售单价（元/吨，2026-11-06 建）：材料级人工价 ＞ 批次口径推算，逐行算完按采购吨数加权，
       * 与台账采购毛利里的「估算货款」同源。null 时看 salesPriceBasis 说明原因。
       */
      BigDecimal salesPrice,
      /**
       * 销价口径：有值时 manual=材料级人工价 / margin=毛利率口径 / uplift=网价+上浮；
       * 无值时给原因：no_policy=批次未设定价口径 / cost_pending=缺采购吨价 /
       * net_pending=上浮口径缺锁定网价。
       */
      String salesPriceBasis,
      String status
  ) {
  }

  /** 材料清单：一个批次一种材料一行，采购/送货两侧按 material_id 聚合。 */
  public List<MaterialRow> list(String batchId) {
    List<BatchMaterial> materials = materialMapper.selectList(
        Wrappers.<BatchMaterial>lambdaQuery()
            .eq(BatchMaterial::getBatchId, batchId)
            .orderByAsc(BatchMaterial::getCategory)
            .orderByAsc(BatchMaterial::getDiameter));
    if (materials.isEmpty()) {
      return List.of();
    }
    List<Document> documents = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .in(Document::getDocType, DOC_PURCHASE, DOC_DELIVERY)
        .in(Document::getStatus, CONFIRMED_STATUS));
    Map<String, List<DocumentItem>> purchaseByMaterial = new HashMap<>();
    Map<String, List<DocumentItem>> deliveryByMaterial = new HashMap<>();
    Map<String, Document> docsById = new HashMap<>();
    if (!documents.isEmpty()) {
      for (Document document : documents) {
        docsById.put(document.getId(), document);
      }
      List<DocumentItem> items = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
          .in(DocumentItem::getDocumentId, docsById.keySet())
          .orderByAsc(DocumentItem::getSortOrder));
      for (DocumentItem item : items) {
        if (!StringUtils.hasText(item.getMaterialId())) {
          continue;
        }
        Document document = docsById.get(item.getDocumentId());
        boolean purchase = document != null && DOC_PURCHASE.equals(document.getDocType());
        (purchase ? purchaseByMaterial : deliveryByMaterial)
            .computeIfAbsent(item.getMaterialId(), k -> new ArrayList<>()).add(item);
      }
    }
    Map<String, String> productNames = new HashMap<>();
    for (com.aibot.purchase.entity.MarketProduct product : productMapper.selectList(null)) {
      productNames.put(product.getCode(), product.getName());
    }
    // 行情行缓存：同 (日期,产品码) 只查一次（一个批次通常一两个取价日）
    Map<String, List<com.aibot.purchase.entity.MarketQuote>> quoteCache = new HashMap<>();
    // 取价日解析缓存：同 (目标日,产品) 只查一次
    Map<String, LocalDate> lockedDayCache = new HashMap<>();
    // 送货锁定金额（按行）：一次载入批次全部锁定记录
    Map<String, BigDecimal> lockedByItem = new HashMap<>();
    if (!documents.isEmpty()) {
      for (DocumentItemMarketMapping mapping : mappingMapper.selectList(
          Wrappers.<DocumentItemMarketMapping>lambdaQuery()
              .in(DocumentItemMarketMapping::getDocumentId, docsById.keySet()))) {
        if (mapping.getAmount() != null) {
          lockedByItem.put(mapping.getDocumentItemId(), mapping.getAmount());
        }
      }
    }

    // 销售单价（2026-11-06）：材料级人工价 ＞ 批次口径推算，与台账采购毛利同一套取价；
    // 整批采购明细一次取价（上浮口径要连行情锁定行一起读），避免逐材料查库。
    DeliveryNetPriceService.PricingPolicy policy = purchaseSalePriceService.policyFor(batchId);
    List<DocumentItem> allPurchaseItems = purchaseByMaterial.values().stream()
        .flatMap(List::stream).toList();
    Map<String, PurchaseSalePriceService.Price> salePrices =
        purchaseSalePriceService.pricesFor(allPurchaseItems, policy);

    List<MaterialRow> rows = new ArrayList<>();
    for (BatchMaterial material : materials) {
      List<DocumentItem> purchaseItems = purchaseByMaterial.getOrDefault(material.getId(), List.of());
      List<DocumentItem> deliveryItems = deliveryByMaterial.getOrDefault(material.getId(), List.of());

      // 采购侧：吨数合计 + 吨数加权均价（算不出吨数的行不参与）
      BigDecimal purchaseTons = BigDecimal.ZERO;
      BigDecimal purchaseAmount = BigDecimal.ZERO;
      BigDecimal salesAmount = BigDecimal.ZERO;
      BigDecimal salesTons = BigDecimal.ZERO;
      int pricedRows = 0;
      for (DocumentItem item : purchaseItems) {
        BigDecimal tons = purchaseSalePriceService.pricingTons(item);
        BigDecimal price = purchaseSalePriceService.costPrice(item);
        if (tons == null || tons.signum() <= 0 || price == null || price.signum() <= 0) {
          continue;
        }
        purchaseTons = purchaseTons.add(tons);
        purchaseAmount = purchaseAmount.add(price.multiply(tons));
        pricedRows++;
        // 销价与采购吨数一起加权：这样「销价 × 采购吨数」能直接对上台账估算货款
        PurchaseSalePriceService.Price sale = salePrices.get(item.getId());
        BigDecimal unit = sale == null ? null : sale.effective();
        if (unit != null && unit.signum() > 0) {
          salesAmount = salesAmount.add(unit.multiply(tons));
          salesTons = salesTons.add(tons);
        }
      }
      BigDecimal purchasePrice = purchaseTons.signum() > 0
          ? purchaseAmount.divide(purchaseTons, 2, RoundingMode.HALF_UP) : null;

      // 销价：人工价不受口径约束（没有口径也能有价），否则按口径推算结果加权
      BigDecimal salesPrice = null;
      String salesPriceBasis;
      BigDecimal manualPrice = material.getManualSalesPrice();
      if (manualPrice != null && manualPrice.signum() > 0) {
        salesPrice = manualPrice;
        salesPriceBasis = "manual";
      } else if (policy == null) {
        salesPriceBasis = "no_policy";
      } else if ("margin".equals(policy.mode())) {
        if (salesTons.signum() > 0) {
          salesPrice = salesAmount.divide(salesTons, 2, RoundingMode.HALF_UP);
        }
        salesPriceBasis = salesPrice != null ? "margin" : "cost_pending";
      } else {
        if (salesTons.signum() > 0) {
          salesPrice = salesAmount.divide(salesTons, 2, RoundingMode.HALF_UP);
        }
        salesPriceBasis = salesPrice != null ? "uplift" : "net_pending";
      }

      // 送货侧：锁定金额合计 + 计价吨数合计（锁定记录的口径）
      BigDecimal deliveryAmount = BigDecimal.ZERO;
      BigDecimal deliveryTons = BigDecimal.ZERO;
      for (DocumentItem item : deliveryItems) {
        BigDecimal locked = lockedByItem.get(item.getId());
        if (locked != null) {
          deliveryAmount = deliveryAmount.add(locked);
        }
        BigDecimal qty = item.getPricingQuantity();
        if (qty != null && "吨".equals(item.getPricingUnit())) {
          deliveryTons = deliveryTons.add(qty);
        }
      }

      String status;
      if ("manual".equals(material.getQuoteSource())) {
        status = "manual";
      } else if ("auto".equals(material.getQuoteSource())) {
        status = "auto";
      } else if ("duplicate".equals(material.getMatchLevel())) {
        status = "duplicate";
      } else {
        status = "unbound";
      }

      // 锁定网价口径（2026-11-04 修正）：材料行的 quote_date 是原始单据日期，行情库未必有那天的价；
      // 与锁价引擎同口径按「该产品」解析真实取价日（≤该日最近有该产品行情的日子），再按绑定规格取价
      // （产地优先、均价回退）。展示的日期就是「匹配的是哪一天的网价」。
      // 按产品而非全局解析：部分日（截图只导一个品类）不会遮掉其他品类自己的最近行情日。
      String quoteDateText = null;
      BigDecimal lockedNetPrice = null;
      BigDecimal manualQuotePrice = material.getManualQuotePrice();
      if (manualQuotePrice != null && manualQuotePrice.signum() > 0) {
        // 人工网价优先（2026-11-12）：不查行情库，也不依赖取价日有没有数据；
        // 取价日仍解析出来展示（它能说明"如果不人工定价会取哪一天"）
        LocalDate manualDay = resolveLockedDay(material.getQuoteDate(),
            material.getQuoteProductCode(), lockedDayCache);
        quoteDateText = (manualDay != null ? manualDay : material.getQuoteDate()) == null
            ? null : (manualDay != null ? manualDay : material.getQuoteDate()).toString();
        lockedNetPrice = manualQuotePrice;
      } else if (StringUtils.hasText(material.getQuoteProductCode())
          && StringUtils.hasText(material.getQuoteSpecName())) {
        LocalDate lockedDay = resolveLockedDay(material.getQuoteDate(),
            material.getQuoteProductCode(), lockedDayCache);
        if (lockedDay != null) {
          quoteDateText = lockedDay.toString();
          lockedNetPrice = specPrice(lockedDay, material.getQuoteProductCode(),
              material.getQuoteSpecName(), material.getOrigin(), quoteCache);
        }
      }
      // 只有送货行、没有采购行的材料（「0 采购行 / N 送货行」）：
      // 销价上面是按「单价 × 采购吨数」加权算的，采购吨数为 0 就永远加权不出价，
      // 于是明明绑了行情、锁定价也在，却显示「待网价」。按两级兜底（2026-11-12）：
      //   ① 送货单已锁定金额 → 金额 ÷ 送货吨数（反映实际成交，最贴近这行钱）；
      //   ② 否则上浮口径直接用锁定网价 + 上浮（不需要采购价）。
      if (salesPrice == null && deliveryAmount.signum() > 0 && deliveryTons.signum() > 0) {
        salesPrice = deliveryAmount.divide(deliveryTons, 2, RoundingMode.HALF_UP);
        salesPriceBasis = "lock";
      } else if (salesPrice == null && policy != null && "uplift".equals(policy.mode())
          && lockedNetPrice != null && lockedNetPrice.signum() > 0) {
        salesPrice = policy.salesUnitPrice(lockedNetPrice);
        salesPriceBasis = "uplift";
      }
      rows.add(new MaterialRow(
          material.getId(),
          material.getCategory(),
          material.getGrade(),
          material.getDiameter(),
          material.getLengthM(),
          material.getOrigin(),
          material.getMatchLevel(),
          material.getMatchSource(),
          material.getQuoteProductCode(),
          material.getQuoteProductCode() == null ? null
              : productNames.get(material.getQuoteProductCode()),
          material.getQuoteSpecName(),
          material.getQuoteSource(),
          material.getSupplyItemId(),
          material.getManualSalesPrice(),
          manualQuotePrice,
          purchaseTons.signum() > 0 ? purchaseTons : null,
          purchasePrice,
          pricedRows,
          deliveryTons.signum() > 0 ? deliveryTons : null,
          deliveryAmount.signum() > 0 ? deliveryAmount : null,
          deliveryItems.size(),
          quoteDateText,
          lockedNetPrice,
          salesPrice,
          salesPriceBasis,
          status
      ));
    }
    return rows;
  }

  /**
   * 某日某行情规格的价格：先按「本批次行情城市」过滤（绝不跨城市平均），
   * 再产地优先（与取价同规则，互相包含算命中），产地未命中回退该规格同城市均价。
   */
  private BigDecimal specPrice(LocalDate date, String productCode, String specName,
                               String materialOrigin,
                               Map<String, List<com.aibot.purchase.entity.MarketQuote>> cache) {
    if (date == null) {
      return null;
    }
    List<com.aibot.purchase.entity.MarketQuote> sameSpec = marketCities.preferCity(
            quotesOf(date, productCode, cache))
        .stream()
        .filter(quote -> specName.equals(quote.getSpecName()))
        .filter(quote -> quote.getPrice() != null && quote.getPrice().signum() > 0)
        .toList();
    if (sameSpec.isEmpty()) {
      return null;
    }
    if (StringUtils.hasText(materialOrigin)) {
      List<com.aibot.purchase.entity.MarketQuote> byOrigin = filterByOrigin(sameSpec, materialOrigin);
      if (!byOrigin.isEmpty()) {
        // 产地命中的多行：来源优先（mysteel 权威价 > gtgqw 爬取价），同来源才平均——
        // 否则人工改价会被爬取价稀释（实测 3540 vs 3310 → 显示 3425）
        return MaterialMarketMatchService.priceOf(byOrigin);
      }
    }
    return average(sameSpec);
  }

  /**
   * 取价日解析（与锁价引擎同口径，按「该产品」各自解析，2026-11-04 修正 '-' 与部分日遮蔽）：
   * 材料行的 quote_date 是原始单据日期，行情库未必有那天的价——
   * ≤该日最近有「该产品」行情的日子才是真实取价日；单据日期早于行情起点取最早行情日；无单据日期按今天。
   */
  private LocalDate resolveLockedDay(LocalDate wanted, String productCode,
                                      Map<String, LocalDate> cache) {
    String key = wanted + "@" + productCode;
    if (cache.containsKey(key)) {
      return cache.get(key);
    }
    LocalDate target = wanted != null ? wanted : LocalDate.now();
    List<com.aibot.purchase.entity.MarketQuote> hit = quoteMapper.selectList(
        Wrappers.<com.aibot.purchase.entity.MarketQuote>lambdaQuery()
            .le(com.aibot.purchase.entity.MarketQuote::getQuoteDate, target)
            .eq(StringUtils.hasText(productCode),
                com.aibot.purchase.entity.MarketQuote::getProductCode, productCode)
            .orderByDesc(com.aibot.purchase.entity.MarketQuote::getQuoteDate)
            .last("LIMIT 1"));
    LocalDate day;
    if (!hit.isEmpty()) {
      day = hit.get(0).getQuoteDate();
    } else {
      List<com.aibot.purchase.entity.MarketQuote> earliest = quoteMapper.selectList(
          Wrappers.<com.aibot.purchase.entity.MarketQuote>lambdaQuery()
              .eq(StringUtils.hasText(productCode),
                  com.aibot.purchase.entity.MarketQuote::getProductCode, productCode)
              .orderByAsc(com.aibot.purchase.entity.MarketQuote::getQuoteDate)
              .last("LIMIT 1"));
      day = earliest.isEmpty() ? null : earliest.get(0).getQuoteDate();
    }
    cache.put(key, day);
    return day;
  }

  private List<com.aibot.purchase.entity.MarketQuote> quotesOf(
      LocalDate date, String productCode,
      Map<String, List<com.aibot.purchase.entity.MarketQuote>> cache) {
    if (date == null) {
      return List.of();
    }
    String key = date + "@" + productCode;
    return cache.computeIfAbsent(key, ignored -> quoteMapper.selectList(
        Wrappers.<com.aibot.purchase.entity.MarketQuote>lambdaQuery()
            .eq(com.aibot.purchase.entity.MarketQuote::getQuoteDate, date)
            .eq(com.aibot.purchase.entity.MarketQuote::getProductCode, productCode)
            // 权威价（mysteel）排前面：同规格同日多源时展示口径与取价口径一致
            .orderByDesc(com.aibot.purchase.entity.MarketQuote::getSource)
            .last("LIMIT 500")));
  }

  private static List<com.aibot.purchase.entity.MarketQuote> filterByOrigin(
      List<com.aibot.purchase.entity.MarketQuote> rows, String origin) {
    String target = origin.trim();
    return rows.stream()
        .filter(quote -> {
          String quoteOrigin = quote.getOrigin() == null ? "" : quote.getOrigin().trim();
          return !quoteOrigin.isEmpty()
              && (quoteOrigin.contains(target) || target.contains(quoteOrigin));
        })
        .toList();
  }

  private static BigDecimal average(List<com.aibot.purchase.entity.MarketQuote> rows) {
    BigDecimal sum = BigDecimal.ZERO;
    for (com.aibot.purchase.entity.MarketQuote quote : rows) {
      sum = sum.add(quote.getPrice());
    }
    return sum.divide(new BigDecimal(rows.size()), 2, RoundingMode.HALF_UP);
  }

  /**
   * 材料级改价（市场行情 · 批次材料表，2026-11-12 重构）：改的是<b>这张材料自己的网价</b>。
   *
   * <p>写 {@code batch_materials.manual_quote_price}，<b>不再往 market_quotes 里钉 mysteel 行</b>。
   * 旧做法是「冒充行情库」：行情库是全局的，改一条会改掉所有批次同产品同规格同产地的取价，
   * 而且同一规格被多条材料共用时（如盘螺 8 与 10 都绑 Φ8-10）改一条会串到另一条。
   *
   * <p>作用域：只影响本材料、本批次；重算只重锁本批次（采购单行情价 + 送货单销售价 + 台账差额）。
   * 要改「市场行情」请去行情 Tab（那是全局的，会刷新受影响批次）。
   *
   * @param price 元/吨；<b>null = 清空人工网价，恢复按行情库取价</b>
   */
  @Transactional
  public ManualQuoteResult updateMaterialQuotePrice(String materialId, BigDecimal price) {
    if (price != null && price.signum() <= 0) {
      throw new com.aibot.purchase.common.ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "价格必须大于 0；清空请留空提交");
    }
    BatchMaterial material = materialMapper.selectById(materialId);
    if (material == null) {
      throw new com.aibot.purchase.common.ApiException(
          org.springframework.http.HttpStatus.NOT_FOUND, "材料不存在");
    }
    if (!StringUtils.hasText(material.getQuoteProductCode())
        || !StringUtils.hasText(material.getQuoteSpecName())) {
      throw new com.aibot.purchase.common.ApiException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "该材料还没有绑定行情规格，请先绑定再改价");
    }
    // 可空列必须显式 set：updateById 会跳过 null，清空会失效（价格落库统一用显式 set）
    materialMapper.update(null, Wrappers.<BatchMaterial>lambdaUpdate()
        .eq(BatchMaterial::getId, material.getId())
        .set(BatchMaterial::getManualQuotePrice, price)
        .set(BatchMaterial::getUpdatedAt, LocalDateTime.now()));
    // 只重锁本批次：人工价是本材料参数，与其它批次无关
    int relocked = deliveryNetPriceService.rerunBatch(material.getBatchId());
    log.info("材料 {}（{} {} {}）人工网价 {} → 只重锁本批次 {} 行，行情库未改动",
        materialId, material.getCategory(), material.getDiameter(), material.getOrigin(),
        price == null ? "清空（恢复行情价）" : price, relocked);
    return new ManualQuoteResult(price, relocked);
  }

  /** 材料人工网价结果：manualQuotePrice=生效值（null=已清空恢复行情价），relocked=重锁的送货单数。 */
  public record ManualQuoteResult(BigDecimal manualQuotePrice, int relocked) {
  }

  /** 绑定请求：manualSalesPrice 选填（元/吨），非空=材料级人工销售价优先于口径推算；显式传 null 可清除。 */
  public record BindRequest(String materialId, String productCode, String specName,
                            String status, BigDecimal manualSalesPrice) {
  }

  /**
   * 人工绑定/解绑材料级网价。manual 永不被自动流程覆盖；
   * 绑定后同步重锁批次（采购单行情价 + 送货单销售价），前端无需再点重跑。
   */
  @Transactional
  public void bind(BindRequest request) {
    BatchMaterial material = materialMapper.selectById(request.materialId());
    if (material == null) {
      throw new com.aibot.purchase.common.ApiException(
          org.springframework.http.HttpStatus.NOT_FOUND, "材料不存在");
    }
    boolean unmatched = "unmatched".equals(request.status());
    if (unmatched) {
      // 解绑：可空列显式置 NULL（updateById 跳过 null 会留脏绑定）
      materialMapper.update(null, Wrappers.<BatchMaterial>lambdaUpdate()
          .eq(BatchMaterial::getId, material.getId())
          .set(BatchMaterial::getQuoteProductCode, null)
          .set(BatchMaterial::getQuoteSpecName, null)
          .set(BatchMaterial::getQuoteOrigin, null)
          .set(BatchMaterial::getQuoteSource, null)
          .set(BatchMaterial::getManualSalesPrice, null)
          .set(BatchMaterial::getUpdatedAt, LocalDateTime.now()));
    } else {
      if (!StringUtils.hasText(request.productCode()) || !StringUtils.hasText(request.specName())) {
        throw new com.aibot.purchase.common.ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "请选择行情产品与规格");
      }
      if (request.manualSalesPrice() != null && request.manualSalesPrice().signum() <= 0) {
        throw new com.aibot.purchase.common.ApiException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "人工销售价必须大于 0");
      }
      materialMapper.update(null, Wrappers.<BatchMaterial>lambdaUpdate()
          .eq(BatchMaterial::getId, material.getId())
          .set(BatchMaterial::getQuoteProductCode, request.productCode())
          .set(BatchMaterial::getQuoteSpecName, request.specName())
          .set(BatchMaterial::getQuoteOrigin, null)
          .set(BatchMaterial::getQuoteSource, "manual")
          // 人工销售价（元/吨，选填）：非空时锁价优先于口径推算；留空/清空恢复自动
          .set(BatchMaterial::getManualSalesPrice, request.manualSalesPrice())
          .set(BatchMaterial::getUpdatedAt, LocalDateTime.now()));
    }
    // 重锁：绑定立即生效到两侧取价
    for (Document supply : deliveryNetPriceService.confirmedSupplies(material.getBatchId())) {
      deliveryNetPriceService.lockSupplyPrices(supply);
    }
    deliveryNetPriceService.resyncDeliverySales(material.getBatchId(), false);
    log.info("材料 {} 人工{}网价绑定，批次 {} 已重锁", material.getId(),
        unmatched ? "解除" : "设置", material.getBatchId());
  }

  /**
   * 手动校准：AI 判同新写法 → 按最新行情重绑 auto（manual 不动）→ 重算销售价。
   * 同步执行（用户点按钮等结果），返回判同摘要供界面提示。
   */
  @Transactional
  public MaterialReconcileService.Result recalibrate(String batchId) {
    MaterialReconcileService.Result result = reconcileService.reconcileBatch(batchId, true);
    for (Document supply : deliveryNetPriceService.confirmedSupplies(batchId)) {
      deliveryNetPriceService.lockSupplyPrices(supply);
    }
    return result;
  }
}
