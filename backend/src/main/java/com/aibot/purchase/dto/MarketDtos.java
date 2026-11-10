package com.aibot.purchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 市场行情模块 DTO */
public final class MarketDtos {

  private MarketDtos() {
  }

  /** 行情明细行（Tab2 全量行情用） */
  public record QuoteRow(
      String id,
      LocalDate quoteDate,
      String productCode,
      String source,
      String productName,
      String specName,
      String spec,
      String grade,
      String origin,
      String city,
      BigDecimal price,
      BigDecimal priceChange,
      String detailUrl
  ) {
  }

  /** 产品字典 */
  public record ProductItem(
      String code,
      String category,
      String name,
      String sourceUrl,
      Boolean isNav
  ) {
  }

  /** 材料 × 行情对比行（Tab1 用）。锁定行情 = 计算销售单价所用的那笔行情；市场价 = 最新行情（对比筛选器口径）。 */
  public record CompareRow(
      String materialId,
      String materialName,
      String materialSpec,
      String unit,
      BigDecimal quantity,
      BigDecimal businessPrice,
      String matchStatus,
      String matchMethod,
      BigDecimal confidence,
      String productCode,
      String productName,
      String marketSpecName,
      String origin,
      String city,
      LocalDate quoteDate,
      BigDecimal marketPrice,
      BigDecimal diff,
      BigDecimal diffRate,
      /** 锁定行情日期（销售单价的取价依据） */
      LocalDate lockedDate,
      /** 锁定行情价（元/吨，多明细按计价数量加权） */
      BigDecimal lockedPrice,
      /** 锁定行情产地（产地命中时为该产地名；多产地均价时为「均价(N条)」标记） */
      String lockedOrigin
  ) {
  }

  /** 批次对比聚合 */
  public record BatchCompare(
      String batchId,
      String batchName,
      int totalMaterials,
      int matchedMaterials,
      int unmatchedMaterials,
      BigDecimal totalBusinessAmount,
      BigDecimal totalMarketAmount,
      BigDecimal totalDiff,
      BigDecimal avgDiffRate,
      List<CompareRow> materials
  ) {
  }

  /** 爬取日志 */
  public record CrawlLogRow(
      String id,
      String runStart,
      String runEnd,
      String status,
      String productCode,
      LocalDate quoteDate,
      int rowsFound,
      int rowsUpserted,
      int rowsSkipped,
      String errorMsg
  ) {
  }

  /** 匹配状态行 */
  public record MappingRow(
      String id,
      String materialId,
      String materialName,
      String materialSpec,
      String productCode,
      String productName,
      String specName,
      String origin,
      String city,
      String matchMethod,
      BigDecimal confidence,
      String status,
      /** 锁定取价的行情日期（送货单载体；null=尚未取价） */
      String quoteDate,
      /** 锁定行情价（元/吨；人工改价行=人工输入价） */
      BigDecimal quotePrice
  ) {
  }

  /** 人工修正请求 */
  public record ManualMappingRequest(
      String materialId,
      String productCode,
      String specName,
      String origin,
      String city,
      String status,
      /** 人工锁定行情价（元/吨，选填；填写时以该价为准，不按行情库取价） */
      BigDecimal quotePrice
  ) {
  }

  /** 行情筛选条件响应：最新日期、可选城市、可选产地 */
  public record MarketMeta(
      LocalDate latestDate,
      List<String> categories,
      List<ProductItem> products,
      List<String> cities,
      List<String> origins
  ) {
  }
}
