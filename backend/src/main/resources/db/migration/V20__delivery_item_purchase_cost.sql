-- 送货单明细行的采购成本关联（2026-10-28）：
-- 毛利率口径的销售价改为「采购吨价 ÷ (1−毛利率)」倒推，送货单需要拿到同批次采购单的吨价。
--   supply_item_id    = 命中的采购明细行（多行加权时取吨数最大的一行留痕，仅供追溯）
--   supply_unit_price = 采购吨价（元/吨）= Σ命中行采购金额 ÷ Σ命中行计价吨数
--   supply_match_level= exact（同大类+同直径单行）/ origin（产地命中）/ weighted（多行加权）/
--                       spec_missing（有采购单但没这个规格）/ no_supply（批次还没有采购单）
-- 历史数据不回填：老送货单保持原锁定值，需要校准的批次手动点「重跑批次」。
ALTER TABLE document_item_market_mappings
  ADD COLUMN supply_item_id VARCHAR(36) NULL COMMENT '命中的采购单明细行 id（多行加权时取吨数最大的一行）' AFTER pricing_unit,
  ADD COLUMN supply_unit_price DECIMAL(12,2) NULL COMMENT '采购吨价（元/吨），毛利率口径定价基数' AFTER supply_item_id,
  ADD COLUMN supply_match_level VARCHAR(24) NULL COMMENT 'exact/origin/weighted/spec_missing/no_supply' AFTER supply_unit_price;
