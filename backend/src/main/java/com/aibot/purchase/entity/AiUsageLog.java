package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_usage_logs")
public class AiUsageLog {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String userId;
  private String projectId;
  private String batchId;
  private String model;
  private String businessType;
  private Long promptTokens;
  private Long completionTokens;
  private Long totalTokens;
  private java.math.BigDecimal billTokens;
  private java.math.BigDecimal costCredits;
  private String refMessageId;
  private LocalDateTime createdAt;
}
