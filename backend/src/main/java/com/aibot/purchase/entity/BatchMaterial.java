package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 批次材料实体：一个批次内一种材料一行，采购行与送货行的汇聚点（V22，2026-10-29）。
 *
 * <p>这张表只放三类东西：<b>身份</b>（category/grade/diameter/lengthM/origin）、
 * <b>参数</b>（网价绑定 + 定价口径 + 人工价）、<b>归属</b>（supplyItemId 指向唯一那条采购明细行）。
 *
 * <p>采购吨数、送货吨数、销售金额、毛利额、毛利率<b>一律不落库</b>——它们是读时算出来的结果，
 * 落库就会引入"改了口径要重跑才生效"的老问题。
 */
@Data
@TableName("batch_materials")
public class BatchMaterial {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String batchId;
  /** 材料大类：螺纹钢/盘螺/高线/圆钢。 */
  private String category;
  /** 牌号：HRB400 / HRB400E / HPB300，未知为 null。 */
  private String grade;
  /** 直径（mm），字符串便于保留 6.5 这类小数原样。 */
  private String diameter;
  /** 定尺长度（米），仅定尺类材料有值。 */
  private String lengthM;
  /** 产地/钢厂/品牌原文，未知为 null。 */
  private String origin;
  /** 粗键 category|diameter|lengthM，用于组内回填牌号/产地。 */
  private String baseKey;
  /** 细键 category|grade|diameter|lengthM|origin，唯一约束。 */
  private String identityKey;
  /** 取价日：采购侧最早单据日期，送货单继承。 */
  private LocalDate quoteDate;
  private String quoteProductCode;
  private String quoteSpecName;
  private String quoteOrigin;
  /**
   * 网价绑定来源：manual=人工绑定（自动流程不得覆盖）/ auto=系统自动匹配（允许刷新）/
   * null=未绑定或历史人工绑定（V24）。
   */
  private String quoteSource;
  /** 定价方式：uplift / margin；null 表示按批次默认口径。 */
  private String pricingMode;
  private BigDecimal upliftAmount;
  private BigDecimal marginRate;
  /**
   * 人工网价（元/吨，2026-11-12 建）：非空时优先于行情库取价，只影响本材料/本批次。
   *
   * <p>与 {@link #manualSalesPrice} 的分工：人工网价换的是「取价基准」（销售价仍按口径算），
   * 人工销售价直接定死「销售单价」。改价入口在批次材料，改完只重锁本批次，<b>不写行情库</b>。
   */
  private BigDecimal manualQuotePrice;
  private BigDecimal manualSalesPrice;
  /** 对应的采购明细行（同材料同日只有一个价）。 */
  private String supplyItemId;
  /** 归并质量：exact / inferred / pending。 */
  private String matchLevel;
  /** 归并来源：rule / ai / manual。 */
  private String matchSource;
  /** shadow = 影子期只写不读。 */
  private String status;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
