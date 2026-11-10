-- V25: 明细行的材料归属查询索引（2026-10-30）
--
-- 用途：`document_items.material_id` 是"材料 → 明细行"的唯一入口，以下场景都在按它查：
--   1. 材料级绑定同步（DeliveryNetPriceService.manualBindByMaterial）；
--   2. 材料清单聚合（MarketQueryService.materialList 读取材料下的全部行）；
--   3. 排查"这个材料下到底有哪些单据行"。
-- 明细行是库里最大的表之一，没有索引就是全表扫描；V22 建列时漏了索引，这里补上。
--
-- 允许 NULL（未归并的行），MySQL 二级索引不索引 NULL，索引体积不受未归并行影响。

ALTER TABLE document_items
  ADD INDEX idx_di_material (material_id);
