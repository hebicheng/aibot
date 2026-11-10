-- V10: 送货单网价锁定（销售侧）
-- 载体：送货单明细行（供货单退出行情链路）。
-- 每条明细行一条锁定记录：行情匹配结果 + 取价 + 计价数量 + 锁定金额。

CREATE TABLE document_item_market_mappings (
  id                VARCHAR(36) PRIMARY KEY,
  document_item_id  VARCHAR(36) NOT NULL COMMENT '送货单明细行 document_items.id',
  document_id       VARCHAR(36) NOT NULL COMMENT '送货单 documents.id',
  batch_id          VARCHAR(36) NOT NULL,
  product_code      VARCHAR(16) NULL COMMENT '匹配到的行情产品code',
  spec_name         VARCHAR(128) NULL COMMENT '匹配到的行情规格+材质原文',
  quote_id          VARCHAR(36) NULL COMMENT '锁定的行情行 market_quotes.id',
  quote_date        DATE NULL COMMENT '实际取价日期（≤送货单日期最近有行情日）',
  quote_source      VARCHAR(32) NULL COMMENT '行情来源',
  origin_label      VARCHAR(64) NULL COMMENT '取价口径标记（如 均价(3条)）',
  quote_price       DECIMAL(12,2) NULL COMMENT '网价（元/吨）',
  uplift            DECIMAL(12,2) NULL COMMENT '锁定时的上浮',
  sales_unit_price  DECIMAL(12,2) NULL COMMENT '销售单价 = 网价 + 上浮',
  pricing_quantity  DECIMAL(14,3) NULL COMMENT '计价数量（吨或件）',
  pricing_unit      VARCHAR(12) NULL COMMENT '计价单位（吨/件/个等）',
  amount            DECIMAL(14,2) NULL COMMENT '锁定金额 = 销售单价 × 计价数量',
  price_basis       VARCHAR(24) NOT NULL COMMENT 'quote=网价计算 / fallback_amount=无行情回退单据金额 / unmatched=无法计价',
  match_method      VARCHAR(16) NOT NULL COMMENT 'exact/fuzzy/range/ai/manual/unmatched',
  confidence        DECIMAL(5,2) NULL COMMENT '置信度 0-100',
  status            VARCHAR(16) NOT NULL DEFAULT 'auto' COMMENT 'auto/manual/unmatched/ai_pending',
  ai_raw            TEXT NULL COMMENT 'AI返回原文（排查用）',
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_item_net_map (document_item_id),
  INDEX idx_item_net_batch (batch_id),
  INDEX idx_item_net_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
