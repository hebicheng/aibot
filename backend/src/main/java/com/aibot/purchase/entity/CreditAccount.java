package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("user_credits")
public class CreditAccount {
  @TableId(type = IdType.INPUT)
  private String userId;
  private java.math.BigDecimal balance;
  private java.math.BigDecimal totalGranted;
  private java.math.BigDecimal totalUsed;
  private java.math.BigDecimal monthlyQuota;
  private String periodMonth;
  private LocalDateTime periodStart;
  private LocalDateTime periodEnd;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
