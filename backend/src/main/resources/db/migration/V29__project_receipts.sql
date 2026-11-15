-- V29: 项目级收款 + 多批次关联（2026-11-15）
--
-- 一笔客户回款可以对应同一项目下的多个批次（按销售顺序收款：如 3 万对应批次 1/2/3，5 万对应批次 4/5）。
--   project_receipts             收款主表：一行一笔回款（金额可改）
--   project_receipt_allocations  收款 ↔ 批次分摊：每个关联批次分到多少钱，Σ分摊 = 收款金额
-- 批次「已收款」= Σ 分摊金额，由 ReceiptService 统一维护并同步到台账 received_amount。
-- 智能助理确认的收款单（documents.doc_type='receipt'）自动生成一笔收款，source_document_id 指回单据。

CREATE TABLE project_receipts (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  receipt_date DATE NOT NULL COMMENT '收款日期',
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '收款金额',
  payer VARCHAR(128) NULL COMMENT '付款单位',
  remark VARCHAR(512) NULL,
  source_document_id VARCHAR(36) NULL COMMENT '来源收款单据（智能助理识别）',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  CONSTRAINT fk_receipt_project FOREIGN KEY (project_id) REFERENCES projects(id),
  INDEX idx_receipt_project (project_id, deleted, receipt_date),
  INDEX idx_receipt_source_document (source_document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_receipt_allocations (
  id VARCHAR(36) PRIMARY KEY,
  receipt_id VARCHAR(36) NOT NULL,
  project_id VARCHAR(36) NOT NULL,
  batch_id VARCHAR(36) NOT NULL,
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '本批次分到的收款金额',
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_alloc_receipt FOREIGN KEY (receipt_id) REFERENCES project_receipts(id),
  CONSTRAINT fk_alloc_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  INDEX idx_alloc_receipt (receipt_id),
  INDEX idx_alloc_batch (batch_id),
  INDEX idx_alloc_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE batches
  ADD COLUMN reconciled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已对账：人工勾选' AFTER remark;

-- 历史收款单迁移：每张已确认收款单 → 一笔收款 + 关联其所在批次
INSERT INTO project_receipts (id, project_id, receipt_date, amount, payer, remark, source_document_id, created_at)
SELECT UUID(), d.project_id, COALESCE(d.doc_date, DATE(d.created_at)), d.amount, d.supplier_name,
       '历史收款单迁移', d.id, d.created_at
FROM documents d
WHERE d.doc_type = 'receipt' AND d.status = 'matched' AND d.deleted = 0;

INSERT INTO project_receipt_allocations (id, receipt_id, project_id, batch_id, amount, sort_order)
SELECT UUID(), r.id, r.project_id, d.batch_id, r.amount, 0
FROM project_receipts r
JOIN documents d ON d.id = r.source_document_id;
