-- V13: 用户设置（JSON，避免往 users 表不断加字段）+ 对账单导出提醒
-- 1) user_settings：一用户一行，settings 存 JSON（新增配置项零迁移）。
--    本期键：reconciliation.enabled / reconciliation.weekday(1-7) / reconciliation.hour(0-23)
-- 2) reconciliation_reminders：用户自定义的一次性对账单导出预约
--    （聊天里说「10分钟后/明天上午9点要对账单」时创建，到点推送统一导出卡片）。

CREATE TABLE user_settings (
  user_id     VARCHAR(36) PRIMARY KEY,
  settings    JSON NOT NULL COMMENT '用户偏好设置（键值见 UserSettingsService）',
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reconciliation_reminders (
  id          VARCHAR(36) PRIMARY KEY,
  user_id     VARCHAR(36) NOT NULL,
  project_id  VARCHAR(36) NOT NULL,
  batch_id    VARCHAR(36) NULL COMMENT '预约时所在会话批次；到点卡片插回该会话',
  trigger_at  DATETIME NOT NULL COMMENT '到点推送时间',
  data_start  DATE NULL COMMENT '对账数据起始日；NULL=到点卡片内默认上周可改',
  data_end    DATE NULL COMMENT '对账数据结束日',
  status      VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/done/cancelled',
  source      VARCHAR(24) NOT NULL DEFAULT 'chat' COMMENT 'chat=聊天预约',
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_recon_remind_due (status, trigger_at),
  INDEX idx_recon_remind_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
