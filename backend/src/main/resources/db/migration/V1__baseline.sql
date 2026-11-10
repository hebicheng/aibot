CREATE TABLE users (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(64) NULL UNIQUE,
  password_hash VARCHAR(128) NULL,
  open_id VARCHAR(128) NULL UNIQUE,
  union_id VARCHAR(128) NULL,
  nickname VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(512) NULL,
  company_name VARCHAR(128) NOT NULL,
  role_name VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE projects (
  id VARCHAR(36) PRIMARY KEY,
  owner_id VARCHAR(36) NOT NULL,
  name VARCHAR(128) NOT NULL,
  customer_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  purchase_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  sales_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  profit_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  margin_rate DECIMAL(8,2) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_projects_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  INDEX idx_projects_owner_updated (owner_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE batches (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  name VARCHAR(128) NOT NULL,
  business_date DATE NULL COMMENT '批次业务日期',
  material_summary VARCHAR(256) NULL COMMENT '主要材料摘要',
  status VARCHAR(32) NOT NULL,
  progress INT NOT NULL DEFAULT 0,
  purchase_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  sales_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  profit_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  margin_rate DECIMAL(8,2) NOT NULL DEFAULT 0,
  remark VARCHAR(512) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_batches_project FOREIGN KEY (project_id) REFERENCES projects(id),
  INDEX idx_batches_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE suppliers (
  id VARCHAR(36) PRIMARY KEY,
  owner_id VARCHAR(36) NOT NULL,
  name VARCHAR(128) NOT NULL,
  rating INT NOT NULL DEFAULT 3,
  payment_terms VARCHAR(128) NOT NULL,
  delivery_days INT NOT NULL DEFAULT 3,
  risk_note VARCHAR(256) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_suppliers_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  INDEX idx_suppliers_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE documents (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  project_id VARCHAR(36) NOT NULL,
  batch_id VARCHAR(36) NOT NULL,
  doc_code VARCHAR(128) NULL COMMENT '单据编号',
  doc_type VARCHAR(32) NOT NULL COMMENT '业务资料类型',
  supplier_name VARCHAR(128) NULL,
  amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '单据总金额',
  doc_date DATE NULL COMMENT '单据日期',
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  confidence INT NULL COMMENT 'AI识别置信度 0-100',
  source VARCHAR(32) NOT NULL DEFAULT 'manual',
  file_name VARCHAR(256) NULL,
  file_url VARCHAR(512) NULL,
  file_object_key VARCHAR(512) NULL COMMENT '对象存储Key',
  source_text TEXT NULL COMMENT '手动输入原文',
  guidance_text TEXT NULL COMMENT '用户处理说明',
  file_hash VARCHAR(64) NULL COMMENT '附件SHA-256',
  business_fingerprint VARCHAR(64) NULL COMMENT '业务内容指纹',
  duplicate_of_document_id VARCHAR(36) NULL,
  duplicate_reason VARCHAR(32) NULL,
  duplicate_decision VARCHAR(16) NULL,
  target_ledger_id VARCHAR(36) NULL,
  target_document_id VARCHAR(36) NULL,
  ledger_applied BOOLEAN NOT NULL DEFAULT FALSE,
  ai_summary TEXT NULL,
  structured_data JSON NULL,
  source_message_id VARCHAR(36) NULL,
  remark VARCHAR(512) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_doc_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_doc_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_doc_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  INDEX idx_doc_batch_type (batch_id, doc_type),
  INDEX idx_doc_project (project_id),
  INDEX idx_doc_status (status),
  INDEX idx_document_file_hash (user_id, batch_id, file_hash),
  INDEX idx_document_business_fingerprint (user_id, batch_id, business_fingerprint),
  INDEX idx_document_duplicate_of (duplicate_of_document_id),
  INDEX idx_document_target_ledger (target_ledger_id),
  INDEX idx_document_target_document (target_document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE document_items (
  id VARCHAR(36) PRIMARY KEY,
  document_id VARCHAR(36) NOT NULL,
  material_name VARCHAR(128) NOT NULL,
  spec VARCHAR(128) NULL,
  package_quantity DECIMAL(18,3) NULL,
  package_unit VARCHAR(32) NULL,
  unit_weight DECIMAL(18,3) NULL,
  total_weight DECIMAL(18,3) NULL,
  weight_unit VARCHAR(32) NULL,
  pricing_quantity DECIMAL(18,3) NULL,
  pricing_unit VARCHAR(32) NULL,
  unit VARCHAR(32) NULL,
  quantity DECIMAL(18,3) NULL,
  unit_price DECIMAL(18,2) NULL,
  amount DECIMAL(18,2) NULL,
  remark VARCHAR(256) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  CONSTRAINT fk_item_doc FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
  INDEX idx_item_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE materials (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  source_document_id VARCHAR(36) NULL,
  name VARCHAR(128) NOT NULL,
  spec VARCHAR(128) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  quantity DECIMAL(18,3) NOT NULL,
  budget_unit_price DECIMAL(18,2) NOT NULL DEFAULT 0,
  selected_supplier VARCHAR(128) NULL,
  selected_quote DECIMAL(18,2) NULL,
  market_price DECIMAL(18,2) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_materials_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  INDEX idx_materials_batch (batch_id),
  INDEX idx_material_source_document (source_document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE supplier_quotes (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  material_id VARCHAR(36) NULL,
  supplier_id VARCHAR(36) NOT NULL,
  quote_price DECIMAL(18,2) NOT NULL,
  market_price_snapshot DECIMAL(18,2) NOT NULL,
  difference_rate DECIMAL(8,2) NOT NULL,
  selected BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_quotes_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  CONSTRAINT fk_quotes_material FOREIGN KEY (material_id) REFERENCES materials(id),
  CONSTRAINT fk_quotes_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  INDEX idx_quotes_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE market_prices (
  id VARCHAR(36) PRIMARY KEY,
  owner_id VARCHAR(36) NOT NULL,
  material_name VARCHAR(128) NOT NULL,
  spec VARCHAR(128) NOT NULL,
  source VARCHAR(64) NOT NULL,
  price DECIMAL(18,2) NOT NULL,
  unit VARCHAR(32) NOT NULL,
  captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_market_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  INDEX idx_market_material_time (owner_id, material_name, spec, captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_records (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  type VARCHAR(32) NOT NULL,
  target VARCHAR(128) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  status VARCHAR(32) NOT NULL,
  occurred_date DATE NOT NULL,
  source_message_id VARCHAR(36) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_finance_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  INDEX idx_finance_batch_date (batch_id, occurred_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE files (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  name VARCHAR(256) NOT NULL,
  file_type VARCHAR(32) NOT NULL,
  url VARCHAR(512) NULL,
  source VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_files_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  INDEX idx_files_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE flow_events (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  code VARCHAR(64) NOT NULL,
  title VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  event_time VARCHAR(64) NOT NULL,
  note VARCHAR(256) NOT NULL,
  detail VARCHAR(512) NULL,
  sort_order INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_flow_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  INDEX idx_flow_batch_order (batch_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_messages (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  project_id VARCHAR(36) NULL,
  batch_id VARCHAR(36) NULL,
  sender VARCHAR(16) NOT NULL,
  message_type VARCHAR(32) NOT NULL,
  business_type VARCHAR(64) NOT NULL,
  content TEXT NOT NULL,
  file_name VARCHAR(256) NULL,
  status VARCHAR(32) NOT NULL,
  payload JSON NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_chat_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_chat_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  INDEX idx_chat_context_time (user_id, project_id, batch_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE operation_logs (
  id VARCHAR(36) PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  project_id VARCHAR(36) NULL,
  batch_id VARCHAR(36) NULL,
  action VARCHAR(64) NOT NULL,
  content VARCHAR(512) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_logs_user FOREIGN KEY (user_id) REFERENCES users(id),
  INDEX idx_logs_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger_records (
  id VARCHAR(36) PRIMARY KEY,
  project_id VARCHAR(36) NOT NULL,
  batch_id VARCHAR(36) NOT NULL,
  source_document_id VARCHAR(36) NULL,
  purchase_date DATE NULL,
  material VARCHAR(256) NULL,
  supplier VARCHAR(128) NULL,
  purchase_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  freight DECIMAL(18,2) NOT NULL DEFAULT 0,
  delivery_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  delivered_package_quantity DECIMAL(18,3) NOT NULL DEFAULT 0,
  delivered_package_unit VARCHAR(32) NULL,
  delivered_weight DECIMAL(18,3) NOT NULL DEFAULT 0,
  delivered_weight_unit VARCHAR(32) NULL,
  requested_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  paid_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  unpaid_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  invoice_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  tax_rate VARCHAR(16) NULL,
  sales_order VARCHAR(128) NULL,
  sales_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  sales_invoice_date DATE NULL,
  sales_invoice_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  sales_tax_rate VARCHAR(16) NULL,
  received_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  outstanding DECIMAL(18,2) NOT NULL DEFAULT 0,
  receipt_date DATE NULL,
  required_receipt_date DATE NULL,
  gross_profit DECIMAL(18,2) NULL COMMENT '系统自动计算',
  gross_margin DECIMAL(8,2) NULL COMMENT '系统自动计算',
  project_manager VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ledger_project FOREIGN KEY (project_id) REFERENCES projects(id),
  CONSTRAINT fk_ledger_batch FOREIGN KEY (batch_id) REFERENCES batches(id),
  INDEX idx_ledger_batch (batch_id),
  INDEX idx_ledger_project (project_id),
  UNIQUE INDEX uk_ledger_source_document (source_document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO users (
  id, username, password_hash, open_id, nickname, company_name, role_name
) VALUES (
  'dev_admin',
  'admin',
  '$2a$10$bZbPfuU4xN19CMNM1mhK1eR3cJKodxhgvQFuiBi6QB5q69BVB0NvC',
  NULL,
  '管理员',
  '杭州葭安科技有限公司',
  '管理员'
);
