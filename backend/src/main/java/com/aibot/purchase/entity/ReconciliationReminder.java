package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户自定义的一次性对账单导出预约。
 *
 * <p>聊天里说「10分钟后/明天上午9点要对账单」时创建；调度器到点推送统一导出卡片后标 done。
 */
@Data
@TableName("reconciliation_reminders")
public class ReconciliationReminder {
  @TableId(type = IdType.ASSIGN_UUID)
  private String id;
  private String userId;
  private String projectId;
  /** 预约时所在会话批次；到点卡片插回该会话 */
  private String batchId;
  private LocalDateTime triggerAt;
  /** 对账数据起始日；NULL = 到点卡片内默认上周可改 */
  private LocalDate dataStart;
  private LocalDate dataEnd;
  /** pending / done / cancelled */
  private String status;
  /** chat = 聊天预约 */
  private String source;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
