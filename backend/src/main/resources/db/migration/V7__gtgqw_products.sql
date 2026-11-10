-- 播种 gtgqw 产品字典（幂等：按唯一键 (source, code) 忽略已存在行）。
-- 背景：V6 将 144/143/145 的 source 切为 gtgqw，但部分环境的 market_products 可能被清空，
--       此处确保螺纹钢/盘螺/高线三产品始终存在，source_url 指向 gtgqw 螺纹钢频道（发现入口，
--       三类产品报价同在一篇「杭州螺纹钢」文章内）。
INSERT IGNORE INTO market_products (id, code, source, category, name, source_url, is_nav, sort_order, enabled) VALUES
('mp_144', '144', 'gtgqw', '建材', '螺纹钢', 'http://www.gtgqw.com/luowengang/', 0, 10, 1),
('mp_143', '143', 'gtgqw', '建材', '盘螺',   'http://www.gtgqw.com/luowengang/', 0, 20, 1),
('mp_145', '145', 'gtgqw', '建材', '高线',   'http://www.gtgqw.com/luowengang/', 0, 30, 1);
