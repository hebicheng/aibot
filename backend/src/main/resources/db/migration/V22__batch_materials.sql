-- V22: 材料实体层（2026-10-29，P0 影子模式）
--
-- 背景：采购单（找钢网销售单 / 提货单 / 供应商对账单）与送货单对同一种材料的写法各不相同
-- （「三级螺纹钢 HRB400 12*9」/「12螺纹钢 9m」/「螺纹钢HRB400 12*9」），但业务上是同一种材料。
-- 系统此前只能按单据行各自匹配网价、各自算价，同一个材料在采购侧和送货侧是两条互不相干的记录。
--
-- 本表把"材料"抽成实体：一个批次内一种材料一行，采购行与送货行都挂到它上面。
-- 价格的三个层次严格分开：
--   参数（输入）   → quote_* 绑定、pricing_mode / uplift_amount / margin_rate / manual_sales_price
--   事实（输入）   → 采购吨数金额、送货吨数、运费（不落本表，来自单据）
--   结果（输出）   → 估算售价、毛利额、毛利率（一律读时算，绝不落库）
--
-- 身份键分两级，用于解决「采购单不写牌号/产地、送货单写了」的常见情况：
--   base_key     = category|diameter|length_m          粗键，先按规格归堆
--   identity_key = category|grade|diameter|length_m|origin  细键，组内唯一非空值回填后定型
-- 组内出现两个不同牌号或产地时不做合并，按细键各自独立并标记，宁可分裂也不猜。
--
-- 定尺（length_m）只对定尺类材料（螺纹钢/圆钢）有意义；盘螺/高线/盘圆成盘供货，恒为 NULL。
-- 抽不到定尺时留 NULL，不设默认值。
--
-- 历史批次不回填：本表先以影子模式运行（只写不读），存量数据不动。

ALTER TABLE document_items
  ADD COLUMN material_id VARCHAR(36) NULL COMMENT '归并到的批次材料（batch_materials.id），NULL=未归并' AFTER spec,
  ADD COLUMN material_match_level VARCHAR(24) NULL COMMENT 'exact=五维齐全 / inferred=牌号或产地由组内唯一值反推 / pending=未归并' AFTER material_id,
  ADD COLUMN material_match_source VARCHAR(16) NULL COMMENT 'rule=规则 / ai=AI 兜底 / manual=人工指定' AFTER material_match_level,
  ADD COLUMN length_m VARCHAR(16) NULL COMMENT '定尺长度（米，如 9 / 12）；非定尺类与未标注为 NULL' AFTER material_match_source,
  ADD COLUMN measure_method VARCHAR(16) NULL COMMENT '计量方式：理计/抄码/过磅。影响吨数口径，不参与材料身份' AFTER length_m;

CREATE TABLE batch_materials (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL COMMENT '所属批次',
  category VARCHAR(32) NOT NULL COMMENT '材料大类：螺纹钢/盘螺/高线/圆钢',
  grade VARCHAR(32) NULL COMMENT '牌号：HRB400 / HRB400E / HPB300，未知为 NULL',
  diameter VARCHAR(16) NULL COMMENT '直径（mm）：12 / 6.5，未知为 NULL',
  length_m VARCHAR(16) NULL COMMENT '定尺长度（米）：9 / 12；盘螺高线盘圆恒 NULL',
  origin VARCHAR(64) NULL COMMENT '产地/钢厂/品牌原文（中天、沙钢），未知为 NULL',
  base_key VARCHAR(96) NOT NULL COMMENT '粗键 category|diameter|length_m',
  identity_key VARCHAR(192) NOT NULL COMMENT '细键 category|grade|diameter|length_m|origin',
  quote_date DATE NULL COMMENT '取价日：以采购侧最早单据日期为准，送货单继承',
  quote_product_code VARCHAR(64) NULL COMMENT '网价绑定：行情产品码（一条绑定，采购/送货共享）',
  quote_spec_name VARCHAR(64) NULL COMMENT '网价绑定：行情规格名',
  quote_origin VARCHAR(64) NULL COMMENT '网价绑定：行情产地名',
  pricing_mode VARCHAR(16) NULL COMMENT '定价方式：uplift=网价+上浮 / margin=成本÷(1−r)，NULL=按批次默认口径',
  uplift_amount DECIMAL(12,2) NULL COMMENT '上浮金额（元/吨），仅 uplift 口径',
  margin_rate DECIMAL(8,4) NULL COMMENT '毛利率点数（如 2.5 表示 2.5%），仅 margin 口径',
  manual_sales_price DECIMAL(12,2) NULL COMMENT '人工指定销售单价（元/吨），非空时优先于口径推算',
  supply_item_id VARCHAR(36) NULL COMMENT '对应的采购明细行（同材料同日只有一个价，故唯一）',
  match_level VARCHAR(24) NULL COMMENT '归并质量：exact / inferred / pending',
  match_source VARCHAR(16) NULL COMMENT '归并来源：rule / ai / manual',
  status VARCHAR(16) NOT NULL DEFAULT 'shadow' COMMENT 'shadow=影子期（只写不读）',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_batch_material (batch_id, identity_key),
  INDEX idx_bm_batch (batch_id),
  INDEX idx_bm_supply_item (supply_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='批次材料实体（一个批次一种材料一行，采购行与送货行的汇聚点）';
