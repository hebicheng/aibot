# 市场行情模块 — 开发计划

> 项目：衡账 AI 项目对账平台（AI-BOT）
> 日期：2026-08-01
> 范围：爬虫采集入库 + 业务系统市场价格展示页（双 Tab）+ 材料与行情 AI 匹配

---

## 1. 需求理解

新增「市场行情」模块：

1. **爬虫**：每天每小时爬取创大钢铁行情数据（含价格与前日涨跌）入库；同一日期的数据不重复存储（幂等 upsert）。
2. **展示页面（web 端）**，两个 Tab：
   - **Tab1 项目对比**：项目 → 分批次 → 批次下材料的价格与市场行情价格并列展示、算出差价/差异率。
   - **Tab2 全量行情**：展示最新一日的全量市场价格（含前日价差，价差直接用爬取值，不自己计算）。
3. **AI 材料匹配**：业务侧材料名称/规格与行情侧名称/规格表述不一致（如「三级抗震螺纹钢 HRB400E Φ25」vs「螺纹钢 Φ25 HRB400E」），需智能匹配；时机为**供货单进入（确认回填台账）时对每条材料匹配一次并落库记录映射**，页面查询不再调用 AI。
4. **UI**：符合现有系统风格（浅色、留白、蓝主色 #2f66f6、圆角卡片），精致美观。

---

## 2. 数据源梳理（创大钢铁 jiage.cdgtw.net）

### 2.1 产品链接清单（汇总页，已逐一验证）

汇总页 URL 模式：`https://jiage.cdgtw.net/huizong{产品ID}/`

| 分类 | 产品 | 链接 |
|---|---|---|
| 建材 | 螺纹钢 | https://jiage.cdgtw.net/huizong144/ |
| 建材 | 盘螺 | https://jiage.cdgtw.net/huizong143/ |
| 建材 | 高线 | https://jiage.cdgtw.net/huizong145/ |
| 建材 | 圆钢 | https://jiage.cdgtw.net/huizong140/ |
| 建材 | 涂镀 | https://jiage.cdgtw.net/huizong180/ |
| 建材 | 中厚板 | https://jiage.cdgtw.net/huizong203/ |
| 建材 | 花纹板 | https://jiage.cdgtw.net/huizong160/ |
| 建材 | 冷轧卷 | https://jiage.cdgtw.net/huizong150/ |
| 不锈钢 | 不锈钢 | https://jiage.cdgtw.net/huizong245/ |
| 不锈钢 | 钢坯 | https://jiage.cdgtw.net/huizong345/ |
| 不锈钢 | 铁矿石 | https://jiage.cdgtw.net/huizong372/ |
| 不锈钢 | 废钢 | https://jiage.cdgtw.net/huizong296/ |
| 不锈钢 | 带钢 | https://jiage.cdgtw.net/huizong210/ |
| 不锈钢 | 铁合金 | https://jiage.cdgtw.net/huizong348/ |
| 不锈钢 | 结构钢 | https://jiage.cdgtw.net/huizong211/ |
| 型钢 | 工字钢 | https://jiage.cdgtw.net/huizong186/ |
| 型钢 | H型钢 | https://jiage.cdgtw.net/huizong183/ |
| 型钢 | 槽钢 | https://jiage.cdgtw.net/huizong189/ |
| 型钢 | 角钢 | https://jiage.cdgtw.net/huizong193/ |
| 钢管 | 焊管 | https://jiage.cdgtw.net/huizong168/ |
| 钢管 | 镀锌管 | https://jiage.cdgtw.net/huizong173/ |
| 钢管 | 无缝管 | https://jiage.cdgtw.net/huizong163/ |
| 钢管 | 螺旋管 | https://jiage.cdgtw.net/huizong170/ |
| 钢管 | 方矩管 | https://jiage.cdgtw.net/huizong162/ |

> 注意：「板材」是分类名（无独立汇总页），其子项为 涂镀/中厚板/花纹板/冷轧卷，已有独立页面。
> **5 个分类页是导航页（无主表数据）**：不锈钢(245)、钢坯(345)、铁矿石(372)、铁合金(348)、结构钢(211) 页面本身只有产品导航和子产品清单，没有价格表格。价格在**子产品页**（如不锈钢下 201/NO1卷板、304/2B卷板 等约 20 个品种；钢坯下方坯、板坯、热轧管坯 等；钢坯的子页面已实测有数据）。爬虫需对这些分类做**二级爬取**：先抓分类页提取子产品清单 → 再抓各子产品页。
> 另有一批次要产品页（冷轧带肋钢筋 139、普线 141、校直圆钢 142、焊接钢筋网片 349、精轧螺纹钢 350、钢筋 408、高强螺纹钢 495、高强盘螺 496、耐蚀钢筋 499），本期可不爬，留作扩展。

### 2.2 数据规模实测（2026-07-31 全量抓取统计）

| 分类 | 产品 | 规格行数 | 城市列数 | 分类 | 产品 | 规格行数 | 城市列数 |
|---|---|---|---|---|---|---|---|
| 建材 | 螺纹钢 144 | 39 | 23 | 型钢 | 工字钢 186 | 16 | 23 |
| 建材 | 盘螺 143 | 12 | 23 | 型钢 | H型钢 183 | 41 | 20 |
| 建材 | 高线 145 | 13 | 23 | 型钢 | 槽钢 189 | 16 | 23 |
| 建材 | 圆钢 140 | 19 | 7 | 型钢 | 角钢 193 | 33 | 23 |
| 建材 | 涂镀 180 | 69 | 18 | 钢管 | 焊管 168 | 26 | 21 |
| 建材 | 中厚板 203 | 33 | 20 | 钢管 | 镀锌管 173 | 31 | 23 |
| 建材 | 花纹板 160 | 43 | 13 | 钢管 | 无缝管 163 | 94 | 20 |
| 建材 | 冷轧卷 150 | 41 | 21 | 钢管 | 螺旋管 170 | 105 | 16 |
| 不锈钢 | 废钢 296 | 2 | 9 | 钢管 | 方矩管 162 | 86 | 15 |
| 不锈钢 | 带钢 210 | 119 | 8 | 不锈钢 | 不锈钢 245 | 导航页（子产品约20个） | - |
| 不锈钢 | 钢坯 345 | 导航页（子产品约12个） | - | 不锈钢 | 铁矿石 372 | 导航页 | - |
| 不锈钢 | 铁合金 348 | 导航页 | - | 不锈钢 | 结构钢 211 | 导航页 | - |

**关键数字（重要）**：
- **「产品×规格」候选集 ≈ 838 条规格**（19 个标准表格产品合计；不含城市维度）。加上 5 个分类下的子产品（不锈钢约 20 个品种、钢坯约 12 个品种等），总候选规格预计在 **1000~1500 条** 量级 —— 这就是 **AI 匹配的候选全集**，不需要也不会到「万」级。
- 「市场行情表按 23 城市展开」后的**存储行数** = 各产品规格行 × 城市列数 ≈ **1.4 万行/日**（含空单元格，空值不重复存）。**注意：这是存储量，不是 AI 匹配候选量**，两者不要混淆。

> 修正说明：上一版文档写「AI 不给全库 1.6 万行」表述不准确——AI 匹配候选集是「产品×规格」约 838~1500 条（几百到一千多），而「城市展开后的约 1.4 万行」只是数据库存储粒度。

### 2.2 汇总页 HTML 结构（已实测验证）

- **表头行**：`<th class="first">产品名</th>` + 23 个城市 `<th>`（北京、长沙、成都、大连、福州、广州、哈尔滨、海口、杭州、合肥、济南、昆明、南昌、南京、上海、沈阳、石家庄、太原、天津、武汉、西安、郑州、重庆）。
- **数据行**：
  - 第一列 `<td class="first">`：`品名 <br> 规格 <br> 材质`（如 `螺纹钢 <br> Φ10 <br> HRB400E`；无缝管类如 `流体管 <br> Φ108*4 <br> 20#（GB/T8163`；型钢如 `H型钢 <br> 100*100*6*8 <br> Q235B`）。
  - 每城市列 `<td class="green|red|...">`：`<a href="详情页URL">价格<br>涨跌</a>`，部分城市为空（无报价）；涨跌可能为 `-20`、`+10` 或 `-`（无变化）。
  - 涨跌颜色：`class="green"` = 跌（数值为负），`red` = 涨（需在爬虫中验证），`-` = 平。
- **页面日期**：页面标题区 `2026-07-31`，以该日期为行情归属日（数据基准日 = 网页日期，而非爬取时刻）。

### 2.3 详情页 HTML 结构（可选扩展）

每个城市价格格子的 `<a>` 指向详情页 `https://jiage.cdgtw.net/j/b{...}.html`，字段：**品名 / 规格 / 材质 / 产地(钢厂) / 价格（元/吨）/ 涨跌 / 备注**，并含「去年同期」「同比涨跌」「价差」等。本期 Tab2 只需要汇总页数据（价格+前日价差），详情页可在二期用于「按钢厂/产地对比」与「走势图」。

---

## 3. 数据模型设计（Flyway V3 迁移）

### 3.1 新表

**① market_products — 行情产品字典（静态配置，可代码枚举 + 建表冗余）**

```sql
CREATE TABLE market_products (
  id          VARCHAR(36) PRIMARY KEY,
  code        VARCHAR(16)  NOT NULL COMMENT '站点产品ID，如 144',
  category    VARCHAR(32)  NOT NULL COMMENT '分类：建材/不锈钢/型钢/钢管',
  name        VARCHAR(64)  NOT NULL COMMENT '产品名：螺纹钢',
  source_url  VARCHAR(256) NOT NULL COMMENT '汇总页URL',
  sort_order  INT          NOT NULL DEFAULT 0,
  enabled     TINYINT      NOT NULL DEFAULT 1,
  UNIQUE KEY uk_market_products_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**② market_quotes — 行情明细（核心表）**

```sql
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
  change        DECIMAL(12,2) NULL COMMENT '前日价差（直接爬取，不计算）',
  detail_url    VARCHAR(256) NULL COMMENT '详情页URL',
  captured_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  UNIQUE KEY uk_market_quote (quote_date, product_code, spec_name, city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- **幂等关键**：`uk_market_quote` 唯一键，爬虫用 `INSERT ... ON DUPLICATE KEY UPDATE price=..., change=...` 或先查后更，实现「同日期不重复存储、有变化则更新当日价格」。
- **索引**：`idx_quotes_date_product (quote_date, product_code)`、`idx_quotes_city_date (city, quote_date)`。
- 数据量预估：19 个标准产品规格行 838 行 + 5 个分类子产品页，按城市展开约 1.4 万格/日（空值不存），一年约 500 万行，MySQL 可承受；预留按月分区或归档策略（见 §8 优化点）。

**③ material_market_mappings — 材料↔行情映射（AI 匹配结果落库）**

```sql
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
  UNIQUE KEY uk_material_map (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> 一材料一行（最新映射），人工修正覆盖写。历史匹配过程可另存 `material_match_history`（可选）。

### 3.2 现有表适配

- `materials.market_price` 字段保留：回填「对比城市当日市场价」快照（人工可覆盖），页面对比优先取 mappings + market_quotes 实时价，展示两列（市场价快照 vs 最新价）。
- 现有 `market_prices` 表（手动录入来源）**保留不删**：作为 `MarketPriceProvider` 的降级来源；新增爬虫来源实现（见 §5）。

---

## 4. 爬虫设计（backend）

### 4.1 调度

- 新增 `@EnableScheduling`，`MarketCrawlerScheduler`：
  - `@Scheduled(cron = "0 5 * * * ?")` 每小时第 5 分钟执行一次。
  - 启动时（`ApplicationRunner`）先跑一次全量，保证库里有当日数据。
- 单实例运行即可（本项目单体部署）；若将来多实例需加分布式锁（shedlock）防重。

### 4.2 抓取与解析（Jsoup）

```text
1. 遍历 market_products 启用的产品 URL
   - 19 个标准产品：直接抓 huizong{id}/ 解析表格
   - 5 个分类导航页（不锈钢245/钢坯345/铁矿石372/铁合金348/结构钢211）：
     先抓分类页提取子产品清单（页面正文中的子产品名），
     再抓各子产品页解析（二级爬取，子产品页结构需实测确认）
2. GET 页面 → 解析页面日期（标题区 span.time）
3. 定位数据表格：第一行 th 城市列表；每行：首列拆分 品名/规格/材质，城市列取 价格/涨跌
4. 组装 MarketQuote 记录，按 (quote_date, product_code, spec_name, city) upsert
5. 记录本次爬取结果到 market_crawl_logs（成功/失败/变更条数/耗时）
```

- 依赖：`org.jsoup:jsoup`（后端 pom 新增）。
- 解析健壮性：城市列为空（`<td>` 无 `<a>`）→ price 记 NULL；涨跌 `-` → NULL；正则 `(\d+)</br>([+-]?\d*)`。
- 反爬与礼貌：固定 User-Agent、并发 ≤ 4、失败指数退避重试 3 次、超时 15s。
- 页面无变化判断：同日期已有完整数据（行数一致且价格一致）→ 跳过写库，仅更新 captured_at 或直接跳过（满足「日期不变不重复存储」）。

### 4.2.1 数据规模与性能（修正）

- **存储量**：19 个标准产品规格行合计 838 行，按城市展开约 1.4 万格/日（含空单元格，空值不存），全年约 500 万行 —— 索引见 §3，二期做分区归档。
- **AI 候选集**：约 838~1500 条（产品×规格，不含城市维度），匹配效率不是瓶颈。

### 4.3 爬取日志表（运维可观测）

```sql
CREATE TABLE market_crawl_logs (
  id           VARCHAR(36) PRIMARY KEY,
  run_start    TIMESTAMP NOT NULL,
  run_end      TIMESTAMP NULL,
  status       VARCHAR(16) NOT NULL COMMENT 'success/partial/failed',
  product_code VARCHAR(16) NULL,
  quote_date   DATE NULL,
  rows_found   INT DEFAULT 0,
  rows_upserted INT DEFAULT 0,
  rows_skipped INT DEFAULT 0,
  error_msg    VARCHAR(512) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.4 管理接口（供后台手动触发/查看）

- `POST /api/market/crawl/run` 手动触发一次（鉴权：管理员）。
- `GET /api/market/crawl/latest` 最近一次运行状态（页面底部可展示「数据更新于 …」）。

---

## 5. 后端服务改造

### 5.1 行情来源统一抽象（复用现有接口）

现有 `MarketPriceProvider` 接口保留，新增：

```java
@Component @Primary
public class CrawlMarketPriceProvider implements MarketPriceProvider {
  // 按 (ownerId忽略) materialName+spec 归一化后，通过 MaterialMarketMapper 找映射 → market_quotes 取最新
}
```

- `ManualMarketPriceProvider` 降级为 @Secondary：AI 未匹配且无爬取数据时，回退用户手动录入价。
- `latest(ownerId, materialName, spec)` 语义不变，调用方（BusinessQueryService 等）无需改动。

### 5.2 匹配服务（核心，见 §6）

`MaterialMarketMatchService`：
- `matchAndPersist(batchId)`：批量处理批次内全部材料，结果写 `material_market_mappings`。
- `matchSingle(materialId)`：单条（人工重试/新增材料时调用）。
- 触发点：**供货单确认回填台账后**（现 DocumentService 确认逻辑处 + 材料保存处）异步调用 `@Async`，不阻塞主流程。

### 5.3 新 Controller

- `GET /api/market/compare?batchId=&city=` → Tab1 数据：批次材料清单 + 每条的 业务单价/市场价/价差/差异率/匹配状态。
- `GET /api/market/quotes?date=&category=&product=&city=&keyword=` → Tab2 数据：最新一日全量行情（默认当天最新日期），支持按产品/城市/规格搜索，分页。
- `GET /api/market/mappings?batchId=` → 批次内材料匹配状态列表（含 unmatched，供人工处理）。
- `PUT /api/market/mappings/{id}` → 人工修正映射（选 product+spec+city 或标记未匹配）。
- `POST /api/market/match/run?batchId=` → 手动重跑匹配。

### 5.4 AI 调用（复用 QwenClient）

匹配场景为纯文本结构化输出，直接复用 `QwenClient.completeJson(systemPrompt, userPrompt)`（temperature 0.1，JSON 模式），不改 QwenClient。

---

## 6. AI 材料匹配设计（业界最佳实践）

### 6.1 核心思路：分层匹配 + 一次落库 + 人工闭环

**原则：AI 只解决规则解决不了的问题；匹配结果必须持久化；人工修正作为反馈闭环。** 页面查询零 AI 调用。

### 6.2 匹配流水线（自下而上，命中即停）

```
L0 归一化（纯规则，零成本）
    ├─ 全角→半角、大小写、去空格、统一符号：Φ/φ/ф/∅→phi 记法统一为 "Φ"
    ├─ 术语归一：三级抗震螺纹钢→螺纹钢；盘螺→盘螺；HRB400E 保留
    ├─ 顺序归一：把「规格 Φ25」与「材质 HRB400E」分别抽取，规格/材质独立比较
    └─ 单位归一：×9 / 9米定尺 等长度标注剥离

L1 精确匹配（规则）
    ├─ 产品名词典：业务名 → 行情产品 code（三级抗震螺纹钢→144螺纹钢；H型钢→183…）
    ├─ 规格+材质字符串完全一致 → 直接命中（覆盖大部分常用规格）
    └─ 同义词库（DB 维护，可运营）：如 HRB400E≡400E、20#≡20号、Q235B≡Q235

L2 模糊匹配（算法）
    ├─ 规格相似度：编辑距离/Jaccard（Φ25 vs Φ25×9 视为兼容 → 取粗规格）
    ├─ 材质模糊：包含关系（HRB400E ⊂ HRB400）
    └─ 评分>阈值(如 0.9) 自动采纳，否则进入 L3

L3 AI 兜底（千问 completeJson，一次批量）
    ├─ 输入：业务材料原文 + 候选行情规格列表（按产品code过滤后的候选，≤50 条）
    ├─ 输出：JSON { matched: bool, productCode, specName, city, confidence, reason }
    ├─ 批量：单次请求可含多条材料，降低调用次数
    └─ 不匹配 → status=unmatched，进入人工清单
```

> **为什么候选集要先收窄**：候选全集本身只有「产品×规格」约 838~1500 条，但按「产品词典」锁定到单个产品后，单次给 AI 的候选就只有该产品下几十个规格（如螺纹钢 39 个），准确率和成本都最优 —— 这是业界（电商 SKU 映射、行情数据对账）通行做法。

### 6.3 触发时机（确认你的判断是对的）

| 时机 | 动作 |
|---|---|
| **供货单确认回填台账后**（主时机） | 对该供货单涉及的新材料异步跑一次匹配，落库 mapping |
| 手动新增/编辑材料后 | 单条重匹配 |
| 新行情规格首次入库时 | 对 status=unmatched 的历史材料做增量补匹配（可选低频任务，如每日爬虫后） |
| 用户手动修正映射 | 覆盖写 mapping，并记入 `match_history`（未来可做反馈微调） |

> 你的设想正确：**进入页面不调 AI，只查映射表**。AI 成本只在材料写入时发生一次。

### 6.4 人工确认闭环（UI 配套）

- Tab1 中「未匹配」材料显示醒目标记，点击可展开候选下拉（按产品词典过滤的行情规格 + 城市），人工选择后 PUT 保存。
- 低置信度（<80）AI 结果也进入「待确认」态，人工可一键确认或改选。
- 匹配率看板：批次级「已匹配 x/N · 人工待处理 y」小徽标。

---

## 7. 前端页面设计（web）

### 7.1 导航与路由

- `navItems` 新增 `{ key: 'market', label: '市场行情', description: '价格对比与行情', icon: TrendingUp }`，`PageKey` 扩展 `'market'`。
- 新文件 `web/src/MarketPage.tsx`；页头沿用现有 `AppHeader` 上下文选择器（项目/批次切换直接驱动 Tab1）。

### 7.2 Tab1「项目对比」

结构（沿用 Panel / Metric / data-table / status-badge 组件体系）：

```
┌ 顶部：当前项目·批次上下文选择器（复用 header）+ 对比城市选择器（默认公司所在地，可改） ┐
├ 概要指标行：批次采购额 / 市场价均价对比 / 总差异额 / 平均差异率 ──────────────────────┤
├ 材料对比表（data-table）──────────────────────────────────────────────────────────────┤
│  材料 | 规格 | 数量 | 业务单价 | 市场价 | 价差(元/吨) | 差异率 | 匹配状态              │
│  三级抗震螺纹钢 | HRB400E Φ25 | 12吨 | 3,450 | 3,320 | +130 | +3.9% | 已匹配(auto)   │
│  …未匹配材料行：市场价列显示「未匹配 →」按钮，点击弹出候选选择 ──────────────────────│
└ 表格底部：差异率用色（高于市场=红/警示，低于=绿/利好，中国习惯红涨绿跌）──────────────┘
```

- 列设计：业务单价=批次实际采购价（materials.budget_unit_price 或单据单价）；市场价=匹配城市当日价；价差=业务-市场；差异率=价差/市场价。
- 差异率着色：>+2% 红、±2% 内灰、<-2% 绿；与现有 status-badge 色系一致（`--red/--green`）。
- 汇总卡片复用 `Metric` 组件，风格与项目页 KPIs 完全一致。

### 7.3 Tab2「全量行情」

```
┌ 筛选栏（一行）：分类(建材/不锈钢/型钢/钢管) → 产品下拉 → 城市下拉 → 日期(默认最新) ─────┐
├ 表格（data-table，分页 50/页）：─────────────────────────────────────────────────────────┤
│  品名 | 规格 | 材质 | 城市 | 价格(元/吨) | 前日价差 | 数据来源                         │
│  螺纹钢 | Φ10 | HRB400E | 成都 | 3,260 | -20 ↓红 | 创大钢铁 2026-07-31               │
├ 底部：数据更新时间（最近一次爬取）+ 数据来源说明 ────────────────────────────────────────┤
```

- 前日价差列：直接展示爬取值，涨红跌绿（`+20` 红、`-20` 绿、`-` 灰），与国内行情软件一致。
- 支持按规格关键词搜索（如 `Φ25`、`HRB400E`），前端防抖。
- 城市默认值：公司所在地（users.company_name 无地理信息 → 默认「成都」，可在设置中维护「默认对比城市」，或 Tab1 记住上次选择 localStorage）。
- 数据量大时表格滚动区域固定表头，列宽 `w-16` 紧凑布局，符合现有 `batch-table` 风格。

### 7.4 样式

全部复用现有 CSS 变量（`--card/--line/--blue/--red/--green/--radius`），不新造色板；新组件样式追加进 `styles.css` 对应区块（.market-*），保持 18px 圆角、浅色留白、克制不堆边框。

---

## 8. 你可能遗漏/值得优化的点（重要）

1. **对比城市**：行情按城市分列，业务材料对比必须选定城市。建议「公司所在地」配置 + Tab1 城市切换，未在库中城市时提示。
2. **数量单位换算**：业务材料按「件/捆/米」计量的（盘螺按件、型钢按支），行情是元/吨。Tab1 需按材料单位换算（吨=件×单件重量，materials 已有 package_quantity/unit_weight 可支持），否则价差无意义 —— 至少对「吨计价」材料做严格对比，其他单位标注「按吨估算」。
3. **规格定尺差异**：业务「Φ12×9（9米定尺）」vs 行情「Φ12」，L1/L2 规则中把定尺剥离，取同规格基准价。
4. **涨跌颜色**：中国惯例红涨绿跌，务必在页面用红=涨/绿=跌（与代码 `--red/--green` 语义一致），不要照搬站点 green 类名。
5. **行情历史归档**：约 500 万行/年增长，二期做分区（按月 RANGE）或 30 天冷数据归档；一期先留好唯一键和索引。
6. **数据完整性**：某些城市单元格为空（该产品该城市无报价）→ 页面显示「-」，不渲染为 0。
7. **价格有效性**：爬虫可能爬到「昨日未更新」页面，入库前校验页面日期；若连续 N 小时页面日期未前进，告警（crawl_logs + 页面提示「行情更新滞后」）。
8. **详情页二期能力**：产地/钢厂维度 + 价格走势图（历史 30 天均线）可直接复用 market_quotes 历史数据，本期不做但数据已够。
9. **权限**：行情数据是全公司共享，还是按 owner 隔离？建议共享（市场行情是公共数据），对比页按用户可见的项目过滤。
10. **material_market_mappings 与 materials.market_price 快照**：快照用于「历史批次当时行情」，页面实时对比用最新 market_quotes —— 两者都展示，避免用户困惑。
11. **mini-new 小程序**：本期需求只提 web 业务系统；小程序功能/UI 尚未对齐 web（项目已知事项），市场行情页建议二期同步小程序（复用 backend API，小程序端仅做 Tab2 简化版）。

---

## 9. 开发里程碑与任务拆分

### M1 数据与爬虫（backend）
- [ ] pom 增加 jsoup；`@EnableScheduling`
- [ ] Flyway V3：market_products / market_quotes / material_market_mappings / market_crawl_logs
- [ ] `MarketCrawlerService`：抓取、解析、upsert、幂等
- [ ] `MarketCrawlerScheduler` 每小时定时 + 启动全量
- [ ] `CrawlMarketPriceProvider`（@Primary）+ 旧 Provider 降级
- [ ] 爬虫管理接口 + crawl_logs
- 验收：跑一次全量，19 个标准产品 + 5 个分类子产品全部入库；再跑一次不重复；market_quotes 有当日全部数据

### M2 AI 匹配（backend）
- [ ] 归一化工具 `MarketNameNormalizer`（术语/符号/顺序/定尺剥离）
- [ ] 产品词典 + 同义词库（DB 表或枚举）
- [ ] `MaterialMarketMatchService`：L1 精确 → L2 模糊 → L3 AI（QwenClient）
- [ ] 供货单确认回填后异步触发匹配，落库 mappings
- [ ] 匹配查询/人工修正接口
- 验收：样例「三级抗震螺纹钢 HRB400E Φ25」→ 命中 螺纹钢 Φ25 HRB400E；乱序/异名案例可人工修正

### M3 展示页面（web）
- [ ] PageKey + 导航 + MarketPage 骨架（双 Tab）
- [ ] Tab1 项目对比：城市选择、材料对比表、差异率着色、未匹配人工绑定交互
- [ ] Tab2 全量行情：筛选、分页、搜索、前日价差红绿
- [ ] api.ts 扩展类型与方法；与后端联调
- 验收：走查 Tab1/Tab2，视觉与现有页面一致

### M4 打磨与二期预留
- [ ] 匹配率看板/徽标、人工绑定记录
- [ ] 行情更新滞后提示（页面 + 日志）
- [ ] 性能：market_quotes 大数据量分页索引验证
- 二期：走势图、钢厂/产地对比、小程序端

---

## 10. 关键决策点（需你确认）

1. **对比城市默认值**：建议「成都」（或公司所在地配置项，建 `sys_config` 表）。
2. **行情数据共享范围**：建议全公司共享，不按用户隔离。
3. **本期是否需要走势图**：建议二期（数据每日积累后更有意义）。
4. **材料计量单位**：Tab1 是否要先做「非吨单位→吨」换算（推荐做，涉及 materials 既有字段）。
5. **爬取频率**：每小时 5 分跑一次（页面 1 小时内可能多次更新价格）；如站点实际日更 2-3 次，可降频为每 2 小时，先按每小时跑。

---

## 附：技术栈与约束（现状确认）

- web：React 19 + TS + Vite，lucide-react 图标，单文件 styles.css（7172 行），App.tsx 内聚路由/布局/四大页面 + 独立页 DataCenter/ReconciliationLedger/ConnectedWorkspace。
- backend：Spring Boot 3.3 + Java 17 + MyBatis-Plus + MySQL + Flyway（V1/V2 已有），`com.aibot.purchase` 包，已有 QwenClient（JSON 模式）、MarketPriceProvider 抽象。
- mini-new：微信小程序，目前仅 login/index 两页，功能未对齐 web（项目已知基线事项）。
