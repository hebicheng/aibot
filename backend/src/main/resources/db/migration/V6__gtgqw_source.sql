-- 行情数据源适配：接入 gtgqw（钢易网）
-- 背景：原数据源 cdgtw（创大钢铁 jiage.cdgtw.net）页面已无数据；gtgqw 为文章制
--       （一篇 = 一城市 × 一产品，表格行含 品名/规格/材质/产地/价格/涨跌）。
-- 变更：
--   1. market_products 增加 source（数据源），唯一键改为 (source, code)；
--      螺纹钢144/盘螺143/高线145 切到 gtgqw 发现入口，其余 cdgtw 产品停用。
--   2. market_quotes 增加 source + origin（产地/钢厂），唯一键含二者
--      （gtgqw 同城市同产品同规格存在多个产地；mysteel 亦有钢厂维度）。
--   3. material_market_mappings 增加 source + origin（记录匹配命中的源与产地）。
--   4. 删除遗留死表 market_prices（旧手动录入来源，全项目无调用方）。

-- ① market_products：数据源维度
ALTER TABLE market_products
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'gtgqw' COMMENT '数据源：gtgqw/mysteel' AFTER code,
  DROP INDEX uk_market_products_code,
  ADD UNIQUE KEY uk_market_products_source_code (source, code);

-- 停用 cdgtw 其它产品（保留历史行，前端 enabled=false 自动不展示）
UPDATE market_products
SET enabled = 0
WHERE source = 'gtgqw' AND code NOT IN ('144', '143', '145');

-- 螺纹钢/盘螺/高线：数据源切 gtgqw。gtgqw 无独立产品 id，source_url 指向发现入口
-- （三类产品的报价同在一篇「杭州螺纹钢」文章内，统一指向螺纹钢频道页）。
UPDATE market_products
SET source_url = 'http://www.gtgqw.com/luowengang/'
WHERE source = 'gtgqw' AND code IN ('144', '143', '145');

-- ② market_quotes：数据源 + 产地维度
ALTER TABLE market_quotes
  ADD COLUMN origin VARCHAR(64) NOT NULL DEFAULT '' COMMENT '产地/钢厂（gtgqw 产地列；mysteel 钢厂）' AFTER grade,
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'gtgqw' COMMENT '数据源：gtgqw/mysteel' AFTER product_code,
  DROP INDEX uk_market_quote,
  ADD UNIQUE KEY uk_market_quote (quote_date, source, product_code, spec_name, city, origin);

-- ③ 材料 ↔ 行情映射：记录命中的源与产地
ALTER TABLE material_market_mappings
  ADD COLUMN source VARCHAR(16) NULL COMMENT '匹配命中的数据源' AFTER spec_name,
  ADD COLUMN origin VARCHAR(64) NULL COMMENT '匹配命中的产地/钢厂' AFTER source;

-- ④ 清理遗留死表（旧手动录入来源，仅 ManualMarketPriceProvider 引用且无任何调用方）
DROP TABLE IF EXISTS market_prices;
