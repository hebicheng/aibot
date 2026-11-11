-- V27: 运费独立成表（2026-11-11）
--
-- 背景：运费原来散在三处——documents.sales_freight（销售运费列）、
-- structuredData.transportFee（送货单上写的采购运费）、ledger_records.freight（批次汇总）。
-- 采购侧因此出现「AI 按口径把 transportFee 填进单据 + 后端又按口径算一份」的双计。
--
-- 改为一行一笔运费，四类来源并列存在、计算时累加（用户 2026-11-11 拍板）：
--   policy_ton      元/吨口径（单价 × 实际重量）
--   policy_trip     元/趟口径（整批一趟包干价，不乘张数）
--   transport_note  单独导入的运输单
--   document_stated 单据上明确写的运费
--
-- 表由 FreightService.rebuild 按批次幂等重建（先删后插），所以不需要复杂的状态字段。
-- 存量批次不回填（用户明确：存量数据不管）。

CREATE TABLE freight_items (
  id                 VARCHAR(36)  NOT NULL,
  project_id         VARCHAR(36)  NOT NULL,
  batch_id           VARCHAR(36)  NOT NULL,
  side               VARCHAR(16)  NOT NULL COMMENT 'purchase=采购运费（我方成本） / sales=销售运费（客户承担，入销售额）',
  source             VARCHAR(24)  NOT NULL COMMENT 'policy_ton / policy_trip / transport_note / document_stated',
  source_document_id VARCHAR(36)  NULL COMMENT '来源单据：运输单 / 写明运费的送货单或采购单；口径行为空',
  tons               DECIMAL(18,4) NULL COMMENT '按吨口径命中的实际重量（吨），备查',
  unit_price         DECIMAL(18,4) NULL COMMENT '按吨/按趟单价，备查',
  amount             DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '本笔运费金额',
  note               VARCHAR(255) NULL COMMENT '展示用说明（如「按口径 30 元/吨 × 98 吨」）',
  created_at         DATETIME     NULL,
  updated_at         DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_freight_batch (batch_id, side),
  KEY idx_freight_doc (source_document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批次运费明细：口径/运输单/单据写明并列存在，计算时累加';
