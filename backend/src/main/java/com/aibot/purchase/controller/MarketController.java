package com.aibot.purchase.controller;

import com.aibot.purchase.common.ApiResponse;
import com.aibot.purchase.common.CurrentUser;
import com.aibot.purchase.dto.MarketDtos.BatchCompare;
import com.aibot.purchase.dto.MarketDtos.CrawlLogRow;
import com.aibot.purchase.dto.MarketDtos.ManualMappingRequest;
import com.aibot.purchase.dto.MarketDtos.MappingRow;
import com.aibot.purchase.dto.MarketDtos.MarketMeta;
import com.aibot.purchase.dto.MarketDtos.QuoteRow;
import com.aibot.purchase.entity.MarketCrawlLog;
import com.aibot.purchase.mapper.MarketCrawlLogMapper;
import com.aibot.purchase.service.BusinessQueryService;
import com.aibot.purchase.service.market.MarketCrawlerService;
import com.aibot.purchase.service.market.MarketQueryService;
import com.aibot.purchase.service.market.MaterialMarketMatchService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 市场行情模块接口：
 * - Tab2 全量行情：GET /api/market/quotes
 * - Tab1 项目对比：GET /api/market/compare?batchId=
 * - 匹配管理：GET /api/market/mappings、PUT /api/market/mappings
 * - 爬虫运维：POST /api/market/crawl/run、GET /api/market/crawl/latest
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

  private final MarketQueryService marketQueryService;
  private final MarketCrawlerService crawlerService;
  private final MaterialMarketMatchService matchService;
  private final com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService;
  private final com.aibot.purchase.service.market.MaterialLayerService materialLayerService;
  private final com.aibot.purchase.service.market.MaterialLayerTrigger materialLayerTrigger;
  private final com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService;
  private final MarketCrawlLogMapper crawlLogMapper;
  private final BusinessQueryService businessQueryService;

  public MarketController(
      MarketQueryService marketQueryService,
      MarketCrawlerService crawlerService,
      MaterialMarketMatchService matchService,
      com.aibot.purchase.service.market.DeliveryNetPriceService deliveryNetPriceService,
      com.aibot.purchase.service.market.MaterialLayerService materialLayerService,
      com.aibot.purchase.service.market.MaterialLayerTrigger materialLayerTrigger,
      com.aibot.purchase.service.market.MarketQuoteImportService marketQuoteImportService,
      MarketCrawlLogMapper crawlLogMapper,
      BusinessQueryService businessQueryService
  ) {
    this.marketQueryService = marketQueryService;
    this.crawlerService = crawlerService;
    this.matchService = matchService;
    this.deliveryNetPriceService = deliveryNetPriceService;
    this.materialLayerService = materialLayerService;
    this.materialLayerTrigger = materialLayerTrigger;
    this.marketQuoteImportService = marketQuoteImportService;
    this.crawlLogMapper = crawlLogMapper;
    this.businessQueryService = businessQueryService;
  }

  /** Tab2 全量行情。 */
  @GetMapping("/quotes")
  public ApiResponse<List<QuoteRow>> quotes(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String productCode,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String origin,
      @RequestParam(required = false) String keyword
  ) {
    return ApiResponse.ok(marketQueryService.quotes(date, category, productCode, city, origin, keyword));
  }

  /** 筛选元数据：最新日期、分类、产品、城市。 */
  @GetMapping("/meta")
  public ApiResponse<MarketMeta> meta() {
    return ApiResponse.ok(marketQueryService.meta());
  }

  /** Tab1 批次材料 × 市场价对比。 */
  @GetMapping("/compare")
  public ApiResponse<BatchCompare> compare(
      @RequestParam String batchId,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) String origin
  ) {
    return ApiResponse.ok(marketQueryService.compareBatch(CurrentUser.get().id(), batchId, city, origin));
  }

  // 行级人工绑定接口（GET/PUT /mappings）已随材料层上线废弃（2026-11-04）：
  // 人工定价语义迁移到材料级 manual_sales_price（PUT /materials/bind）。

  /** 手动重跑批次匹配（送货单网价重锁 + AI 兜底）；材料层按最新行情刷新 auto 绑定后重锁。 */
  @PostMapping("/match/run")
  public ApiResponse<Integer> runMatch(@RequestParam String batchId) {
    businessQueryService.getBatch(CurrentUser.get().id(), batchId);
    int count = deliveryNetPriceService.rerunBatch(batchId);
    materialLayerTrigger.afterCommitRefresh(batchId, true);
    return ApiResponse.ok(count);
  }

  // ---------------------------------------------------------------------------
  // 材料清单（一个材料一个入口，2026-11-04）
  // ---------------------------------------------------------------------------

  /** 材料级改价：把该材料取价日的绑定规格价钉成权威价，并按产品+规格刷新受影响批次。 */
  @PutMapping("/materials/{materialId}/quote-price")
  public ApiResponse<com.aibot.purchase.service.market.MarketQuoteImportService.ImportOutcome> updateMaterialQuotePrice(
      @PathVariable String materialId,
      @RequestBody QuotePriceUpdate request) {
    return ApiResponse.ok(materialLayerService.updateMaterialQuotePrice(materialId, request.price()));
  }

  /** 材料清单：批次内每种材料一行（身份 + 网价绑定 + 两侧聚合 + 锁定网价口径）。 */
  @GetMapping("/materials")
  public ApiResponse<List<com.aibot.purchase.service.market.MaterialLayerService.MaterialRow>> materials(
      @RequestParam String batchId) {
    businessQueryService.getBatch(CurrentUser.get().id(), batchId);
    return ApiResponse.ok(materialLayerService.list(batchId));
  }

  /** 人工绑定/解绑材料级网价（manual 永不被自动流程覆盖），绑定后立即重锁。 */
  @PutMapping("/materials/bind")
  public ApiResponse<Void> bindMaterial(
      @RequestBody com.aibot.purchase.service.market.MaterialLayerService.BindRequest request) {
    materialLayerService.bind(request);
    return ApiResponse.ok();
  }

  /** 手动校准：AI 判同新写法 + 重绑 + 重算销售价（同步返回摘要）。 */
  @PostMapping("/materials/recalibrate")
  public ApiResponse<com.aibot.purchase.service.market.MaterialReconcileService.Result> recalibrate(
      @RequestParam String batchId) {
    businessQueryService.getBatch(CurrentUser.get().id(), batchId);
    return ApiResponse.ok(materialLayerService.recalibrate(batchId));
  }

  /** 人工改价：更新某条行情的价格，并按其产品+规格刷新受影响批次（发起批次同步，其余后台）。 */
  @PutMapping("/quotes/{quoteId}")
  public ApiResponse<com.aibot.purchase.service.market.MarketQuoteImportService.ImportOutcome> updateQuotePrice(
      @PathVariable String quoteId,
      @RequestBody QuotePriceUpdate request) {
    return ApiResponse.ok(marketQuoteImportService.updateQuotePrice(
        quoteId, request.price(), request.currentBatchId()));
  }

  /** 改价请求体。 */
  public record QuotePriceUpdate(java.math.BigDecimal price, String currentBatchId) {
  }

  /**
   * 指定产品的行情规格候选（人工绑定下拉用）：规格 + 该规格有哪些来源渠道 + 最近有价日。
   *
   * <p>materialId 决定日期口径：候选按该材料的取价日（≤ 单据日期最近有该产品行情的一天）取，
   * 与锁价同一天，避免「人工导入的旧日期行情在候选里消失」。不传则按今天解析。
   */
  @GetMapping("/products/{code}/specs")
  public ApiResponse<List<Map<String, Object>>> productSpecs(
      @PathVariable String code,
      @RequestParam(required = false) String materialId) {
    return ApiResponse.ok(matchService.candidateSpecs(code, materialId));
  }

  /** 手动触发全量爬取。 */
  @PostMapping("/crawl/run")
  public ApiResponse<Map<String, Object>> crawlRun() {
    return ApiResponse.ok(crawlerService.crawlAll());
  }

  /** 最近一次爬取日志。 */
  @GetMapping("/crawl/latest")
  public ApiResponse<List<CrawlLogRow>> crawlLatest() {
    List<MarketCrawlLog> rows = crawlLogMapper.selectList(Wrappers.<MarketCrawlLog>lambdaQuery()
        .orderByDesc(MarketCrawlLog::getRunStart)
        .last("LIMIT 20"));
    return ApiResponse.ok(rows.stream().map(this::mapLog).toList());
  }

  private CrawlLogRow mapLog(MarketCrawlLog logRow) {
    return new CrawlLogRow(
        logRow.getId(),
        logRow.getRunStart() == null ? null : logRow.getRunStart().toString(),
        logRow.getRunEnd() == null ? null : logRow.getRunEnd().toString(),
        logRow.getStatus(),
        logRow.getProductCode(),
        logRow.getQuoteDate(),
        logRow.getRowsFound() == null ? 0 : logRow.getRowsFound(),
        logRow.getRowsUpserted() == null ? 0 : logRow.getRowsUpserted(),
        logRow.getRowsSkipped() == null ? 0 : logRow.getRowsSkipped(),
        logRow.getErrorMsg()
    );
  }
}
