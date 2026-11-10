-- V23: 材料写法映射（AI 判同的持久化，2026-10-29）
--
-- 背景：V22 把材料抽成实体，但"两条写法是不是同一种材料"仍靠正则 + 组内唯一值回填，
-- 覆盖不了别名/简称/钢厂习惯叫法（「中天」vs「中天钢铁」、「三级螺纹钢」vs「HRB400」）。
--
-- 本表把 AI 的判定结果固化成"写法 → 材料身份"的映射：
--   raw_key = 单据上实际写法的规范化文本（材料名|规格|产地，大写去空格）
--   五维    = 归一后的 category/grade/diameter/length_m/origin
--
-- 作用有三：
--   1. rebuild 时优先查本表，AI 的判定不会被规则重算冲掉；
--   2. 同一批次同样的写法只问一次 AI，是"不重复计费"的依据；
--   3. 可解释：能查到这个材料是从哪种写法归过来的。
--
-- 作用域是批次：不同批次可以有不同的写法习惯，互不干扰。
-- 与 batch_materials 的关系：本表是"输入 → 身份"的映射，batch_materials 是身份的唯一实体。

CREATE TABLE batch_material_aliases (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL COMMENT '所属批次',
  raw_key VARCHAR(255) NOT NULL COMMENT '单据写法规范化文本：材料名|规格|产地',
  category VARCHAR(32) NOT NULL COMMENT 'AI 归出的大类：螺纹钢/盘螺/高线/圆钢',
  grade VARCHAR(32) NULL COMMENT '牌号，未知为 NULL',
  diameter VARCHAR(16) NULL COMMENT '直径（mm），未知为 NULL',
  length_m VARCHAR(16) NULL COMMENT '定尺（米），非定尺类为 NULL',
  origin VARCHAR(64) NULL COMMENT '产地/钢厂，未知为 NULL',
  canonical_name VARCHAR(255) NULL COMMENT 'AI 给出的规范写法（人读，便于核对）',
  source VARCHAR(16) NOT NULL DEFAULT 'ai' COMMENT '来源：ai / rule（降级）',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_alias_raw (batch_id, raw_key),
  INDEX idx_alias_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='材料写法 → 材料身份的映射（AI 判同结果，rebuild 时优先于规则）';
