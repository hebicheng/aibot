package com.aibot.purchase.service.market;

import com.aibot.purchase.common.ApiException;
import com.aibot.purchase.dto.BusinessDtos.BatchDto;
import com.aibot.purchase.dto.MarketDtos.BatchCompare;
import com.aibot.purchase.dto.MarketDtos.CompareRow;
import com.aibot.purchase.dto.MarketDtos.MarketMeta;
import com.aibot.purchase.dto.MarketDtos.ProductItem;
import com.aibot.purchase.dto.MarketDtos.QuoteRow;
import com.aibot.purchase.entity.MarketProduct;
import com.aibot.purchase.entity.MarketQuote;
import com.aibot.purchase.mapper.MarketProductMapper;
import com.aibot.purchase.mapper.MarketQuoteMapper;
import com.aibot.purchase.service.BusinessQueryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 市场行情查询：全量行情（Tab2）、筛选元数据、项目批次对比（Tab1）。
 */
@Service
public class MarketQueryService {

  private final MarketQuoteMapper quoteMapper;
  private final MarketProductMapper productMapper;
  private final com.aibot.purchase.mapper.DocumentMapper documentMapper;
  private final com.aibot.purchase.mapper.DocumentItemMapper documentItemMapper;
  private final com.aibot.purchase.mapper.DocumentItemMarketMappingMapper itemNetMappingMapper;
  private final BusinessQueryService businessQueryService;
  /** 城市维度的唯一来源（白名单 + 本批次行情城市）。 */
  private final MarketCities marketCities;

  public MarketQueryService(
      MarketQuoteMapper quoteMapper,
      MarketProductMapper productMapper,
      com.aibot.purchase.mapper.DocumentMapper documentMapper,
      com.aibot.purchase.mapper.DocumentItemMapper documentItemMapper,
      com.aibot.purchase.mapper.DocumentItemMarketMappingMapper itemNetMappingMapper,
      BusinessQueryService businessQueryService,
      MarketCities marketCities
  ) {
    this.quoteMapper = quoteMapper;
    this.productMapper = productMapper;
    this.documentMapper = documentMapper;
    this.documentItemMapper = documentItemMapper;
    this.itemNetMappingMapper = itemNetMappingMapper;
    this.businessQueryService = businessQueryService;
    this.marketCities = marketCities;
  }

  /** 最新行情日期（库中最大的 quote_date）。 */
  public LocalDate latestDate() {
    List<MarketQuote> rows = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .orderByDesc(MarketQuote::getQuoteDate)
        .last("LIMIT 1"));
    return rows.isEmpty() ? LocalDate.now() : rows.get(0).getQuoteDate();
  }

  /**
   * Tab2 全量行情：默认最新一日，支持分类/产品/城市/产地/关键词过滤。
   */
  public List<QuoteRow> quotes(LocalDate date, String category, String productCode,
                               String city, String origin, String keyword) {
    LocalDate target = date != null ? date : latestDate();
    var query = Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, target)
        .eq(StringUtils.hasText(productCode), MarketQuote::getProductCode, productCode)
        .eq(StringUtils.hasText(city), MarketQuote::getCity, city)
        .eq(StringUtils.hasText(origin), MarketQuote::getOrigin, origin)
        .orderByAsc(MarketQuote::getProductCode)
        .orderByAsc(MarketQuote::getSpecName);
    List<MarketQuote> rows;
    if (StringUtils.hasText(category)) {
      rows = productMapper.selectList(Wrappers.<MarketProduct>lambdaQuery()
              .eq(MarketProduct::getCategory, category))
          .stream()
          .map(MarketProduct::getCode)
          .toList()
          .isEmpty() ? List.of() : null;
      if (rows == null) {
        List<String> codes = productMapper.selectList(Wrappers.<MarketProduct>lambdaQuery()
                .eq(MarketProduct::getCategory, category))
            .stream().map(MarketProduct::getCode).toList();
        if (codes.isEmpty()) {
          return List.of();
        }
        query.in(MarketQuote::getProductCode, codes);
      }
    }
    if (StringUtils.hasText(keyword)) {
      query.and(w -> w.like(MarketQuote::getSpecName, keyword)
          .or().like(MarketQuote::getProductName, keyword));
    }
    return quoteMapper.selectList(query).stream().map(this::mapQuote).toList();
  }

  /** 筛选元数据：最新日期、分类、产品、城市、产地。 */
  public MarketMeta meta() {
    List<MarketProduct> products = productMapper.selectList(Wrappers.<MarketProduct>lambdaQuery()
        .eq(MarketProduct::getEnabled, true)
        .orderByAsc(MarketProduct::getSortOrder));
    Set<String> categories = new LinkedHashSet<>();
    for (MarketProduct product : products) {
      categories.add(product.getCategory());
    }
    List<String> cities = distinctCities();
    List<String> origins = distinctOrigins();
    return new MarketMeta(latestDate(), new ArrayList<>(categories), products.stream()
        .map(p -> new ProductItem(p.getCode(), p.getCategory(), p.getName(), p.getSourceUrl(), p.getIsNav()))
        .toList(), cities, origins);
  }

  /** 最新一日的城市列表（数据驱动，只认白名单内的城市；无数据时回退已知城市，避免筛选器为空）。 */
  private List<String> distinctCities() {
    List<String> cities = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
            .eq(MarketQuote::getQuoteDate, latestDate())
            .select(MarketQuote::getCity)
            .isNotNull(MarketQuote::getCity)
            .ne(MarketQuote::getCity, "")
            .last("LIMIT 200"))
        .stream()
        // 只认合法城市：历史脏值（如把产地「中天」写进 city）不进筛选器
        .map(quote -> MarketCities.normalize(quote.getCity()))
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    return cities.isEmpty() ? MarketCities.known() : cities;
  }

  /** 最新一日的产地列表（去重）。 */
  private List<String> distinctOrigins() {
    return quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
            .eq(MarketQuote::getQuoteDate, latestDate())
            .select(MarketQuote::getOrigin)
            .isNotNull(MarketQuote::getOrigin)
            .ne(MarketQuote::getOrigin, "")
            .last("LIMIT 500"))
        .stream()
        .map(MarketQuote::getOrigin)
        .distinct()
        .toList();
  }

  /**
   * Tab1 批次对比：送货单明细（按 名称+规格 聚合）× 锁定网价 → 最新行情价 → 价差/差异率。
   * 业务侧价格 = 锁定销售单价（网价+上浮）的加权平均；市场侧价格按产地优先级取最新行情。
   */
  public BatchCompare compareBatch(String ownerId, String batchId, String city, String origin) {
    BatchDto batch = businessQueryService.getBatch(ownerId, batchId);
    // 城市不传就用本批次行情城市（不再硬编码「杭州」，改由配置决定）
    String compareCity = marketCities.resolve(city);
    String compareOrigin = StringUtils.hasText(origin) ? origin : null;
    LocalDate date = latestDate();

    List<com.aibot.purchase.entity.Document> deliveries = documentMapper.selectList(
        Wrappers.<com.aibot.purchase.entity.Document>lambdaQuery()
            .eq(com.aibot.purchase.entity.Document::getBatchId, batchId)
            .eq(com.aibot.purchase.entity.Document::getDocType, "delivery_note")
            .in(com.aibot.purchase.entity.Document::getStatus, "matched", "archived")
            .orderByAsc(com.aibot.purchase.entity.Document::getCreatedAt));

    List<CompareRow> rows = new ArrayList<>();
    BigDecimal totalBusiness = BigDecimal.ZERO;
    BigDecimal totalMarket = BigDecimal.ZERO;
    int matched = 0;
    BigDecimal diffRateSum = BigDecimal.ZERO;
    int diffRateCount = 0;

    if (!deliveries.isEmpty()) {
      List<String> documentIds = deliveries.stream()
          .map(com.aibot.purchase.entity.Document::getId).toList();
      List<com.aibot.purchase.entity.DocumentItem> items = documentItemMapper.selectList(
          Wrappers.<com.aibot.purchase.entity.DocumentItem>lambdaQuery()
              .in(com.aibot.purchase.entity.DocumentItem::getDocumentId, documentIds)
              .orderByAsc(com.aibot.purchase.entity.DocumentItem::getSortOrder));
      List<com.aibot.purchase.entity.DocumentItemMarketMapping> mappings =
          itemNetMappingMapper.selectList(Wrappers.<com.aibot.purchase.entity.DocumentItemMarketMapping>lambdaQuery()
              .in(com.aibot.purchase.entity.DocumentItemMarketMapping::getDocumentId, documentIds));
      java.util.Map<String, com.aibot.purchase.entity.DocumentItemMarketMapping> byItem = new java.util.HashMap<>();
      for (com.aibot.purchase.entity.DocumentItemMarketMapping mapping : mappings) {
        byItem.put(mapping.getDocumentItemId(), mapping);
      }

      // 按 名称+规格 聚合：数量/金额合计，代表映射取状态优先级最高的一行；
      // 锁定行情价按计价数量加权（每行可能锁到不同明细数量）
      record Group(String name, String spec, String unit,
                   com.aibot.purchase.entity.DocumentItemMarketMapping rep,
                   java.math.BigDecimal quantity, java.math.BigDecimal amount,
                   java.math.BigDecimal quoteWeightedSum, java.math.BigDecimal quoteWeight,
                   LocalDate lockedDate) {}
      java.util.Map<String, Group> groups = new java.util.LinkedHashMap<>();
      for (com.aibot.purchase.entity.DocumentItem item : items) {
        String key = DeliveryNetPriceService.bindingGroupKey(
            batchId, item.getMaterialName(), item.getSpec());
        com.aibot.purchase.entity.DocumentItemMarketMapping mapping = byItem.get(item.getId());
        java.math.BigDecimal qty = item.getPricingQuantity() == null
            ? java.math.BigDecimal.ZERO : item.getPricingQuantity();
        java.math.BigDecimal amount = mapping != null
            && !"unmatched".equals(mapping.getPriceBasis())
            && mapping.getAmount() != null ? mapping.getAmount() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal quotePrice = mapping != null && mapping.getQuotePrice() != null
            && qty.signum() > 0 ? mapping.getQuotePrice() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal quoteWeight = mapping != null && mapping.getQuotePrice() != null
            ? qty : java.math.BigDecimal.ZERO;
        String unit = StringUtils.hasText(item.getPricingUnit()) ? item.getPricingUnit() : "";
        LocalDate lockedDate = mapping != null && mapping.getQuoteDate() != null
            ? mapping.getQuoteDate() : null;
        Group existing = groups.get(key);
        if (existing == null) {
          groups.put(key, new Group(item.getMaterialName(), item.getSpec(), unit, mapping,
              qty, amount, quotePrice.multiply(qty), quoteWeight, lockedDate));
          continue;
        }
        int newRank = mapping == null ? 0 : statusRank(mapping.getStatus());
        int oldRank = statusRank(existing.rep() == null ? null : existing.rep().getStatus());
        groups.put(key, new Group(
            existing.name(), existing.spec(),
            StringUtils.hasText(existing.unit()) ? existing.unit() : unit,
            newRank > oldRank ? mapping : existing.rep(),
            existing.quantity().add(qty),
            existing.amount().add(amount),
            existing.quoteWeightedSum().add(quotePrice.multiply(qty)),
            existing.quoteWeight().add(quoteWeight),
            existing.lockedDate() != null ? existing.lockedDate() : lockedDate));
      }

      for (java.util.Map.Entry<String, Group> entry : groups.entrySet()) {
        Group group = entry.getValue();
        com.aibot.purchase.entity.DocumentItemMarketMapping rep = group.rep();
        String matchStatus = rep == null ? "unmatched"
            : (rep.getStatus() == null ? "unmatched" : rep.getStatus());
        String matchMethod = rep == null ? null : rep.getMatchMethod();
        java.math.BigDecimal confidence = rep == null ? null : rep.getConfidence();
        String productCode = rep == null ? null : rep.getProductCode();
        String marketSpecName = rep == null ? null : rep.getSpecName();
        String productName = null;
        String matchOrigin = compareOrigin;
        String matchCity = compareCity;
        java.math.BigDecimal marketPrice = null;
        LocalDate marketDate = null;

        if (rep != null && java.util.List.of("auto", "manual").contains(matchStatus)
            && StringUtils.hasText(productCode) && StringUtils.hasText(marketSpecName)) {
          // 防御：映射缺 productCode 时按 spec_name 反查
          MarketQuote ref = quoteMapper.selectOne(Wrappers.<MarketQuote>lambdaQuery()
              .eq(MarketQuote::getSpecName, marketSpecName)
              .isNotNull(MarketQuote::getProductCode)
              .last("LIMIT 1"));
          if (!StringUtils.hasText(productCode) && ref != null) {
            productCode = ref.getProductCode();
          }
          // 指定产地有价 → 精确取价；无价 → 城市全产地均价 → 全国均价回退
          MarketQuote quote = StringUtils.hasText(matchOrigin)
              ? findQuote(date, productCode, marketSpecName, matchCity, matchOrigin)
              : null;
          if (quote != null) {
            marketPrice = quote.getPrice();
            marketDate = quote.getQuoteDate();
            matchOrigin = quote.getOrigin();
          } else {
            marketPrice = averagePrice(date, productCode, marketSpecName, matchCity);
            marketDate = date;
            if (marketPrice == null) {
              marketPrice = averagePrice(date, productCode, marketSpecName, null);
              marketDate = date;
            }
          }
          MarketProduct product = productMapper.selectOne(Wrappers.<MarketProduct>lambdaQuery()
              .eq(MarketProduct::getCode, productCode).last("LIMIT 1"));
          productName = product == null ? null : product.getName();
        }

        // 业务侧：锁定销售金额合计 / 计价数量合计（加权平均销售单价）
        java.math.BigDecimal businessPrice = group.amount();
        if (businessPrice.signum() > 0 && group.quantity().signum() > 0) {
          businessPrice = businessPrice.divide(group.quantity(), 2, RoundingMode.HALF_UP);
        } else if (rep != null && rep.getSalesUnitPrice() != null) {
          businessPrice = rep.getSalesUnitPrice();
        } else {
          businessPrice = java.math.BigDecimal.ZERO;
        }

        java.math.BigDecimal diff = null;
        java.math.BigDecimal diffRate = null;
        if (marketPrice != null) {
          diff = businessPrice.subtract(marketPrice);
          if (marketPrice.compareTo(java.math.BigDecimal.ZERO) > 0) {
            diffRate = diff.multiply(java.math.BigDecimal.valueOf(100))
                .divide(marketPrice, 2, RoundingMode.HALF_UP);
          }
          matched++;
          totalBusiness = totalBusiness.add(group.amount());
          totalMarket = totalMarket.add(marketPrice.multiply(nz(group.quantity())));
          if (diffRate != null) {
            diffRateSum = diffRateSum.add(diffRate);
            diffRateCount++;
          }
        }

        rows.add(new CompareRow(
            entry.getKey(),
            group.name(),
            group.spec(),
            group.unit(),
            group.quantity().signum() == 0 ? null : group.quantity(),
            businessPrice,
            matchStatus,
            matchMethod,
            confidence,
            productCode,
            productName,
            marketSpecName,
            matchOrigin,
            matchCity,
            marketDate,
            marketPrice,
            diff,
            diffRate,
            group.lockedDate(),
            group.quoteWeight().signum() > 0
                ? group.quoteWeightedSum().divide(group.quoteWeight(), 2, RoundingMode.HALF_UP)
                : (rep != null ? rep.getQuotePrice() : null),
            rep != null ? rep.getOriginLabel() : null
        ));
      }
    }

    BigDecimal totalDiff = totalBusiness.subtract(totalMarket);
    BigDecimal avgRate = diffRateCount > 0
        ? diffRateSum.divide(BigDecimal.valueOf(diffRateCount), 2, RoundingMode.HALF_UP)
        : null;

    return new BatchCompare(
        batchId,
        batch.name(),
        rows.size(),
        matched,
        rows.size() - matched,
        totalBusiness,
        totalMarket,
        totalDiff,
        avgRate,
        rows
    );
  }

  private static int statusRank(String status) {
    return switch (status == null ? "" : status) {
      case "manual" -> 4;
      case "auto" -> 3;
      case "ai_pending" -> 2;
      case "unmatched" -> 1;
      default -> 0;
    };
  }

  private MarketQuote findQuote(LocalDate date, String productCode, String specName, String city,
                                String origin) {
    return quoteMapper.selectOne(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, date)
        .eq(MarketQuote::getProductCode, productCode)
        .eq(MarketQuote::getSpecName, specName)
        .eq(MarketQuote::getCity, city)
        .eq(StringUtils.hasText(origin), MarketQuote::getOrigin, origin)
        .last("LIMIT 1"));
  }

  /** 该产品+规格当日有报价行均价（city 为空则跨城市）。多产地场景会平均产地价差。 */
  private BigDecimal averagePrice(LocalDate date, String productCode, String specName, String city) {
    List<MarketQuote> rows = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .eq(MarketQuote::getQuoteDate, date)
        .eq(MarketQuote::getProductCode, productCode)
        .eq(MarketQuote::getSpecName, specName)
        .eq(StringUtils.hasText(city), MarketQuote::getCity, city)
        .isNotNull(MarketQuote::getPrice));
    if (rows.isEmpty()) {
      return null;
    }
    BigDecimal sum = rows.stream()
        .map(MarketQuote::getPrice)
        .filter(java.util.Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.divide(BigDecimal.valueOf(rows.size()), 0, RoundingMode.HALF_UP);
  }

  private QuoteRow mapQuote(MarketQuote q) {
    return new QuoteRow(
        q.getId(), q.getQuoteDate(), q.getProductCode(), q.getSource(), q.getProductName(),
        q.getSpecName(), q.getSpec(), q.getGrade(), q.getOrigin(), q.getCity(),
        q.getPrice(), q.getPriceChange(), q.getDetailUrl()
    );
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static void requireText(String value, String message) {
    if (!StringUtils.hasText(value)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, message);
    }
  }
}
