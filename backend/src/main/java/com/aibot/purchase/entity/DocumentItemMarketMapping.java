package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 送货单明细行 × 行情匹配锁定记录（销售网价）。
 *
 * <p>送货单确认时同步生成/更新；台账销售金额与对账单导出读本表的锁定值。
 */
@Data
@TableName("document_item_market_mappings")
public class DocumentItemMarketMapping {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String documentItemId;
  private String documentId;
  private String batchId;
  private String productCode;
  private String specName;
  private String quoteId;
  private LocalDate quoteDate;
  private String quoteSource;
  private String originLabel;
  private BigDecimal quotePrice;
  private BigDecimal uplift;
  /** 锁定时的定价方式：uplift（uplift 列=上浮金额）/ margin（uplift 列=销售价-网价差额）。 */
  private String pricingMode;
  private BigDecimal salesUnitPrice;
  private BigDecimal pricingQuantity;
  private String pricingUnit;
  /** 命中的采购单明细行 id（毛利率口径定价依据，多行加权时取吨数最大的一行留痕）。 */
  private String supplyItemId;
  /** 匹配到的采购吨价（元/吨）：毛利率口径的销售价按它倒推。 */
  private BigDecimal supplyUnitPrice;
  /**
   * 采购价匹配结果：exact=同大类+同直径单行 / origin=产地命中 / weighted=多行吨数加权 /
   * spec_missing=有采购单但没这个规格 / no_supply=批次还没有采购单。
   */
  private String supplyMatchLevel;
  private BigDecimal amount;
  /** cost=采购价倒推 / cost_pending=待采购价 / quote=网价计算 / fallback_amount=回退单据金额 / unmatched=无法计价 */
  private String priceBasis;
  private String matchMethod;
  private BigDecimal confidence;
  /** auto/manual/unmatched/ai_pending */
  private String status;
  private String aiRaw;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
