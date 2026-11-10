-- 月结加价款支持：
-- 1. ledger_records 增加「是否月结」标识（批次级，挂在批次首条台账记录上）
-- 2. 利息为时点值（随当前日期变化），不落库，由接口每次现算返回
ALTER TABLE ledger_records
  ADD COLUMN is_monthly_settlement TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否月结（月结时按回款日期计算加价利息）' AFTER required_receipt_date;
