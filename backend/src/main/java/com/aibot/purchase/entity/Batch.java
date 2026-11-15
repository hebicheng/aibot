package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("batches")
public class Batch {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String projectId;
  private String name;
  private LocalDate businessDate;
  private String materialSummary;
  private String status;
  private Integer progress;
  private BigDecimal purchaseAmount;
  private BigDecimal salesAmount;
  private BigDecimal profitAmount;
  private BigDecimal marginRate;
  private String remark;
  /** 是否已对账：人工勾选（V29） */
  private Boolean reconciled;
  /** 批次级业务默认口径（MySQL JSON 列），结构见 V17 迁移注释，由 BatchHintInterceptor 统一读写 */
  private String businessHints;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  @TableLogic
  private Integer deleted;
}
