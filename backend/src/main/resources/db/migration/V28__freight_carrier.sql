-- V28: 运费行自带承运单位（2026-11-11）
--
-- V27 建表时漏了承运单位：台账「运输费用」行的承运单位还是每次去源单据拼
-- （运输单取 supplier_name、送货单取 structuredData.carrierName、口径行硬编码"待补充"），
-- 与「运费独立成表」的设计冲突，也没法人工修改。
--
-- 改为存在运费行上：
--   carrier_name   承运单位（运输单/送货单自动带入，可人工覆盖）
--   carrier_manual 1=人工改过：FreightService.rebuild 每次先删后插，靠这个标记保住人工值，
--                  否则口径变更/单据变动一次，手填的承运单位就被单据值盖掉。

ALTER TABLE freight_items
  ADD COLUMN carrier_name VARCHAR(128) NULL
    COMMENT '承运单位：运输单取往来单位 / 送货单取 structuredData.carrierName / 可人工覆盖' AFTER note,
  ADD COLUMN carrier_manual TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '1=人工改过承运单位，重建运费行时保留人工值' AFTER carrier_name;
