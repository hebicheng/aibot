-- 材料人工网价（2026-11-12）：
-- 「批次材料 · 改价」改为只改本材料/本批次的网价基准，不再往 market_quotes 写一条 mysteel 权威行。
-- 原因：行情库是全局的，写进去会改掉所有批次同产品同规格同产地的取价，且同一规格被多条材料共用时
-- （如盘螺 8 与 10 都绑 Φ8-10）改一条会串到另一条。行情库的改价入口保留在「行情」Tab（改市场行情）。
--
-- 取价优先级（新增一层，最高）：manual_quote_price > mysteel 权威行 > gtgqw 爬取行（同城市、同产地优先）。
ALTER TABLE batch_materials
  ADD COLUMN manual_quote_price DECIMAL(12, 2) NULL
  COMMENT '人工网价（元/吨）：非空时优先于行情库取价；只影响本材料/本批次，可从批次材料「改价」清空恢复' 
  AFTER manual_sales_price;
