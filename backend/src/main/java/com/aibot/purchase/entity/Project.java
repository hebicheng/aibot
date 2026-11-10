package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("projects")
public class Project {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String ownerId;
  private String name;
  private String customerName;
  private String status;
  /** 项目级业务默认口径（结构同 batches.business_hints，schema 见 V21 迁移注释）。 */
  private String businessHints;
  private Integer progress;
  private BigDecimal purchaseAmount;
  private BigDecimal salesAmount;
  private BigDecimal profitAmount;
  private BigDecimal marginRate;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  @TableLogic
  private Integer deleted;
}
