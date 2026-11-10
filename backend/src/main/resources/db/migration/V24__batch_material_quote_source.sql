-- V24: 材料网价绑定的来源（2026-10-30，第 2 刀）
--
-- 背景：V22 的绑定参数（quote_product_code / quote_spec_name / quote_origin）最初只有人工绑定一种来源。
-- 第 2 刀起，自动匹配也把结果钉在材料上（一种材料一个网价，采购侧与送货侧共用同一条绑定），
-- 否则同一个材料在采购单、送货单各算一次，仍会出现两个价。
--
-- 两个来源必须能区分，因为处置方式相反：
--   manual → 人工选的，任何自动流程（重跑批次、重新匹配）都不得覆盖；
--   auto   → 系统匹配的，重跑批次/新增单据时允许刷新（人工绑过的不动）。
--
-- match_source 是"归并来源"（rule/ai/manual，表示材料身份从哪来），与本列不是一回事，不能混用。

ALTER TABLE batch_materials
  ADD COLUMN quote_source VARCHAR(16) NULL COMMENT '网价绑定来源：manual=人工绑定 / auto=自动匹配 / NULL=未绑定或历史人工绑定' AFTER quote_origin;
