package com.aibot.purchase.service.market;

import com.aibot.purchase.entity.BatchMaterial;
import com.aibot.purchase.entity.Document;
import com.aibot.purchase.entity.DocumentItem;
import com.aibot.purchase.entity.DocumentItemMarketMapping;
import com.aibot.purchase.entity.MarketProduct;
import com.aibot.purchase.entity.MarketQuote;
import com.aibot.purchase.mapper.BatchMaterialMapper;
import com.aibot.purchase.mapper.DocumentItemMapper;
import com.aibot.purchase.mapper.DocumentItemMarketMappingMapper;
import com.aibot.purchase.mapper.DocumentMapper;
import com.aibot.purchase.mapper.MarketProductMapper;
import com.aibot.purchase.mapper.MarketQuoteMapper;
import com.aibot.purchase.service.BatchHintService;
import com.aibot.purchase.service.LedgerService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 网价锁定服务。
 *
 * <p>送货单（销售侧）：确认时同步执行：明细行规则匹配行情 → 取价 → 计价数量判定
 * （重量优先，统一换算成吨）→ 金额锁定落库。取价基数按口径分两种（2026-10-28 起）：
 * <b>上浮口径</b>销售价 = 网价 + 上浮；<b>毛利率口径</b>销售价 = 采购吨价 ÷ (1−毛利率)，
 * 采购吨价来自同批次采购单（{@link PurchaseCostResolver}），网价只作展示对比。
 * 性能约束：当日行情按产品码一次性载入内存，
 * 同「名称+规格」的行去重只匹配一次，确认接口耗时与明细行数无关。
 *
 * <p>规则层未命中的行标记 ai_pending，确认返回后异步 AI 兜底，成功后回填并重算台账。
 * 台账销售金额与对账单导出永远读锁定值，不再现场匹配。
 *
 * <p>采购单（采购侧，{@link #lockSupplyPrices}）：确认/修正时按单据日期锁定行情价，
 * 仅作批次采购毛利率估算依据——定价策略来自批次默认口径，销售单价/金额不落库
 * （台账读时按当前口径现算）；不进行情中心、不 AI 兜底、不回写采购单明细。
 */
@Service
public class DeliveryNetPriceService {
  private static final Logger log = LoggerFactory.getLogger(DeliveryNetPriceService.class);

  private final DocumentItemMapper itemMapper;
  private final DocumentItemMarketMappingMapper mappingMapper;
  private final DocumentMapper documentMapper;
  private final MarketProductMapper productMapper;
  private final MarketQuoteMapper quoteMapper;
  private final BatchMaterialMapper batchMaterialMapper;
  private final MaterialMarketMatchService matchService;
  private final LedgerService ledgerService;
  private final com.aibot.purchase.mapper.BatchMapper batchMapper;
  private final BatchHintService batchHintService;
  /** 毛利率口径的采购价来源（送货单明细 → 同批次采购单吨价）。 */
  private final PurchaseCostResolver costResolver;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
  private final com.aibot.purchase.service.FreightService freightService;
  /** 行情城市维度：取价先按本批次行情城市过滤，绝不跨城市平均（2026-11-12）。 */
  private final MarketCities marketCities;

  /** 默认销售上浮（全局配置，默认 30；仅作为无定价指令时的缺省，只影响之后确认的送货单）。 */
  @Value("${purchase.sales-uplift:30}")
  private BigDecimal salesUplift;

  /**
   * 文档级销售定价策略（送货单确认时锁定）：
   * uplift=网价+上浮（默认，来自补充说明或全局配置）/ margin=毛利率（成本 ÷ (1−毛利率)，
   * 送货单的成本取同批次采购价，见 {@link PricingPolicy#salesUnitPriceFromCost}）。
   * 取整规则按口径区分（2026-11-05 改）：上浮口径直接相加、不取整；毛利率口径个位四舍五入取整十。
   */
  public record PricingPolicy(String mode, BigDecimal upliftAmount, BigDecimal marginRate) {

    /**
     * 采购单侧定价策略：来自批次默认口径（delivery_note scope 定价键）。
     * 无口径或口径不完整返回 null——采购单只锁网价，不产生销售价（不用全局上浮兜底）。
     */
    public static PricingPolicy fromBatchHints(java.util.Map<String, Object> scope) {
      if (scope == null || scope.isEmpty()) {
        return null;
      }
      String mode = scope.get(com.aibot.purchase.service.BatchHintService.KEY_PRICING_MODE) == null
          ? null : String.valueOf(scope.get(com.aibot.purchase.service.BatchHintService.KEY_PRICING_MODE)).trim();
      if ("margin".equals(mode)) {
        BigDecimal rate = com.aibot.purchase.service.BatchHintService.decimal(
            scope.get(com.aibot.purchase.service.BatchHintService.KEY_MARGIN_RATE));
        if (rate != null && rate.signum() > 0 && rate.compareTo(BigDecimal.valueOf(100)) < 0) {
          return new PricingPolicy("margin", null, rate);
        }
      }
      if ("uplift".equals(mode)) {
        BigDecimal uplift = com.aibot.purchase.service.BatchHintService.decimal(
            scope.get(com.aibot.purchase.service.BatchHintService.KEY_UPLIFT_AMOUNT));
        if (uplift != null && uplift.signum() > 0) {
          return new PricingPolicy("uplift", uplift, null);
        }
      }
      return null;
    }

    static PricingPolicy of(Document doc, BigDecimal globalUplift) {
      if (doc == null) {
        return new PricingPolicy("uplift", globalUplift, null);
      }
      if ("margin".equals(doc.getPricingMode())) {
        return new PricingPolicy("margin", null, doc.getMarginRate() != null
            ? doc.getMarginRate() : new BigDecimal("2.5"));
      }
      if ("uplift".equals(doc.getPricingMode()) && doc.getUpliftAmount() != null) {
        return new PricingPolicy("uplift", doc.getUpliftAmount(), null);
      }
      return new PricingPolicy("uplift", globalUplift, null);
    }

    /**
     * 销售单价（元/吨）：上浮口径 = 网价 + 上浮，直接相加（不取整，2026-11-05 改）；
     * 毛利率口径 = 网价 ÷ (1−毛利率)，个位四舍五入取整十。
     *
     * <p>⚠️ 送货单的毛利率口径必须走 {@link #salesUnitPriceFromCost}（成本取采购价），
     * 这里的 margin 分支是历史口径残留，只为兼容保留。
     */
    public BigDecimal salesUnitPrice(BigDecimal netPrice) {
      if ("margin".equals(mode)) {
        return roundToTen(netPrice.multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(100).subtract(marginRate), 6, RoundingMode.HALF_UP));
      }
      return netPrice.add(upliftAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 采购单销售单价（元/计价单位，2026-10-27 定）：毛利率口径按<b>采购成本价</b>倒推
     * （成本 ÷ (1−毛利率)），上浮口径为网价 + 上浮。
     *
     * <p>取整规则（2026-11-05 改）：毛利率口径个位四舍五入取整十；上浮口径直接相加、不取整，
     * 按实际计算结果（保留 2 位）落库。
     * 缺基数（无采购吨价 / 上浮口径无网价）返回 null —— 该行不产生销售价。
     */
    public BigDecimal salesUnitPriceFromCost(BigDecimal costPrice, BigDecimal netPrice) {
      if ("margin".equals(mode)) {
        if (costPrice == null || costPrice.signum() <= 0) {
          return null;
        }
        return roundToTen(costPrice.multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(100).subtract(marginRate), 6, RoundingMode.HALF_UP));
      }
      if (netPrice == null || netPrice.signum() <= 0) {
        return null;
      }
      return netPrice.add(upliftAmount).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundToTen(BigDecimal price) {
      return price.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP)
          .multiply(BigDecimal.TEN)
          .setScale(2, RoundingMode.HALF_UP);
    }

    /** 上浮列存值：uplift 模式=上浮金额；margin 模式=销售价-网价差额。 */
    BigDecimal upliftOf(BigDecimal netPrice, BigDecimal salesUnitPrice) {
      return "margin".equals(mode) ? salesUnitPrice.subtract(netPrice) : upliftAmount;
    }
  }

  public DeliveryNetPriceService(
      DocumentItemMapper itemMapper,
      DocumentItemMarketMappingMapper mappingMapper,
      DocumentMapper documentMapper,
      MarketProductMapper productMapper,
      MarketQuoteMapper quoteMapper,
      BatchMaterialMapper batchMaterialMapper,
      MaterialMarketMatchService matchService,
      LedgerService ledgerService,
      com.aibot.purchase.mapper.BatchMapper batchMapper,
      BatchHintService batchHintService,
      PurchaseCostResolver costResolver,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper,
      com.aibot.purchase.service.FreightService freightService,
      MarketCities marketCities
  ) {
    this.itemMapper = itemMapper;
    this.mappingMapper = mappingMapper;
    this.documentMapper = documentMapper;
    this.productMapper = productMapper;
    this.quoteMapper = quoteMapper;
    this.batchMaterialMapper = batchMaterialMapper;
    this.matchService = matchService;
    this.ledgerService = ledgerService;
    this.batchMapper = batchMapper;
    this.batchHintService = batchHintService;
    this.costResolver = costResolver;
    this.objectMapper = objectMapper;
    this.freightService = freightService;
    this.marketCities = marketCities;
  }

  /** 锁定结果摘要：matched=按口径定价行数 / fallback=回退单据金额行数 / pending=待 AI 兜底行数 /
   *  costPending=毛利率口径待采购价行数（不触发 AI 兜底，采购单到位后自动补算）。 */
  public record LockResult(int matched, int fallback, int pending, int costPending) {
    public boolean hasPending() {
      return pending > 0;
    }
  }

  /**
   * 清理「明细行已被删掉」的价格锁定记录（2026-11-08）。
   *
   * <p>编辑单据时明细是整表删了重插，行 id 全新，而锁定表按行 id 存 → 旧记录变成孤儿；
   * 台账按 document_id 汇总锁定金额，孤儿会被重复计入，编辑一次销售金额就多叠一整套
   * （实测：改一次行销售价，销售额 164,624.86 → 490,674.58）。每次重锁前先清理。
   */
  public int purgeOrphanMappings(String documentId) {
    if (!StringUtils.hasText(documentId)) {
      return 0;
    }
    List<String> itemIds = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
            .eq(DocumentItem::getDocumentId, documentId))
        .stream().map(DocumentItem::getId).toList();
    if (itemIds.isEmpty()) {
      return 0;
    }
    return mappingMapper.delete(Wrappers.<DocumentItemMarketMapping>lambdaQuery()
        .eq(DocumentItemMarketMapping::getDocumentId, documentId)
        .notIn(DocumentItemMarketMapping::getDocumentItemId, itemIds));
  }

  /**
   * 送货单确认时同步锁定全部明细行的网价与金额（在确认事务内执行）。
   */
  public LockResult lockPrices(Document doc) {
    List<DocumentItem> items = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, doc.getId())
        .orderByAsc(DocumentItem::getSortOrder));
    // 明细行被整表重插后旧锁定记录会变孤儿，先清理再锁，避免按 document_id 汇总时重复计入
    purgeOrphanMappings(doc.getId());
    if (items.isEmpty()) {
      return new LockResult(0, 0, 0, 0);
    }
    QuoteDay day = resolveQuoteDay(doc.getDocDate());
    LocalDate quoteTarget = doc.getDocDate() != null ? doc.getDocDate() : LocalDate.now();
    PricingPolicy policy = PricingPolicy.of(doc, salesUplift);
    // 毛利率口径以采购价为成本：一次载入本批次采购明细并按行匹配吨价
    // （行情只在 upsell 口径参与定价；margin 口径下网价仅作展示对比）
    boolean costMode = "margin".equals(policy.mode());
    Map<String, PurchaseCostResolver.Cost> costs = costMode
        ? costResolver.resolve(doc.getBatchId(), items) : Map.of();

    // 分组去重：材料层已归并且绑定了网价的行，按材料共用一次匹配（同材料必然同网价）；
    // 未归并/未绑定的行走旧的「名称+规格+产地」分组。行情行按产品码一次性载入内存共享
    Map<String, BatchMaterial> materials = loadMaterials(doc.getBatchId());
    Map<String, List<MarketQuote>> quotesByProduct = new HashMap<>();
    Map<String, GroupMatch> groupMatches = new HashMap<>();
    for (DocumentItem item : items) {
      groupMatches.computeIfAbsent(matchKey(item, materials),
          k -> matchGroup(item, quoteTarget, day, quotesByProduct, bindingOf(item, materials)));
    }

    int matched = 0;
    int fallback = 0;
    int pending = 0;
    int costPending = 0;
    for (DocumentItem item : items) {
      DocumentItemMarketMapping mapping = upsertMapping(item, doc);
      PurchaseCostResolver.Cost cost = costs.get(item.getId());
      // 人工改价行（matchMethod=manual_price）：锁定值由人工输入，重锁/AI 兜底一律不覆盖
      if ("manual_price".equals(mapping.getMatchMethod())) {
        saveCostColumns(mapping, cost);
        continue;
      }
      PricingQty pq = resolvePricingQuantity(item);
      mapping.setPricingMode(policy.mode());

      // 人工锁定（行情中心绑定）：按绑定规格精确取价，不被自动匹配覆盖
      if ("manual".equals(mapping.getStatus())) {
        LockedOutcome manual = resolveManualLocked(
            mapping, item, pq, quoteTarget, day, quotesByProduct, policy, cost);
        if ("quote".equals(manual.basis()) || "cost".equals(manual.basis())) {
          mapping.setQuotePrice(manual.quotePrice());
          mapping.setUplift(manual.quotePrice() != null && manual.salesUnitPrice() != null
              ? policy.upliftOf(manual.quotePrice(), manual.salesUnitPrice()) : null);
          mapping.setSalesUnitPrice(manual.salesUnitPrice());
          mapping.setAmount(manual.amount());
          mapping.setPriceBasis(manual.basis());
          mapping.setQuoteDate(manual.quote() != null && manual.quote().getQuoteDate() != null
              ? manual.quote().getQuoteDate() : (day != null ? day.date() : null));
          mapping.setQuoteId(manual.quote() != null ? manual.quote().getId() : null);
          mapping.setQuoteSource(manual.quote() != null ? manual.quote().getSource() : null);
          if (pq != null) {
            mapping.setPricingQuantity(pq.quantity());
            mapping.setPricingUnit(pq.unit());
          }
          if (manual.quotePrice() != null) {
            item.setNetPrice(manual.quotePrice());
          }
          if (pq != null) {
            item.setPricingQuantity(pq.quantity());
            item.setPricingUnit(pq.unit());
          }
          itemMapper.updateById(item);
          matched++;
        } else if ("cost_pending".equals(manual.basis())) {
          // 绑定只解决网价，采购价没有就还是空着（采购单到位后重锁补算）
          mapping.setQuotePrice(manual.quotePrice());
          mapping.setUplift(null);
          mapping.setSalesUnitPrice(null);
          mapping.setAmount(null);
          mapping.setPriceBasis("cost_pending");
          if (pq != null) {
            mapping.setPricingQuantity(pq.quantity());
            mapping.setPricingUnit(pq.unit());
          }
          costPending++;
        } else if ("fallback_amount".equals(manual.basis())) {
          mapping.setQuotePrice(null);
          mapping.setUplift(null);
          mapping.setSalesUnitPrice(null);
          mapping.setAmount(manual.amount());
          mapping.setPriceBasis("fallback_amount");
          if (pq != null) {
            mapping.setPricingQuantity(pq.quantity());
            mapping.setPricingUnit(pq.unit());
          }
          fallback++;
        } else {
          mapping.setQuotePrice(null);
          mapping.setUplift(null);
          mapping.setSalesUnitPrice(null);
          mapping.setAmount(null);
          mapping.setPriceBasis("unmatched");
        }
        mapping.setMatchMethod("manual");
        mapping.setUpdatedAt(LocalDateTime.now());
        persistMapping(mapping, cost);
        continue;
      }

      GroupMatch gm = groupMatches.get(matchKey(item, materials));

      BigDecimal quotePrice = null;
      BigDecimal salesUnitPrice = null;
      BigDecimal amount = null;
      String basis;
      String status;
      String method = "unmatched";

      // 材料级人工销售价（batch_materials.manual_sales_price）：非空时优先于口径推算，
      // 承接原行级 manual_price 的「人工定价」语义（2026-11-04 迁移）。status 保持 auto，
      // 重锁幂等重算同一个值，不会误入行级人工锁定分支
      BatchMaterial boundMaterial = item.getMaterialId() == null ? null : materials.get(item.getMaterialId());
      if (boundMaterial != null && boundMaterial.getManualSalesPrice() != null
          && boundMaterial.getManualSalesPrice().signum() > 0) {
        quotePrice = gm != null && gm.quote() != null ? gm.avgPrice() : null;
        method = "manual";
        if (pq != null && "吨".equals(pq.unit())) {
          salesUnitPrice = boundMaterial.getManualSalesPrice();
          amount = salesUnitPrice.multiply(pq.quantity()).setScale(2, RoundingMode.HALF_UP);
          basis = "manual";
          status = "auto";
        } else if (nz(item.getAmount()).signum() > 0) {
          amount = item.getAmount();
          basis = "fallback_amount";
          status = "auto";
        } else {
          basis = "unmatched";
          status = "unmatched";
        }
      } else if (item.getSalesUnitPrice() != null && item.getSalesUnitPrice().signum() > 0
          && pq != null && "吨".equals(pq.unit())) {
        // 行级人工销售价（document_items.sales_unit_price，数据中心/编辑卡片里直接填的价）：
        // 优先级低于材料级人工价、高于口径推算（2026-11-08）。status 保持 auto，
        // 重锁按行值幂等重算同一个数，清空行值即自动恢复口径——不锁死，避免之后改不回来。
        quotePrice = gm != null && gm.quote() != null ? gm.avgPrice() : null;
        method = "manual";
        salesUnitPrice = item.getSalesUnitPrice();
        amount = salesUnitPrice.multiply(pq.quantity()).setScale(2, RoundingMode.HALF_UP);
        basis = "manual";
        status = "auto";
      } else if (costMode) {
        // 毛利率口径：销售价 = 采购吨价 ÷ (1−毛利率)，网价不参与定价
        quotePrice = gm != null && gm.quote() != null ? gm.avgPrice() : null;
        if (pq == null) {
          // 没有计价数量，任何口径都算不出金额
          basis = "unmatched";
          status = "unmatched";
        } else if (cost == null || !cost.priced()) {
          // 待采购价：金额留空、不计入销售金额，采购单到位后由重锁补算
          basis = "cost_pending";
          status = "auto";
        } else if ("吨".equals(pq.unit())) {
          salesUnitPrice = policy.salesUnitPriceFromCost(cost.unitPrice(), null);
          amount = salesUnitPrice.multiply(pq.quantity()).setScale(2, RoundingMode.HALF_UP);
          basis = "cost";
          status = "auto";
        } else if (nz(item.getAmount()).signum() > 0) {
          // 采购价是元/吨，数量型（件/个/根）无法折吨计价 → 回退单据金额
          amount = item.getAmount();
          basis = "fallback_amount";
          status = "auto";
        } else {
          basis = "unmatched";
          status = "unmatched";
        }
      } else if (gm != null && gm.quote() != null && pq != null) {
        quotePrice = gm.avgPrice();
        salesUnitPrice = policy.salesUnitPrice(quotePrice);
        method = gm.method();
        if ("吨".equals(pq.unit())) {
          amount = salesUnitPrice.multiply(pq.quantity()).setScale(2, RoundingMode.HALF_UP);
          basis = "quote";
          status = "auto";
        } else {
          // 行情是元/吨，数量型（件/个/根）无法用网价计价 → 回退单据金额
          if (nz(item.getAmount()).signum() > 0) {
            amount = item.getAmount();
            basis = "fallback_amount";
            status = "auto";
          } else {
            basis = "unmatched";
            status = "unmatched";
            salesUnitPrice = null;
            quotePrice = null;
          }
        }
      } else {
        // 规则未命中：金额暂计 0，AI 异步兜底后重算台账
        basis = "unmatched";
        status = "ai_pending";
      }

      mapping.setProductCode(gm != null && gm.quote() != null ? gm.quote().getProductCode() : null);
      mapping.setSpecName(gm != null && gm.quote() != null ? gm.quote().getSpecName() : null);
      mapping.setQuoteId(gm != null && gm.quote() != null ? gm.quote().getId() : null);
      mapping.setQuoteDate(gm != null && gm.quote() != null && gm.quote().getQuoteDate() != null
          ? gm.quote().getQuoteDate() : (day != null ? day.date() : null));
      mapping.setQuoteSource(gm != null && gm.quote() != null ? gm.quote().getSource() : null);
      mapping.setOriginLabel(gm != null ? gm.originLabel() : null);
      mapping.setQuotePrice(quotePrice);
      mapping.setUplift(quotePrice != null && salesUnitPrice != null
          ? policy.upliftOf(quotePrice, salesUnitPrice) : null);
      mapping.setSalesUnitPrice(salesUnitPrice);
      if (pq != null) {
        mapping.setPricingQuantity(pq.quantity());
        mapping.setPricingUnit(pq.unit());
      }
      mapping.setAmount(amount);
      mapping.setPriceBasis(basis);
      mapping.setMatchMethod(method);
      mapping.setStatus(status);
      mapping.setUpdatedAt(LocalDateTime.now());
      persistMapping(mapping, cost);

      if (basis.equals("quote") || basis.equals("cost") || basis.equals("manual")) {
        if (quotePrice != null) {
          item.setNetPrice(quotePrice);
        }
        item.setPricingQuantity(pq.quantity());
        item.setPricingUnit(pq.unit());
        itemMapper.updateById(item);
        matched++;
      } else if (basis.equals("fallback_amount")) {
        if (pq != null) {
          item.setPricingQuantity(pq.quantity());
          item.setPricingUnit(pq.unit());
          itemMapper.updateById(item);
        }
        fallback++;
      } else if (basis.equals("cost_pending")) {
        if (pq != null) {
          item.setPricingQuantity(pq.quantity());
          item.setPricingUnit(pq.unit());
          itemMapper.updateById(item);
        }
        costPending++;
      } else if (!costMode) {
        // 毛利率口径未定价的行不是「等 AI 匹配行情」，是「等采购单」，不能触发 AI 兜底
        pending++;
      }
    }
    log.info("送货单 {} 销售价锁定完成：按口径定价 {} 行、回退 {} 行、待 AI {} 行、待采购价 {} 行",
        doc.getId(), matched, fallback, pending, costPending);
    return new LockResult(matched, fallback, pending, costPending);
  }

  // ---------------------------------------------------------------------------
  // 采购单网价锁定（采购侧毛利率估算依据，不进行情中心）
  // ---------------------------------------------------------------------------

  /**
   * 采购单确认/修正时按单据日期锁定明细行行情价（与送货单同机制：
   * 规则匹配 + 产地优先 + 取价日回退 + 计价数量判定）。
   *
   * <p>与送货单锁定的差异：① 定价策略来自批次默认口径，无口径只锁网价不产生销售价；
   * ② 销售单价/金额不落库，台账读时按当前口径现算（改口径即时生效）；
   * ③ 不回写 document_items；④ 未命中行不做 AI 兜底。幂等：重复确认/修正按行覆盖重锁。
   */
  public LockResult lockSupplyPrices(Document doc) {
    if (!"supply_order".equals(doc.getDocType())) {
      return new LockResult(0, 0, 0, 0);
    }
    List<DocumentItem> items = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, doc.getId())
        .orderByAsc(DocumentItem::getSortOrder));
    if (items.isEmpty()) {
      return new LockResult(0, 0, 0, 0);
    }
    QuoteDay day = resolveQuoteDay(doc.getDocDate());
    LocalDate quoteTarget = doc.getDocDate() != null ? doc.getDocDate() : LocalDate.now();
    PricingPolicy policy = PricingPolicy.fromBatchHints(
        batchHintService.deliveryNoteHints(batchMapper.selectById(doc.getBatchId())));

    // 分组去重 + 当日行情按产品码载入内存，与送货单锁定同机制（材料绑定优先）
    Map<String, BatchMaterial> materials = loadMaterials(doc.getBatchId());
    Map<String, List<MarketQuote>> quotesByProduct = new HashMap<>();
    Map<String, GroupMatch> groupMatches = new HashMap<>();
    for (DocumentItem item : items) {
      groupMatches.computeIfAbsent(matchKey(item, materials),
          k -> matchGroup(item, quoteTarget, day, quotesByProduct, bindingOf(item, materials)));
    }

    int matched = 0;
    for (DocumentItem item : items) {
      DocumentItemMarketMapping mapping = upsertMapping(item, doc);
      GroupMatch gm = groupMatches.get(matchKey(item, materials));
      PricingQty pq = resolvePricingQuantity(item);
      boolean hit = gm != null && gm.quote() != null && pq != null && "吨".equals(pq.unit());
      if (hit) {
        mapping.setProductCode(gm.quote().getProductCode());
        mapping.setSpecName(gm.quote().getSpecName());
        mapping.setQuoteId(gm.quote().getId());
        mapping.setQuoteDate(gm.quote().getQuoteDate() != null
            ? gm.quote().getQuoteDate() : (day != null ? day.date() : null));
        mapping.setQuoteSource(gm.quote().getSource());
        mapping.setOriginLabel(gm.originLabel());
        mapping.setQuotePrice(gm.avgPrice());
        mapping.setPricingQuantity(pq.quantity());
        mapping.setPricingUnit(pq.unit());
        mapping.setPriceBasis("quote");
        mapping.setMatchMethod(gm.method());
        mapping.setStatus("auto");
        mapping.setUpdatedAt(LocalDateTime.now());
        saveSupplyMapping(mapping, true);
        matched++;
      } else {
        saveSupplyMapping(mapping, false);
      }
    }
    log.info("采购单 {} 行情锁定完成：命中 {} 行 / 共 {} 行（定价口径：{}）",
        doc.getId(), matched, items.size(), policy != null ? policy.mode() : "无");
    return new LockResult(matched, 0, 0, 0);
  }

  /**
   * 采购单行锁定落库。命中 → 常规 updateById；未命中 → 可空列显式置 NULL
   * （updateById 跳过 null 字段，重锁从命中转未命中时残留值必须清空）。
   */
  private void saveSupplyMapping(DocumentItemMarketMapping mapping, boolean hit) {
    if (!StringUtils.hasText(mapping.getId())) {
      if (hit) {
        mappingMapper.insert(mapping);
      }
      return;
    }
    if (hit) {
      mappingMapper.updateById(mapping);
      return;
    }
    mappingMapper.update(null, Wrappers.<DocumentItemMarketMapping>lambdaUpdate()
        .eq(DocumentItemMarketMapping::getId, mapping.getId())
        .set(DocumentItemMarketMapping::getProductCode, null)
        .set(DocumentItemMarketMapping::getSpecName, null)
        .set(DocumentItemMarketMapping::getQuoteId, null)
        .set(DocumentItemMarketMapping::getQuoteDate, null)
        .set(DocumentItemMarketMapping::getQuoteSource, null)
        .set(DocumentItemMarketMapping::getOriginLabel, null)
        .set(DocumentItemMarketMapping::getQuotePrice, null)
        .set(DocumentItemMarketMapping::getPricingQuantity, null)
        .set(DocumentItemMarketMapping::getPricingUnit, null)
        .set(DocumentItemMarketMapping::getPriceBasis, "unmatched")
        .set(DocumentItemMarketMapping::getMatchMethod, "unmatched")
        .set(DocumentItemMarketMapping::getStatus, "unmatched")
        .set(DocumentItemMarketMapping::getUpdatedAt, LocalDateTime.now()));
  }

  /**
   * AI 兜底（确认事务提交后异步执行）：对 ai_pending 行跑 AI 匹配，
   * 成功后回填锁定值并按前后差额重算台账销售金额。幂等：已人工锁定的行不覆盖。
   */
  @Transactional
  public void aiFallback(String documentId) {
    Document doc = ledgerService.getDocumentInternal(documentId);
    if (doc == null || !"delivery_note".equals(doc.getDocType())) {
      return;
    }
    List<DocumentItem> items = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, documentId)
        .orderByAsc(DocumentItem::getSortOrder));
    if (items.isEmpty()) {
      return;
    }
    List<DocumentItemMarketMapping> mappings = mappingMapper.selectList(
        Wrappers.<DocumentItemMarketMapping>lambdaQuery()
            .eq(DocumentItemMarketMapping::getDocumentId, documentId));
    Map<String, DocumentItemMarketMapping> byItem = new HashMap<>();
    for (DocumentItemMarketMapping mapping : mappings) {
      byItem.put(mapping.getDocumentItemId(), mapping);
    }
    BigDecimal beforeTotal = lockedTotal(mappings);

    QuoteDay day = resolveQuoteDay(doc.getDocDate());
    LocalDate quoteTarget = doc.getDocDate() != null ? doc.getDocDate() : LocalDate.now();
    PricingPolicy policy = PricingPolicy.of(doc, salesUplift);
    if ("margin".equals(policy.mode())) {
      // 毛利率口径的销售价按采购价倒推，AI 匹配行情帮不上忙：
      // 直接返回，交给下次重锁（采购单到位 / 重跑批次）按采购价定价。
      log.info("送货单 {} 为毛利率口径，跳过网价 AI 兜底", documentId);
      return;
    }
    Map<String, List<MarketQuote>> quotesByProduct = new HashMap<>();
    boolean changed = false;
    for (DocumentItem item : items) {
      DocumentItemMarketMapping mapping = byItem.get(item.getId());
      if (mapping == null || !"ai_pending".equals(mapping.getStatus())) {
        continue;
      }
      MarketNameNormalizer.Parsed parsed =
          MarketNameNormalizer.parse(item.getMaterialName(), item.getSpec());
      MarketQuote aiQuote = null;
      if (day != null && parsed.productCode() != null) {
        // AI 兜底也只在本城市行情里选，避免把别的市场的规格价带进来
        List<MarketQuote> rows = marketCities.preferCity(
            quotesForProduct(parsed.productCode(), quoteTarget, quotesByProduct));
        aiQuote = matchService.aiMatchQuote(
            item.getMaterialName(), parsed.spec(), parsed.grade(), doc.getBatchId(), rows);
      }
      if (aiQuote == null) {
        // 终态：不无限重试
        mapping.setStatus("unmatched");
        mapping.setUpdatedAt(LocalDateTime.now());
        saveMapping(mapping);
        changed = true;
        continue;
      }
      PricingQty pq = resolvePricingQuantity(item);
      if (pq != null && "吨".equals(pq.unit())) {
        BigDecimal salesUnitPrice = policy.salesUnitPrice(aiQuote.getPrice());
        mapping.setQuotePrice(aiQuote.getPrice());
        mapping.setUplift(policy.upliftOf(aiQuote.getPrice(), salesUnitPrice));
        mapping.setPricingMode(policy.mode());
        mapping.setSalesUnitPrice(salesUnitPrice);
        mapping.setAmount(salesUnitPrice.multiply(pq.quantity()).setScale(2, RoundingMode.HALF_UP));
        mapping.setPriceBasis("quote");
        item.setNetPrice(aiQuote.getPrice());
        item.setPricingQuantity(pq.quantity());
        item.setPricingUnit(pq.unit());
        itemMapper.updateById(item);
      } else if (nz(item.getAmount()).signum() > 0) {
        mapping.setAmount(item.getAmount());
        mapping.setPriceBasis("fallback_amount");
      } else {
        mapping.setPriceBasis("unmatched");
        mapping.setAmount(null);
      }
      mapping.setProductCode(aiQuote.getProductCode());
      mapping.setSpecName(aiQuote.getSpecName());
      mapping.setQuoteId(aiQuote.getId());
      mapping.setQuoteDate(aiQuote.getQuoteDate() != null
          ? aiQuote.getQuoteDate() : (day != null ? day.date() : null));
      mapping.setQuoteSource(aiQuote.getSource());
      mapping.setOriginLabel(null);
      mapping.setMatchMethod("ai");
      mapping.setConfidence(new BigDecimal("80"));
      mapping.setStatus("auto");
      mapping.setUpdatedAt(LocalDateTime.now());
      saveMapping(mapping);
      changed = true;
    }

    if (changed) {
      BigDecimal afterTotal = lockedTotal(mappingMapper.selectList(
          Wrappers.<DocumentItemMarketMapping>lambdaQuery()
              .eq(DocumentItemMarketMapping::getDocumentId, documentId)));
      ledgerService.adjustDeliverySales(doc, beforeTotal, afterTotal);
    }
  }

  // ---------------------------------------------------------------------------
  // 行情中心（送货单载体）
  // ---------------------------------------------------------------------------

  /** 绑定组标识编码：batchId::材料名::规格（行情中心行 ↔ 明细行的映射键）。 */
  public static String bindingGroupKey(String batchId, String name, String spec) {
    return batchId + "::" + (name == null ? "" : name) + "::" + (spec == null ? "" : spec);
  }


  /** 手动重跑批次匹配：全部已确认送货单重锁（人工绑定行保留），按差额重算台账，待 AI 行异步兜底；
   *  同时重锁批次内已确认采购单的行情价（行情后补数据场景；采购毛利率读台账时现算，无需其它处理）。 */
  @Transactional
  public int rerunBatch(String batchId) {
    int deliveryCount = resyncDeliverySales(batchId);
    List<Document> supplies = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "supply_order")
        .in(Document::getStatus, "matched", "archived"));
    for (Document supply : supplies) {
      lockSupplyPrices(supply);
    }
    return deliveryCount;
  }

  /**
   * 只重算送货单：重锁销售价/金额并按差额修正台账（人工改价行保留）。
   *
   * <p>采购侧入口专用——采购单确认/修正/删除、批次口径变更后调用：毛利率口径的销售价基数
   * 是采购价，采购单一变，同批次送货单的销售额就得跟着变，否则台账销售金额会停在旧值。
   */
  @Transactional
  public int resyncDeliverySales(String batchId) {
    return resyncDeliverySales(batchId, false);
  }

  /**
   * 重算送货单（可强制刷新销售运费）。
   *
   * <p>销售运费（客户承担的运费）有两个来源：单据自身填的值、批次口径推算。
   * 送货单创建时 {@code DocumentService.applyFreightHintDefaults} 会把口径落进实体列，
   * 但「先有单据、后配口径」时实体现值为空，必须在这里补——否则台账销售金额
   * 和材料区毛利率都会少一笔运费（2026-10-28 修）。
   *
   * @param forceFreight true=批次口径变更场景：按当前口径覆盖（单据说明自己写了运费的除外）；
   *                     false=采购单变更场景：只给 sales_freight 为空的行补默认值。
   */
  @Transactional
  public int resyncDeliverySales(String batchId, boolean forceFreight) {
    // 按日期+创建顺序固定排序：元/趟口径下「包干价挂在第一张送货单」需要稳定的第一张
    List<Document> deliveries = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "delivery_note")
        .in(Document::getStatus, "matched", "archived")
        .orderByAsc(Document::getDocDate)
        .orderByAsc(Document::getCreatedAt));
    for (Document doc : deliveries) {
      BigDecimal before = ledgerService.deliveryContributedSales(
          doc, itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
              .eq(DocumentItem::getDocumentId, doc.getId())));
      // 先补运费再重锁价格：before 用旧运费、after 用新运费，差额才会正确进台账
      // （2026-11-04 方案 B：采购运费不再写进送货单，运输费用列由 LedgerService.recalcFreight
      //  按批次统一重算；这里只补客户承担的销售运费）
      LockResult result = lockPrices(doc);
      BigDecimal after = ledgerService.deliveryContributedSales(
          doc, itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
              .eq(DocumentItem::getDocumentId, doc.getId())));
      ledgerService.adjustDeliverySales(doc, before, after);
      if (result.hasPending()) {
        afterCommitFallback(doc.getId());
      }
    }
    if (!deliveries.isEmpty()) {
      log.info("批次 {} 送货单销售价重算完成：{} 张", batchId, deliveries.size());
    }
    // 运输费用列批次级统一重算（口径变更/采购单变更都会走到这里）
    ledgerService.recalcFreight(batchId);
    return deliveries.size();
  }

  private Map<String, Object> parseStructured(String json) {
    if (!StringUtils.hasText(json)) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(json,
          new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
          });
    } catch (Exception e) {
      log.warn("structuredData 解析失败，按空对象处理：{}", e.getMessage());
      return new LinkedHashMap<>();
    }
  }

  private String toJson(Map<String, Object> data) {
    try {
      return objectMapper.writeValueAsString(data);
    } catch (Exception e) {
      log.warn("structuredData 序列化失败：{}", e.getMessage());
      return null;
    }
  }

  /** 送货单明细计价吨数合计：计价单位为吨的行才累加；一行都没有返回 null。 */
  private BigDecimal deliveryTons(String documentId) {
    List<DocumentItem> items = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
        .eq(DocumentItem::getDocumentId, documentId)
        .orderByAsc(DocumentItem::getSortOrder));
    BigDecimal total = BigDecimal.ZERO;
    boolean any = false;
    for (DocumentItem item : items) {
      PricingQty pq = resolvePricingQuantity(item);
      if (pq != null && "吨".equals(pq.unit()) && pq.quantity().signum() > 0) {
        total = total.add(pq.quantity());
        any = true;
      }
    }
    return any ? total : null;
  }

  /** 供触发器在事务提交后执行 AI 兜底（rerunBatch 事务内注册）。 */
  private void afterCommitFallback(String documentId) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
              aiFallbackAsync(documentId);
            }
          });
    } else {
      aiFallbackAsync(documentId);
    }
  }

  private void aiFallbackAsync(String documentId) {
    try {
      aiFallback(documentId);
    } catch (Exception ex) {
      log.error("送货单 {} AI 网价兜底异常", documentId, ex);
    }
  }

  // ---------------------------------------------------------------------------
  // 台账销售金额详情（弹框数据 + 人工修正）
  // ---------------------------------------------------------------------------

  /**
   * 台账销售金额明细：批次内已确认送货单 × 明细行展开（含锁定网价/销售单价/计价数量/金额）。
   */
  public List<Map<String, Object>> salesDetail(String userId, String batchId) {
    List<Document> deliveries = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "delivery_note")
        .in(Document::getStatus, "matched", "archived")
        .orderByAsc(Document::getDocDate)
        .orderByAsc(Document::getCreatedAt));
    // 销售运费一律读运费表（口径行 + 单据写明行），与台账销售总金额同源（2026-11-11）
    List<com.aibot.purchase.entity.FreightItem> salesFreightRows =
        freightService.list(batchId, com.aibot.purchase.service.FreightService.SIDE_SALES);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Document doc : deliveries) {
      if (!userId.equals(doc.getUserId())) {
        throw new com.aibot.purchase.common.ApiException(
            org.springframework.http.HttpStatus.NOT_FOUND, "批次不存在或无权访问");
      }
      List<DocumentItem> items = itemMapper.selectList(Wrappers.<DocumentItem>lambdaQuery()
          .eq(DocumentItem::getDocumentId, doc.getId())
          .orderByAsc(DocumentItem::getSortOrder));
      List<DocumentItemMarketMapping> mappings = mappingMapper.selectList(
          Wrappers.<DocumentItemMarketMapping>lambdaQuery()
              .eq(DocumentItemMarketMapping::getDocumentId, doc.getId()));
      Map<String, DocumentItemMarketMapping> byItem = new HashMap<>();
      for (DocumentItemMarketMapping mapping : mappings) {
        byItem.put(mapping.getDocumentItemId(), mapping);
      }
      for (DocumentItem item : items) {
        DocumentItemMarketMapping mapping = byItem.get(item.getId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mappingId", mapping == null ? null : mapping.getId());
        row.put("documentId", doc.getId());
        row.put("docCode", doc.getDocCode());
        row.put("docDate", doc.getDocDate() == null ? null : doc.getDocDate().toString());
        row.put("materialName", item.getMaterialName());
        row.put("spec", item.getSpec());
        row.put("quoteSource", mapping == null ? null : mapping.getQuoteSource());
        row.put("quoteSpecName", mapping == null ? null : mapping.getSpecName());
        row.put("quoteDate", mapping == null || mapping.getQuoteDate() == null
            ? null : mapping.getQuoteDate().toString());
        row.put("quotePrice", mapping == null ? null : mapping.getQuotePrice());
        // 采购价（毛利率口径的定价基数）与匹配结果，界面据此显示「待采购价」的原因
        row.put("costPrice", mapping == null ? null : mapping.getSupplyUnitPrice());
        row.put("costLevel", mapping == null ? null : mapping.getSupplyMatchLevel());
        row.put("uplift", mapping == null ? null : mapping.getUplift());
        row.put("salesUnitPrice", mapping == null ? null : mapping.getSalesUnitPrice());
        row.put("pricingQuantity", mapping == null ? null : mapping.getPricingQuantity());
        row.put("pricingUnit", mapping == null ? null : mapping.getPricingUnit());
        row.put("amount", mapping == null ? null : mapping.getAmount());
        row.put("priceBasis", mapping == null ? "unmatched" : mapping.getPriceBasis());
        row.put("status", mapping == null ? "unmatched" : mapping.getStatus());
        row.put("pricingNote", pricingLabel(doc, mapping));
        row.put("rowType", "item");
        rows.add(row);
      }
      // 该送货单的销售运费（运费表里挂在这张单上的行合计）
      BigDecimal docFreight = salesFreightRows.stream()
          .filter(freight -> doc.getId().equals(freight.getSourceDocumentId()))
          .map(com.aibot.purchase.entity.FreightItem::getAmount)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (docFreight.compareTo(BigDecimal.ZERO) > 0) {
        rows.add(salesFreightDetailRow(doc, docFreight, "销售运费", "sales_freight"));
      }
    }
    // 口径运费是批次级的一笔，不挂在任何单据上：单独一行，保证明细合计 = 销售总金额
    BigDecimal policyFreight = salesFreightRows.stream()
        .filter(freight -> freight.getSourceDocumentId() == null)
        .map(com.aibot.purchase.entity.FreightItem::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (policyFreight.compareTo(BigDecimal.ZERO) > 0) {
      rows.add(salesFreightDetailRow(null, policyFreight, "销售运费（按口径）", "sales_freight_policy"));
    }
    return rows;
  }

  /** 销售明细里的运费条目：字段与材料行对齐，前端按 status=freight 只读展示。 */
  private Map<String, Object> salesFreightDetailRow(
      Document doc, BigDecimal amount, String materialName, String priceBasis) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("mappingId", null);
    row.put("documentId", doc == null ? null : doc.getId());
    row.put("docCode", doc == null ? null : doc.getDocCode());
    row.put("docDate", doc == null || doc.getDocDate() == null
        ? null : doc.getDocDate().toString());
    row.put("materialName", materialName);
    row.put("spec", null);
    row.put("quoteSource", null);
    row.put("quoteSpecName", null);
    row.put("quoteDate", null);
    row.put("quotePrice", null);
    row.put("costPrice", null);
    row.put("costLevel", null);
    row.put("uplift", null);
    row.put("salesUnitPrice", null);
    row.put("pricingQuantity", null);
    row.put("pricingUnit", null);
    row.put("amount", amount);
    row.put("priceBasis", priceBasis);
    row.put("status", "freight");
    row.put("pricingNote", null);
    row.put("rowType", "sales_freight");
    return row;
  }

  /**
   * 明细行的销售定价说明（毛利率X% / 网价+X）：锁定记录的定价方式优先，
   * 无记录时回退单据级定价字段；历史单据无定价信息返回 null（前端显示 -）。
   */
  private String pricingLabel(Document doc, DocumentItemMarketMapping mapping) {
    String mode = mapping != null && mapping.getPricingMode() != null
        ? mapping.getPricingMode() : doc.getPricingMode();
    if ("margin".equals(mode)) {
      BigDecimal rate = doc.getMarginRate() != null ? doc.getMarginRate() : new BigDecimal("2.5");
      return "毛利率" + strip(rate) + "%";
    }
    if ("uplift".equals(mode)) {
      if (mapping != null && mapping.getUplift() != null) {
        return "网价+" + strip(mapping.getUplift());
      }
      if (doc.getUpliftAmount() != null) {
        return "网价+" + strip(doc.getUpliftAmount());
      }
    }
    return null;
  }

  private static String strip(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  // ---------------------------------------------------------------------------
  // 材料级绑定消费（2026-11-04）：已归并且绑定网价的行按材料共用一条绑定取价
  // ---------------------------------------------------------------------------

  /** 批次材料一次载入（材料 id → 实体）；批次没有材料层（老批次未重跑）返回空表。 */
  private Map<String, BatchMaterial> loadMaterials(String batchId) {
    if (!StringUtils.hasText(batchId)) {
      return Map.of();
    }
    Map<String, BatchMaterial> byId = new HashMap<>();
    for (BatchMaterial material : batchMaterialMapper.selectList(
        Wrappers.<BatchMaterial>lambdaQuery().eq(BatchMaterial::getBatchId, batchId))) {
      byId.put(material.getId(), material);
    }
    return byId;
  }

  private static boolean isBound(BatchMaterial material) {
    return material != null
        && StringUtils.hasText(material.getQuoteProductCode())
        && StringUtils.hasText(material.getQuoteSpecName());
  }

  private static Binding bindingOf(DocumentItem item, Map<String, BatchMaterial> materials) {
    if (item.getMaterialId() == null) {
      return null;
    }
    BatchMaterial material = materials.get(item.getMaterialId());
    return isBound(material)
        ? new Binding(material.getQuoteProductCode(), material.getQuoteSpecName())
        : null;
  }

  /** 分组键：已绑定材料按材料分组（同材料必然同网价），否则按旧的写法分组。 */
  private static String matchKey(DocumentItem item, Map<String, BatchMaterial> materials) {
    return bindingOf(item, materials) != null
        ? "M:" + item.getMaterialId()
        : groupKey(item);
  }

  /** 批次内已确认采购单（材料层刷新后重锁行情价用）。 */
  public List<Document> confirmedSupplies(String batchId) {
    return documentMapper.selectList(Wrappers.<Document>lambdaQuery()
        .eq(Document::getBatchId, batchId)
        .eq(Document::getDocType, "supply_order")
        .in(Document::getStatus, "matched", "archived"));
  }

  // ---------------------------------------------------------------------------
  // 匹配与计算
  // ---------------------------------------------------------------------------

  /** 分组匹配结果：命中行情行 + 同规格均价 + 取价口径标记。 */
  private record GroupMatch(MarketQuote quote, BigDecimal avgPrice, String originLabel, String method) {
  }

  /** 人工锁定取价结果。 */
  private record LockedOutcome(
      MarketQuote quote, String basis, BigDecimal quotePrice, BigDecimal salesUnitPrice, BigDecimal amount) {
  }

  /** 人工锁定取价：绑定规格精确取价；毛利率口径按采购价倒推（网价只留痕），无采购价则待采购价。 */
  private LockedOutcome resolveManualLocked(
      DocumentItemMarketMapping mapping,
      DocumentItem item,
      PricingQty pq,
      LocalDate target,
      QuoteDay day,
      Map<String, List<MarketQuote>> quotesByProduct,
      PricingPolicy policy,
      PurchaseCostResolver.Cost cost
  ) {
    MarketQuote bound = null;
    if (day != null && pq != null && "吨".equals(pq.unit())
        && StringUtils.hasText(mapping.getProductCode()) && StringUtils.hasText(mapping.getSpecName())) {
      List<MarketQuote> rows = marketCities.preferCity(
          quotesForProduct(mapping.getProductCode(), target, quotesByProduct));
      bound = rows.stream()
          .filter(r -> mapping.getSpecName().equals(r.getSpecName()))
          .filter(r -> r.getPrice() != null && r.getPrice().signum() > 0)
          .findFirst().orElse(null);
    }
    if ("margin".equals(policy.mode())) {
      // 毛利率口径：绑定只解决「看哪条行情」，销售价仍从采购价倒推
      BigDecimal netPrice = bound == null ? null : bound.getPrice();
      if (pq == null || !"吨".equals(pq.unit())) {
        return nz(item.getAmount()).signum() > 0
            ? new LockedOutcome(bound, "fallback_amount", netPrice, null, item.getAmount())
            : new LockedOutcome(bound, "unmatched", netPrice, null, null);
      }
      if (cost == null || !cost.priced()) {
        return new LockedOutcome(bound, "cost_pending", netPrice, null, null);
      }
      BigDecimal salesUnitPrice = policy.salesUnitPriceFromCost(cost.unitPrice(), null);
      return new LockedOutcome(bound, "cost", netPrice, salesUnitPrice,
          salesUnitPrice.multiply(pq.quantity()).setScale(2, RoundingMode.HALF_UP));
    }
    if (bound != null) {
      BigDecimal salesUnitPrice = policy.salesUnitPrice(bound.getPrice());
      return new LockedOutcome(bound, "quote", bound.getPrice(), salesUnitPrice,
          salesUnitPrice.multiply(pq.quantity()).setScale(2, RoundingMode.HALF_UP));
    }
    if (nz(item.getAmount()).signum() > 0) {
      return new LockedOutcome(null, "fallback_amount", null, null, item.getAmount());
    }
    return new LockedOutcome(null, "unmatched", null, null, null);
  }

  private GroupMatch matchGroup(
      DocumentItem item, LocalDate target, QuoteDay day,
      Map<String, List<MarketQuote>> quotesByProduct) {
    return matchGroup(item, target, day, quotesByProduct, null);
  }

  /**
   * 单组匹配：binding 非空时（材料级网价绑定）直接按绑定规格取行情行，
   * 不再解析材料名——绑定就是这一侧匹配的结论；产地优先逻辑与按名匹配共用。
   * 行情行按产品各自解析取价日（quotesForProduct），部分导入日不会遮掉其他品类。
   */
  private GroupMatch matchGroup(
      DocumentItem item, LocalDate target, QuoteDay day,
      Map<String, List<MarketQuote>> quotesByProduct,
      Binding binding) {
    if (day == null) {
      return null;
    }
    // 数量型（无重量）行用不了元/吨行情，跳过匹配（走回退口径）
    PricingQty pq = resolvePricingQuantity(item);
    if (pq == null || !"吨".equals(pq.unit())) {
      return null;
    }
    MarketQuote quote;
    List<MarketQuote> rows;
    String method;
    if (binding != null) {
      rows = marketCities.preferCity(
          quotesForProduct(binding.productCode(), target, quotesByProduct));
      quote = rows.stream()
          .filter(r -> binding.specName().equals(r.getSpecName()))
          .filter(r -> r.getPrice() != null && r.getPrice().signum() > 0)
          .findFirst().orElse(null);
      if (quote == null) {
        return new GroupMatch(null, null, null, "unmatched");
      }
      method = "material";
    } else {
      MarketNameNormalizer.Parsed parsed =
          MarketNameNormalizer.parse(item.getMaterialName(), item.getSpec());
      if (parsed.productCode() == null || parsed.spec() == null) {
        return null;
      }
      rows = marketCities.preferCity(
          quotesForProduct(parsed.productCode(), target, quotesByProduct));
      quote = matchService.matchFromRows(rows, parsed.spec(), parsed.grade());
      if (quote == null) {
        return new GroupMatch(null, null, null, "unmatched");
      }
      method = "exact";
    }
    // 取价口径：先按本城市行情过滤（上面已做，跨城市绝不平均）；明细行有产地时优先匹配该产地行情，
    // 产地未命中再回退同城市同规格全产地均价
    List<MarketQuote> sameSpec = rows.stream()
        .filter(r -> quote.getSpecName() != null && quote.getSpecName().equals(r.getSpecName()))
        .filter(r -> r.getPrice() != null && r.getPrice().signum() > 0)
        .toList();
    if (sameSpec.isEmpty()) {
      return new GroupMatch(quote, quote.getPrice(), null, method);
    }
    String itemOrigin = item.getOrigin() == null ? "" : item.getOrigin().trim();
    if (StringUtils.hasText(itemOrigin)) {
      List<MarketQuote> byOrigin = sameSpec.stream()
          .filter(r -> originMatches(itemOrigin, r.getOrigin()))
          .toList();
      if (!byOrigin.isEmpty()) {
        // 产地命中：单行直接取价，多行（同规格同产地）取均价，产地标记为命中产地
        MarketQuote originRep = byOrigin.get(0);
        if (byOrigin.size() == 1) {
          return new GroupMatch(originRep, originRep.getPrice(), originRep.getOrigin(), method);
        }
        BigDecimal avg = byOrigin.stream()
            .map(MarketQuote::getPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(new BigDecimal(byOrigin.size()), 2, RoundingMode.HALF_UP);
        return new GroupMatch(originRep, avg, originRep.getOrigin(), method);
      }
      // 产地未命中 → 继续走下方全产地均价回退
    }
    if (sameSpec.size() == 1) {
      MarketQuote only = sameSpec.get(0);
      return new GroupMatch(only, only.getPrice(), only.getOrigin(), method);
    }
    BigDecimal sum = sameSpec.stream()
        .map(MarketQuote::getPrice)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal avg = sum.divide(new BigDecimal(sameSpec.size()), 2, RoundingMode.HALF_UP);
    return new GroupMatch(quote, avg, "均价(" + sameSpec.size() + "条)", method);
  }

  /** 材料级网价绑定（产品码 + 规格名），取价时采购/送货两侧共用。 */
  private record Binding(String productCode, String specName) {
  }

  /** 产地匹配：互相包含即视为命中（单据产地常为简称，如「沙钢」命中行情产地「沙钢/沙永」）。 */
  private static boolean originMatches(String itemOrigin, String quoteOrigin) {
    if (!StringUtils.hasText(itemOrigin) || !StringUtils.hasText(quoteOrigin)) {
      return false;
    }
    String a = itemOrigin.trim();
    String b = quoteOrigin.trim();
    return b.contains(a) || a.contains(b);
  }

  /** 行情取价日：≤送货单日期最近有行情日；送货单日期早于行情起点时取最早行情日（非当日标记）。 */
  private QuoteDay resolveQuoteDay(LocalDate docDate) {
    LocalDate target = docDate != null ? docDate : LocalDate.now();
    List<MarketQuote> hit = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .le(MarketQuote::getQuoteDate, target)
        .orderByDesc(MarketQuote::getQuoteDate)
        .last("LIMIT 1"));
    if (!hit.isEmpty()) {
      return new QuoteDay(hit.get(0).getQuoteDate(), true);
    }
    List<MarketQuote> earliest = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
        .orderByAsc(MarketQuote::getQuoteDate)
        .last("LIMIT 1"));
    return earliest.isEmpty() ? null : new QuoteDay(earliest.get(0).getQuoteDate(), false);
  }

  /**
   * 按产品解析取价日并载入该日行情行（缓存按产品）：
   * ≤目标日最近有「该产品」行情的日子；目标日早于该产品行情起点取最早日。
   *
   * <p>2026-11-04 修：全局解析取价日会被「部分日」遮住——例如截图只导入了一个品类，
   * 全局最近日变成那天，其他品类在该日没有行情行就全部落空（连原有兜底价都丢）。
   * 所以日期解析必须按产品各自进行，QuoteDay 只保留「行情库是否为空」的判断用途。
   */
  private List<MarketQuote> quotesForProduct(String productCode, LocalDate target,
                                              Map<String, List<MarketQuote>> cache) {
    return cache.computeIfAbsent(productCode, code -> {
      if (!StringUtils.hasText(code)) {
        return List.of();
      }
      List<MarketQuote> hit = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
          .le(MarketQuote::getQuoteDate, target)
          .eq(MarketQuote::getProductCode, code)
          .orderByDesc(MarketQuote::getQuoteDate)
          .last("LIMIT 1"));
      LocalDate day = hit.isEmpty() ? null : hit.get(0).getQuoteDate();
      if (day == null) {
        List<MarketQuote> earliest = quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
            .eq(MarketQuote::getProductCode, code)
            .orderByAsc(MarketQuote::getQuoteDate)
            .last("LIMIT 1"));
        if (earliest.isEmpty()) {
          return List.of();
        }
        day = earliest.get(0).getQuoteDate();
      }
      return quoteMapper.selectList(Wrappers.<MarketQuote>lambdaQuery()
          .eq(MarketQuote::getQuoteDate, day)
          .eq(MarketQuote::getProductCode, code)
          .orderByDesc(MarketQuote::getSource)
          .last("LIMIT 500"));
    });
  }

  private record QuoteDay(LocalDate date, boolean current) {
  }

  /**
   * 计价数量判定（一次性，锁定后不再自动变）：
   * 重量优先（统一换算成吨），无重量时按件数（件/个/根等），两者皆无返回 null。
   */
  private PricingQty resolvePricingQuantity(DocumentItem item) {
    BigDecimal weight = item.getTotalWeight();
    if (weight != null && weight.signum() > 0) {
      BigDecimal tons = toTons(weight, item.getWeightUnit());
      if (tons != null && tons.signum() > 0) {
        return new PricingQty(tons, "吨");
      }
    }
    BigDecimal packages = item.getPackageQuantity();
    if (packages != null && packages.signum() > 0) {
      String unit = StringUtils.hasText(item.getPackageUnit()) ? item.getPackageUnit() : "件";
      return new PricingQty(packages, unit);
    }
    if (item.getPricingQuantity() != null && item.getPricingQuantity().signum() > 0) {
      String unit = StringUtils.hasText(item.getPricingUnit()) ? item.getPricingUnit() : "件";
      return new PricingQty(item.getPricingQuantity(), unit);
    }
    return null;
  }

  private record PricingQty(BigDecimal quantity, String unit) {
  }

  /** 重量单位换算成吨；缺省按吨处理。 */
  private static BigDecimal toTons(BigDecimal weight, String unit) {
    if (!StringUtils.hasText(unit)) {
      return weight;
    }
    String u = unit.trim().toLowerCase();
    if (u.contains("公斤") || u.contains("千克") || u.equals("kg")) {
      // 10 的幂除法可精确表示，不损失精度
      return weight.divide(new BigDecimal("1000"));
    }
    if (u.equals("克") || u.equals("g")) {
      return weight.divide(new BigDecimal("1000000"));
    }
    // 吨 / t / 其他默认按吨
    return weight;
  }

  private static String groupKey(DocumentItem item) {
    // 产地参与分组：同材料同规格但产地不同的行可能匹配到不同产地行情，不能共享匹配结果
    return (item.getMaterialName() == null ? "" : item.getMaterialName().trim())
        + "|" + (item.getSpec() == null ? "" : item.getSpec().trim())
        + "|" + (item.getOrigin() == null ? "" : item.getOrigin().trim());
  }

  private static BigDecimal lockedTotal(List<DocumentItemMarketMapping> mappings) {
    return mappings.stream()
        .filter(m -> !"unmatched".equals(m.getPriceBasis()))
        .map(DocumentItemMarketMapping::getAmount)
        .map(DeliveryNetPriceService::nz)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private DocumentItemMarketMapping upsertMapping(DocumentItem item, Document doc) {
    DocumentItemMarketMapping mapping = mappingMapper.selectOne(
        Wrappers.<DocumentItemMarketMapping>lambdaQuery()
            .eq(DocumentItemMarketMapping::getDocumentItemId, item.getId())
            .last("LIMIT 1"));
    if (mapping == null) {
      mapping = new DocumentItemMarketMapping();
      mapping.setDocumentItemId(item.getId());
      mapping.setDocumentId(doc.getId());
      mapping.setBatchId(doc.getBatchId());
      mapping.setCreatedAt(LocalDateTime.now());
    }
    return mapping;
  }

  private void saveMapping(DocumentItemMarketMapping mapping) {
    if (StringUtils.hasText(mapping.getId())) {
      mappingMapper.updateById(mapping);
    } else {
      mappingMapper.insert(mapping);
    }
  }

  /**
   * 采购价三列单独写：updateById 会跳过 null 字段，采购单删除后必须把旧采购价显式清成 NULL，
   * 否则重锁留着上一次的价格继续算销售价（静默算错）。
   */
  private void saveCostColumns(DocumentItemMarketMapping mapping, PurchaseCostResolver.Cost cost) {
    if (mapping == null || !StringUtils.hasText(mapping.getId())) {
      return;
    }
    BigDecimal price = cost == null ? null : cost.unitPrice();
    String supplyItemId = cost == null ? null : cost.supplyItemId();
    String level = cost == null ? null : cost.level();
    mappingMapper.update(null, Wrappers.<DocumentItemMarketMapping>lambdaUpdate()
        .eq(DocumentItemMarketMapping::getId, mapping.getId())
        .set(DocumentItemMarketMapping::getSupplyUnitPrice, price)
        .set(DocumentItemMarketMapping::getSupplyItemId, supplyItemId)
        .set(DocumentItemMarketMapping::getSupplyMatchLevel, level));
    mapping.setSupplyUnitPrice(price);
    mapping.setSupplyItemId(supplyItemId);
    mapping.setSupplyMatchLevel(level);
  }

  /**
   * 落库锁定结果（含显式清空）。
   *
   * <p>不能用 updateById：它跳过 null 字段，于是「口径从网价换成毛利率、采购价又还没到」时
   * 旧的销售单价/金额会留在库里继续进台账，页面上却显示「待采购价」——金额和界面打架。
   * 这里把每次重锁会变的列全部显式 set，null 才能真的落成 NULL。
   */
  private void persistMapping(DocumentItemMarketMapping mapping, PurchaseCostResolver.Cost cost) {
    BigDecimal costPrice = cost == null ? null : cost.unitPrice();
    String supplyItemId = cost == null ? null : cost.supplyItemId();
    String supplyLevel = cost == null ? null : cost.level();
    mapping.setSupplyUnitPrice(costPrice);
    mapping.setSupplyItemId(supplyItemId);
    mapping.setSupplyMatchLevel(supplyLevel);
    if (!StringUtils.hasText(mapping.getId())) {
      // 新行：insert 即内存值（null 会如实写库）
      mappingMapper.insert(mapping);
      return;
    }
    mappingMapper.update(null, Wrappers.<DocumentItemMarketMapping>lambdaUpdate()
        .eq(DocumentItemMarketMapping::getId, mapping.getId())
        .set(DocumentItemMarketMapping::getPricingMode, mapping.getPricingMode())
        .set(DocumentItemMarketMapping::getProductCode, mapping.getProductCode())
        .set(DocumentItemMarketMapping::getSpecName, mapping.getSpecName())
        .set(DocumentItemMarketMapping::getQuoteId, mapping.getQuoteId())
        .set(DocumentItemMarketMapping::getQuoteDate, mapping.getQuoteDate())
        .set(DocumentItemMarketMapping::getQuoteSource, mapping.getQuoteSource())
        .set(DocumentItemMarketMapping::getOriginLabel, mapping.getOriginLabel())
        .set(DocumentItemMarketMapping::getQuotePrice, mapping.getQuotePrice())
        .set(DocumentItemMarketMapping::getUplift, mapping.getUplift())
        .set(DocumentItemMarketMapping::getSalesUnitPrice, mapping.getSalesUnitPrice())
        .set(DocumentItemMarketMapping::getPricingQuantity, mapping.getPricingQuantity())
        .set(DocumentItemMarketMapping::getPricingUnit, mapping.getPricingUnit())
        .set(DocumentItemMarketMapping::getAmount, mapping.getAmount())
        .set(DocumentItemMarketMapping::getPriceBasis, mapping.getPriceBasis())
        .set(DocumentItemMarketMapping::getMatchMethod, mapping.getMatchMethod())
        .set(DocumentItemMarketMapping::getStatus, mapping.getStatus())
        .set(DocumentItemMarketMapping::getSupplyUnitPrice, costPrice)
        .set(DocumentItemMarketMapping::getSupplyItemId, supplyItemId)
        .set(DocumentItemMarketMapping::getSupplyMatchLevel, supplyLevel)
        .set(DocumentItemMarketMapping::getUpdatedAt, LocalDateTime.now()));
  }

  private static BigDecimal nz(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }
}
