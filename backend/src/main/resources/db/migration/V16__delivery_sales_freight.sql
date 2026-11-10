-- 送货单运费扩展（2026-09-21）：
--   供货运费 = 原运输费用（transportFee，存 structuredData，进台账运输费用行），逻辑不变；
--   销售运费 = 客户承担的运费（销售侧收入），进台账销售总金额（锁定货款+销售运费）。
ALTER TABLE documents
  ADD COLUMN sales_freight DECIMAL(12,2) NULL COMMENT '销售运费（元，选填，客户承担）' AFTER margin_rate;
