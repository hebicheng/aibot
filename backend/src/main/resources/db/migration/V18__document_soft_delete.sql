-- 单据软删除支持（数据中心删除入口）：
-- 对齐项目/批次的软删模式（V5）：documents 增加 deleted 标记，
-- 删除即置 1，业务查询自动过滤（MyBatis-Plus @TableLogic）。
-- 底层业务数据（明细行/锁定行情映射/附件/聊天卡片）物理保留，可审计、可恢复；
-- AI 网价兜底、重复检测、费用关联候选等经 selectById/列表查询的路径自动放行已删单据。
ALTER TABLE documents
  ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记：0=正常 1=已删除' AFTER updated_at,
  ADD INDEX idx_documents_batch_deleted (batch_id, deleted);
