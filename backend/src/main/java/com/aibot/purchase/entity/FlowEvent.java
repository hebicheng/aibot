package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("flow_events")
public class FlowEvent {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String batchId;
  private String code;
  private String title;
  private String status;
  private String eventTime;
  private String note;
  private String detail;
  private Integer sortOrder;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
