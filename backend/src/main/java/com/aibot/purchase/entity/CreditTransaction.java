package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("credit_transactions")
public class CreditTransaction {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String userId;
  private java.math.BigDecimal delta;
  private String type;
  private String refUsageLogId;
  private String remark;
  private LocalDateTime createdAt;
}
