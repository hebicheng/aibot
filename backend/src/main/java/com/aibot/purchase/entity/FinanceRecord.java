package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("finance_records")
public class FinanceRecord {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String batchId;
  private String type;
  private String target;
  private BigDecimal amount;
  private String status;
  private LocalDate occurredDate;
  private String sourceMessageId;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
