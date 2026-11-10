-- V21: 项目级默认口径（2026-10-28）
-- 用户在对话里声明「整个项目都按这个口径」时，由 AI 判定 target=project，
-- 落库到当前聊天批次所属项目的 business_hints（结构完全同 batches.business_hints）。
--
-- 生效规则（读取时合并，不回写批次）：
--   1. 批次该字段有值 → 用批次的；
--   2. 批次该字段为空、项目有 → 用项目的；
--   3. 两者都没有 → 无默认口径（沿用单据自身/全局默认）。
-- 同「组」的键必须整组回退，避免出现「元/趟 + 元/吨」并存：
--   采购运费组 supplyFreightPerTrip | supplyFreightPerTon
--   销售运费组 salesFreightPerTrip | salesFreightPerTon
--   定价组     pricingMode + upliftAmount | marginRate
--
-- 历史项目为 NULL = 无项目级口径，不做回填。

ALTER TABLE projects
  ADD COLUMN business_hints JSON NULL COMMENT '项目级业务默认口径（结构同 batches.business_hints）' AFTER status;
