package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 项目级收款：一笔客户回款，可分摊到同项目下多个批次（见 V29）。 */
@Data
@TableName("project_receipts")
public class ProjectReceipt {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String projectId;
  private LocalDate receiptDate;
  private BigDecimal amount;
  private String payer;
  private String remark;
  private String sourceDocumentId;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  @TableLogic
  private Integer deleted;
}
