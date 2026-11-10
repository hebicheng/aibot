-- V26: 材料层转正（2026-11-04）
--
-- batch_materials.status 的 'shadow'（只写不读的影子期）语义过时：
-- P2 消费切换起锁价/成本/台账已直接消费材料层（quote_* 绑定、supply_item_id），
-- 材料行是生产数据，不再是影子。默认值改 'active'，存量行一并更新。
-- match_level / match_source 不受影响（归并质量与来源仍有意义）。

ALTER TABLE batch_materials
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active'
  COMMENT 'active=生产态（材料层为取价权威）。原影子期语义已废弃';

UPDATE batch_materials SET status = 'active' WHERE status = 'shadow';
