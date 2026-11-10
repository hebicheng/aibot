-- 采购单明细行人工销售价（元/计价单位）：
--   NULL = 按批次默认口径自动推算（毛利率口径：采购吨价 ÷ (1 - 毛利率)，个位四舍五入取整十）；
--   非空 = 人工指定值，采购毛利与卡片/数据中心一律以它为准，改口径不覆盖人工值。
-- 历史数据不回填：回填会让口径变更不再生效，只影响之后手工改过的行。
ALTER TABLE document_items
  ADD COLUMN sales_unit_price DECIMAL(12,2) NULL COMMENT '人工销售单价（元/计价单位），NULL=按批次口径自动推算' AFTER net_price;
