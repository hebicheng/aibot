-- 送货单/供货单明细行增加产地列：
--   识别阶段抽取单据上的产地（钢厂），网价匹配时优先按产地过滤行情（匹配不到才回退同规格均价）。
-- 历史数据不回填，只影响之后识别的明细行。
ALTER TABLE document_items
  ADD COLUMN origin VARCHAR(64) NULL COMMENT '产地/钢厂（单据产地列）' AFTER spec;
