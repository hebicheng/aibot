package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("chat_messages")
public class ChatMessage {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String userId;
  private String projectId;
  private String batchId;
  private String sender;
  private String messageType;
  private String businessType;
  private String content;
  private String fileName;
  private String status;
  private String payload;
  private java.math.BigDecimal creditsUsed;
  private LocalDateTime createdAt;
}
