package com.aibot.purchase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户偏好设置（JSON 单行，避免往 users 表不断加字段）。
 *
 * <p>settings 键由 {@link com.aibot.purchase.service.UserSettingsService} 统一读写，
 * 新增配置项零迁移。本期键：reconciliation.enabled / weekday / hour。
 */
@Data
@TableName("user_settings")
public class UserSettings {
  @TableId(type = IdType.INPUT)
  private String userId;
  /** JSON 字符串（MySQL JSON 列），由 service 层解析合并 */
  private String settings;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
