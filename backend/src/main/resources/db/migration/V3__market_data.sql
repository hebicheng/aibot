-- 市场行情模块：产品字典 / 行情明细 / 材料映射 / 爬取日志

-- ① 行情产品字典
CREATE TABLE market_products (
  id          VARCHAR(36) PRIMARY KEY,
  code        VARCHAR(16)  NOT NULL COMMENT '站点产品ID，如 144',
  category    VARCHAR(32)  NOT NULL COMMENT '分类：建材/不锈钢/型钢/钢管',
  name        VARCHAR(64)  NOT NULL COMMENT '产品名：螺纹钢',
  source_url  VARCHAR(256) NOT NULL COMMENT '汇总页URL',
  is_nav      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否分类导航页（需二级爬取）',
  sort_order  INT          NOT NULL DEFAULT 0,
  enabled     TINYINT      NOT NULL DEFAULT 1,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_market_products_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO market_products (id, code, category, name, source_url, is_nav, sort_order) VALUES
('mp_144','144','建材','螺纹钢','https://jiage.cdgtw.net/huizong144/',0,10),
('mp_143','143','建材','盘螺','https://jiage.cdgtw.net/huizong143/',0,20),
('mp_145','145','建材','高线','https://jiage.cdgtw.net/huizong145/',0,30),
('mp_140','140','建材','圆钢','https://jiage.cdgtw.net/huizong140/',0,40),
('mp_180','180','建材','涂镀','https://jiage.cdgtw.net/huizong180/',0,50),
('mp_203','203','建材','中厚板','https://jiage.cdgtw.net/huizong203/',0,60),
('mp_160','160','建材','花纹板','https://jiage.cdgtw.net/huizong160/',0,70),
('mp_150','150','建材','冷轧卷','https://jiage.cdgtw.net/huizong150/',0,80),
('mp_245','245','不锈钢','不锈钢','https://jiage.cdgtw.net/huizong245/',1,110),
('mp_345','345','不锈钢','钢坯','https://jiage.cdgtw.net/huizong345/',1,120),
('mp_372','372','不锈钢','铁矿石','https://jiage.cdgtw.net/huizong372/',1,130),
('mp_296','296','不锈钢','废钢','https://jiage.cdgtw.net/huizong296/',0,140),
('mp_210','210','不锈钢','带钢','https://jiage.cdgtw.net/huizong210/',0,150),
('mp_348','348','不锈钢','铁合金','https://jiage.cdgtw.net/huizong348/',1,160),
('mp_211','211','不锈钢','结构钢','https://jiage.cdgtw.net/huizong211/',1,170),
('mp_186','186','型钢','工字钢','https://jiage.cdgtw.net/huizong186/',0,210),
('mp_183','183','型钢','H型钢','https://jiage.cdgtw.net/huizong183/',0,220),
('mp_189','189','型钢','槽钢','https://jiage.cdgtw.net/huizong189/',0,230),
('mp_193','193','型钢','角钢','https://jiage.cdgtw.net/huizong193/',0,240),
('mp_168','168','钢管','焊管','https://jiage.cdgtw.net/huizong168/',0,310),
('mp_173','173','钢管','镀锌管','https://jiage.cdgtw.net/huizong173/',0,320),
('mp_163','163','钢管','无缝管','https://jiage.cdgtw.net/huizong163/',0,330),
('mp_170','170','钢管','螺旋管','https://jiage.cdgtw.net/huizong170/',0,340),
('mp_162','162','钢管','方矩管','https://jiage.cdgtw.net/huizong162/',0,350);

-- ② 行情明细（核心表）
CREATE TABLE market_quotes (
  id            VARCHAR(36) PRIMARY KEY,
  quote_date    DATE         NOT NULL COMMENT '行情归属日（网页日期）',
  product_code  VARCHAR(16)  NOT NULL COMMENT '产品code',
  product_name  VARCHAR(64)  NOT NULL COMMENT '冗余产品名',
  spec_name     VARCHAR(128) NOT NULL COMMENT '规格+材质原文，如 Φ10 HRB400E',
  spec          VARCHAR(64)  NULL COMMENT '规格，如 Φ10 / Φ108*4',
  grade         VARCHAR(64)  NULL COMMENT '材质，如 HRB400E / 20#',
  city          VARCHAR(32)  NOT NULL COMMENT '城市',
  price         DECIMAL(12,2) NULL COMMENT '价格（元/吨），空=当日该城市无报价',
  price_change  DECIMAL(12,2) NULL COMMENT '前日价差（直接爬取，不计算）',
  detail_url    VARCHAR(256) NULL COMMENT '详情页URL',
  captured_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  UNIQUE KEY uk_market_quote (quote_date, product_code, spec_name, city),
  INDEX idx_quotes_date_product (quote_date, product_code),
  INDEX idx_quotes_city_date (city, quote_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ③ 材料 ↔ 行情映射（AI 匹配结果落库）
CREATE TABLE material_market_mappings (
  id              VARCHAR(36) PRIMARY KEY,
  material_id     VARCHAR(36) NOT NULL COMMENT '业务材料 materials.id',
  batch_id        VARCHAR(36) NOT NULL,
  product_code    VARCHAR(16) NULL COMMENT '匹配到的行情产品code',
  spec_name       VARCHAR(128) NULL COMMENT '匹配到的行情规格+材质原文',
  city            VARCHAR(32) NULL COMMENT '对比城市（默认公司所在地）',
  match_method    VARCHAR(16) NOT NULL COMMENT 'exact/rule/fuzzy/ai/manual/unmatched',
  confidence      DECIMAL(5,2) NULL COMMENT '置信度 0-100',
  status          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/auto/manual/unmatched',
  matched_at      TIMESTAMP NULL,
  ai_raw          TEXT NULL COMMENT 'AI返回原文（排查用）',
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_material_map (material_id),
  INDEX idx_mapping_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ④ 爬取日志（运维可观测）
CREATE TABLE market_crawl_logs (
  id           VARCHAR(36) PRIMARY KEY,
  run_start    TIMESTAMP NOT NULL,
  run_end      TIMESTAMP NULL,
  status       VARCHAR(16) NOT NULL COMMENT 'success/partial/failed',
  product_code VARCHAR(16) NULL,
  quote_date   DATE NULL,
  rows_found   INT NOT NULL DEFAULT 0,
  rows_upserted INT NOT NULL DEFAULT 0,
  rows_skipped INT NOT NULL DEFAULT 0,
  error_msg    VARCHAR(512) NULL,
  INDEX idx_crawl_time (run_start),
  INDEX idx_crawl_product (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
