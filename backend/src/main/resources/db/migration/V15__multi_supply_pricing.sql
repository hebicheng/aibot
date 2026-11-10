-- 一批次可导入多张供货单/送货单：台账骨架行改为按批次累加（批次主台账行），
-- source_document_id 仅留痕首张供货单，不再要求唯一。
ALTER TABLE ledger_records DROP INDEX uk_ledger_source_document;

-- 送货单销售定价方式（确认锁定时消费）：
--   pricing_mode = uplift（网价+上浮，默认）| margin（网价/(1-毛利率)）
--   uplift_amount = 用户指定的上浮金额（元/吨）；为空时用全局配置 purchase.sales-uplift
--   margin_rate   = 用户指定的毛利率（百分数，如 2.5 表示 2.5%）；为空时默认 2.5
ALTER TABLE documents
  ADD COLUMN pricing_mode VARCHAR(16) NULL COMMENT '销售定价方式：uplift/margin（送货单）' AFTER remark,
  ADD COLUMN uplift_amount DECIMAL(12,2) NULL COMMENT '指定上浮金额（元/吨）' AFTER pricing_mode,
  ADD COLUMN margin_rate DECIMAL(8,4) NULL COMMENT '指定毛利率（百分数，如2.5）' AFTER uplift_amount;

-- 锁定记录追溯每行采用的定价方式；uplift 列在 margin 模式下存「销售价-网价」差额
ALTER TABLE document_item_market_mappings
  ADD COLUMN pricing_mode VARCHAR(16) NULL COMMENT '锁定时的定价方式：uplift/margin' AFTER uplift;
