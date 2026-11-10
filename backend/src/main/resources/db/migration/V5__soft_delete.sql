-- 项目/批次软删除支持：
-- 1. projects / batches 增加 deleted 标记，删除即置 1，业务查询自动过滤（MyBatis-Plus @TableLogic）。
-- 2. 底层业务数据（单据/台账/材料/财务/附件/聊天等）物理保留，仅从业务视图隐藏，可审计、可恢复。
ALTER TABLE projects
  ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记：0=正常 1=已删除' AFTER updated_at,
  ADD INDEX idx_projects_owner_deleted (owner_id, deleted);

ALTER TABLE batches
  ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记：0=正常 1=已删除' AFTER updated_at,
  ADD INDEX idx_batches_project_deleted (project_id, deleted);
