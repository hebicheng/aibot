-- V17: 批次级业务提示词（2026-10-01）
-- 用户在批次会话里单独发送业务口径（如「运费300元/趟」「钢筋销售价：网价+30」「进价毛利2.5%」）时，
-- 存为本批次默认口径；本批次后续导入送货单未附带说明时自动套用（单据附带信息优先）。
-- 结构按单据类型分 scope（本期仅 delivery_note），扩展其它单据类型零迁移：
-- {
--   "delivery_note": {
--     "supplyFreightPerTrip": 300,   // 供货运费 元/趟（transportFee 默认值）
--     "supplyFreightPerTon": 25,     // 供货运费 元/吨（按计价吨数算 transportFee）
--     "salesFreightPerTrip": 50,     // 销售运费 元/趟（salesFreight 默认值）
--     "salesFreightPerTon": 25,      // 销售运费 元/吨
--     "pricingMode": "uplift",       // uplift | margin
--     "upliftAmount": 30,            // 元/吨
--     "marginRate": 2.5,             // %
--     "rawText": "...",              // 最近一次原始输入留痕
--     "updatedAt": "2026-10-01T12:00:00"
--   }
-- }
-- 历史批次为 NULL = 无默认口径，不做回填（沿用「只影响新数据」惯例）。

ALTER TABLE batches
  ADD COLUMN business_hints JSON NULL COMMENT '批次级业务默认口径（按单据类型 scope 分组，见 BatchHintInterceptor）' AFTER remark;
