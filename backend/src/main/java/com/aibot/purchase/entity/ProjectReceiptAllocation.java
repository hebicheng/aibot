package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 收款 ↔ 批次分摊：一笔收款在某个批次上分到的金额。 */
@Data
@TableName("project_receipt_allocations")
public class ProjectReceiptAllocation {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String receiptId;
  private String projectId;
  private String batchId;
  private BigDecimal amount;
  private Integer sortOrder;
  private LocalDateTime createdAt;
}
