-- 计费 / 积分体系
-- 设计：大模型每次调用（聊天识别、行情 AI 匹配）都按返回的 token 用量记账并扣减用户积分。
-- 用户感知的是「积分」；token→积分的换算系数（ratio / pricePerToken）可配置，当前默认 1，后续可调。

-- 1. 每次模型调用明细
CREATE TABLE IF NOT EXISTS ai_usage_logs (
  id              VARCHAR(36)  NOT NULL,
  user_id         VARCHAR(36)  NOT NULL,
  project_id      VARCHAR(36),
  batch_id        VARCHAR(36),
  model           VARCHAR(64),
  business_type   VARCHAR(32)  NOT NULL COMMENT 'chat=聊天识别, market_match=行情AI匹配',
  prompt_tokens   BIGINT       NOT NULL DEFAULT 0,
  completion_tokens BIGINT     NOT NULL DEFAULT 0,
  total_tokens    BIGINT       NOT NULL DEFAULT 0,
  bill_tokens     BIGINT       NOT NULL DEFAULT 0 COMMENT '已乘倍率的计费token',
  cost_credits    BIGINT       NOT NULL DEFAULT 0 COMMENT '实际扣减积分',
  ref_message_id  VARCHAR(36),
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_ai_usage_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 用户积分账户（每用户一行）
CREATE TABLE IF NOT EXISTS user_credits (
  user_id         VARCHAR(36)  NOT NULL,
  balance         BIGINT       NOT NULL DEFAULT 0 COMMENT '当前余额',
  total_granted   BIGINT       NOT NULL DEFAULT 0 COMMENT '累计发放',
  total_used      BIGINT       NOT NULL DEFAULT 0 COMMENT '累计消耗',
  monthly_quota   BIGINT       NOT NULL DEFAULT 1500 COMMENT '每月刷新额度',
  period_month    VARCHAR(7)   COMMENT '当前账期 YYYY-MM',
  period_start    DATETIME(3),
  period_end      DATETIME(3),
  created_at      DATETIME(3)  NOT NULL,
  updated_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 积分流水（审计）
CREATE TABLE IF NOT EXISTS credit_transactions (
  id              VARCHAR(36)  NOT NULL,
  user_id         VARCHAR(36)  NOT NULL,
  delta           BIGINT       NOT NULL COMMENT '正=发放, 负=消耗',
  type            VARCHAR(16)  NOT NULL COMMENT 'consume / grant',
  ref_usage_log_id VARCHAR(36),
  remark          VARCHAR(255),
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_credit_tx_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. chat_messages 增加「本次回复消耗积分」列，前端直接读取展示
ALTER TABLE chat_messages ADD COLUMN credits_used BIGINT NOT NULL DEFAULT 0;
