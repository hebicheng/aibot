package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("operation_logs")
public class OperationLog {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String userId;
  private String projectId;
  private String batchId;
  private String action;
  private String content;
  private LocalDateTime createdAt;
}
