-- 计费 / 积分精度升级：积分需支持小数（1 积分 = 1 元，单次消耗为 0.x 元）。
-- 将 V8 中建的整数积分列改为 DECIMAL(12,2)，历史已成功的 V8 不会重放，故用 ALTER 补丁。
-- 若目标列已是 DECIMAL 则 MODIFY 为幂等（类型相同无影响）。

ALTER TABLE ai_usage_logs MODIFY COLUMN bill_tokens   DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE ai_usage_logs MODIFY COLUMN cost_credits  DECIMAL(12,2) NOT NULL DEFAULT 0;

ALTER TABLE user_credits MODIFY COLUMN balance       DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE user_credits MODIFY COLUMN total_granted DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE user_credits MODIFY COLUMN total_used    DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE user_credits MODIFY COLUMN monthly_quota DECIMAL(12,2) NOT NULL DEFAULT 1500;

ALTER TABLE credit_transactions MODIFY COLUMN delta DECIMAL(12,2) NOT NULL;

ALTER TABLE chat_messages MODIFY COLUMN credits_used DECIMAL(12,2) NOT NULL DEFAULT 0;
