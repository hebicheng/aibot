# gtgqw 行情源适配调研报告与方案

> 日期：2026-08-21
> 背景：原行情源（创大钢铁 jiage.cdgtw.net）已失效（页面 200 但无任何数据，实测无表格/无日期/无价格），需切换/适配 gtgqw（钢易网）；后续可能接纳 mysteel，本期不做但 DB 结构需预留。

---

## 一、现状探查结论

### 1. 核心表 `market_quotes`：确实只差「产地」

| 现状字段 | 说明 |
|---|---|
| quote_date / product_code / product_name / spec_name / spec / grade | 产品与规格 |
| city / price / price_change / detail_url / captured_at | 城市、价格、涨跌 |
| **缺 origin（产地/钢厂）** | gtgqw 每行都有产地；mysteel 也有「钢厂/产地」筛选（mrkbrandCode） |

**当前唯一键 `(quote_date, product_code, spec_name, city)` 无法区分「同一城市+同一产品+同一规格」下的多个产地**——而 gtgqw 数据恰恰如此（实测杭州螺纹钢 Φ25 HRB400E 就有安徽富鑫/宝武鄂钢/方大特钢等不同产地、不同价格）。这是必须改的点。

### 2. 用不上的表：`market_prices`（V1 遗留，死代码）

- 表 `market_prices` + 实体 `MarketPrice` + `MarketPriceMapper` + `MarketPriceProvider` 接口 + `ManualMarketPriceProvider`
- 全项目 **没有任何调用方**（grep 确认 `MarketPriceProvider.latest()` 无引用）
- 结论：属于旧方案遗留，当前行情链路完全走 `market_quotes`。建议清理（删表或保留均可，见方案 A4）。

其他表均在用：`market_products`（产品字典，需换源重建）、`material_market_mappings`（匹配落库）、`market_crawl_logs`（爬取日志）。

### 3. 现有爬虫实现（`MarketCrawlerService`）

- 结构假设是 cdgtw 的：一页 = 一个产品，表头 = 23 个城市列，首列 = 品名/规格/材质，单元格 = `<a>价格<br>涨跌</a>`。
- 幂等：按 `(quote_date, product_code, spec_name, city)` upsert。
- 调度：启动全量 + 定时周期（已拆分为 `crawler-enabled-on-startup` 与 `crawler-enabled-scheduled` 两个独立开关，默认均 false；定时改为每 5 小时 `0 0 */5 * * ?`）。
- **该结构对 gtgqw 完全不适用**（gtgqw 是文章制，一篇 = 一城市 × 一产品，行列语义相反）。

### 4. gtgqw 站点结构（已实测 2026-08-21）

- **产品频道**（23 个）：`/luowengang/`（螺纹钢）、`/zhongban/`（中厚板）、`/wufeng/`、`/hanguan/`、`/fangguan/`、`/luoxuanguan/`、`/daigang/`、`/caogang/`（工角槽，含工/槽/角钢）、`/hgang/`、`/hua/`（花纹板）、`/feigang/`、`/tiejingfen/`、`/rezha/`、`/lengzha/`、`/duxinban/`、`/caituban/`、`/duxinguan/`、`/bxg/`（不锈钢）、`/gangpi/`、`/kuang/`、`/zao/`（造船板）、`/guo/`（锅炉板）、`/iron/`（生铁）
- **城市频道**：`/hangzhou/`、`/shanghai/`、`/beijing/`、`/tianjin/`、`/tangshan/`、`/jinan/`、`/hefei/`、`/wuhan/`、`/handan/`、`/wuxi/` 等 20+
- **文章**：`/j/showhz{id}.html`，标题如「8月21日杭州螺纹钢价格今日报价表」→ **一篇 = 一个城市 × 一个产品**
- **详情页表格**（实测）：
  - 表头：`品名 | 规格（mm） | 材质 | 产地 | 价格（元/吨） | 涨跌`（6 列，无详情链接）
  - **产品是行内列**，不是文章级别：一篇「螺纹钢」文章实际含多个品名行
  - 每行 = 一条报价，含**产地**；同城同规格不同产地多行
  - 存在大量**范围规格**：Φ28-32、Φ12-14、Φ16-25、Φ10（混用）
  - 编码 **gb2312**（现有 Jsoup 抓取需显式处理）
  - 实测杭州螺纹钢文章（showhz2483815）共 270 行：**螺纹钢 200 / 盘螺 59 / 高线 11**（品名分布）
  - 螺纹钢 17 种规格（Φ10/Φ12-14/Φ16/Φ16-20/Φ16-25/...），盘螺 6 种，高线 4 种；产地 8+ 家（安徽富鑫/宝武鄂钢/方大特钢/...）
- 当前业务只需要**杭州的螺纹钢文章**（内含螺纹钢/盘螺/高线），一篇文章即可覆盖三类产品。

### 5. 前端现状

| 端 | Tab2 全量行情 | Tab1 项目对比 |
|---|---|---|
| **web**（`MarketPage.tsx`） | 筛选：分类/产品/城市/关键词（L427-471）；表列：品名/规格/材质/城市/价格/前日价差/日期（L485-491） | 城市切换，无产地（L301-333） |
| **mini-new**（`market.wxml`） | 筛选：分类/产品/城市 + 搜索（L160-188）；分组卡片，无产地 | 城市切换 + 重新匹配，无产地 |

两端均**无产地筛选、无产地列**；`api.ts` 的 `MarketQuoteRow` 类型也无 origin 字段。

### 6. 材料 ↔ 市场匹配逻辑现状（含范围规格处理）

链路：`MarketNameNormalizer.parse()`（L0 规则解析）→ `MaterialMarketMatchService` L1 精确 / L2 模糊 / L3 AI 兜底。

**已发现的优化点：**

1. **Normalizer 规格正则不抓范围**（`MarketNameNormalizer.java:94`）：
   `Φ\s*(\d+(?:[.×*xX]\d+){0,4})` → 输入「Φ28-32」只抓到 `28`，`-32` 残留。业务材料规格若是范围，解析出来的 spec 是残缺的。
2. **匹配没有「范围包含」逻辑**（`MaterialMarketMatchService.java:168-221`）：
   - 现有 `isRangeSpec` 只是把范围规格**降优先级**，`specEqual` 只做「归一化相等 / 定尺兼容（Φ25 ≡ Φ25×9）」；
   - **业务 Φ25 无法命中市场 Φ16-25 这类范围报价**（字符串相等才中）；
   - 之前 cdgtw 也有范围，但处理是「完全相等才兜底」，没有数值区间判断——gtgqw 范围占比更高，值得补强。
3. 符号归一化只处理 Φ/Ф/φ 和空白，未处理 ×/x/* 全角变体。

### 7. mysteel 预留（后续）

`probe/mysteel_price.py` 已跑通：带登录 + AES 解密 + 签名，筛选维度含 **品名/规格/材质/市场/钢厂(产地)/交货地**。数据天然有 source + origin 两个维度，与 gtgqw 方案一致，本期只需在表结构预留。

---

## 二、适配方案

### A. 数据库变更（新增 V6 迁移）

1. **`market_quotes` 加两列**
   - `origin VARCHAR(64) NOT NULL DEFAULT ''` COMMENT '产地/钢厂'
   - `source VARCHAR(16) NOT NULL DEFAULT 'gtgqw'` COMMENT '数据源：gtgqw/mysteel'
   - 唯一键改为 `(quote_date, source, product_code, spec_name, city, origin)`
   - `spec_name` 仍存完整原文（gtgqw 组成为「品名 规格 材质」，与 cdgtw 兼容，匹配逻辑不改数据形态）

2. **`market_products` 加 `source` 列**，唯一键 `(source, code)`。**code 复用现有内部编号**（螺纹钢=144、盘螺=143、高线=145），只把这 3 行的 `source='gtgqw'`、`source_url` 改为 gtgqw 发现入口（均指向 `/luowengang/`，因为三类产品数据在同一篇文章里），其余 cdgtw 行 `enabled=0` 保留。
   - 理由：gtgqw 无外部产品 id，code 只是内部稳定标识；**复用现有编号则 `MarketNameNormalizer` 词典、前端产品筛选逻辑、匹配逻辑全部零改动**。
   - 未来新增产品（如中厚板/焊管）时再按 gtgqw 对应渠道页补充行。
   - `market_products` 表**必须保留**：前端产品/分类筛选、行内品名→code 映射、爬取发现入口（source_url）都依赖它。

3. **`material_market_mappings` 加 `source VARCHAR(16) NULL`、`origin VARCHAR(64) NULL`**（记录匹配命中的源与产地，供对比页展示/取价）。

4. **`market_prices` 死代码清理**：删除 `MarketPrice`/`MarketPriceMapper`/`MarketPriceProvider`/`ManualMarketPriceProvider` 四个类，并在 V6 里 `DROP TABLE market_prices`。
   - **决策点 A4-1**：确认删除（低风险，无调用方）；若想保守可保留表不删。

### B. 爬虫重构（新建 `GtgqwCrawlerService`）

当前业务只需**杭州的螺纹钢文章**（含螺纹钢/盘螺/高线），流程极简：

1. **发现层**：抓杭州频道页 `/hangzhou/`（或螺纹钢渠道页 `/luowengang/`），正则提取 `/j/showhz\d+\.html` + 标题，选标题含「螺纹钢」的**最新一篇**（匹配当日日期标题更稳，如「8月21日杭州螺纹钢…」）。
2. **解析层**：抓文章页（显式指定 gb2312），定位含「品名/价格」的表头，逐行解析 `品名 / 规格 / 材质 / 产地 / 价格 / 涨跌`；`quote_date` 取标题日期或发布时间，`city` 固定为杭州（来自发现入口）；`product_code` 按行内品名经 `MarketNameNormalizer` 词典反查（螺纹钢→144、盘螺→143、高线→145，同一篇文章产出三类产品行）。
3. **入库**：幂等 upsert `(quote_date, source='gtgqw', product_code, spec_name, city='杭州', origin)`；`spec_name` 组成「品名 规格 材质」（与 cdgtw 形态一致，匹配逻辑零改动）；`detail_url` 存文章 URL；涨跌直接入库（沿用 `price_change`）。
4. **调度**：仅杭州 + 1 篇文章，一次爬取 ≈ 300 行，量级很小。沿用现有 `MarketCrawlerScheduler`（启动 + 每小时均可）；城市/产品入口配置化（`app.crawl-channels`），未来扩展杭州以外的城市或更多产品时只需加配置，不改代码。

### C. 前端适配

1. **web `MarketPage.tsx`**
   - Tab2：表加「产地」列；筛选栏加「产地」下拉（meta 返回 `origins` 列表）；
   - Tab1：对比行展示命中产地；市场价列在「多产地」时取该产品默认产地价或全产地均价（决策点见 E3）。
2. **mini-new `market.wxml` + `index.js`**：同步加产地筛选与产地展示（卡片行 + 对比项）。
3. **`api.ts`**：`MarketQuoteRow` 加 `origin`，`marketQuotes` 加 `origin` 参数，`MarketMeta` 加 `origins`。

### D. 匹配逻辑增强（重点回应「Φ28-32 优化空间」）

1. **Normalizer 支持范围规格**：规格正则升级为 `Φ\s*(\d+(?:[.×*xX]\d+){0,4}(?:[-–]\d+)?)`，使「Φ28-32」整体解析为 spec=「28-32」，不再残留。
2. **新增 L2.5「范围包含」匹配**（规则、零成本）：
   - 业务单规格（如 Φ25）→ 市场存在范围行（Φ16-25）且数值 ∈ [16,25] 时命中，置信度 ~85，method=`range`；
   - 优先级：L1 单规格精确（Φ25→Φ25）> L2 定尺兼容 > L2.5 范围包含 > L3 AI；
   - 范围规格本身也保留「相等即命中」（业务 Φ28-32 ↔ 市场 Φ28-32）。
3. **符号归一化补强**：×/x/X/*、全角变体统一处理。
4. **AI 兜底提示词**补充产地、范围规格说明（可选，量少时走规则即可）。
5. **多产地取价策略**（对比页 `findQuote` 命中多行时）：
   - 方案 1：按映射命中产地精确取价，无该产地则回退均价（推荐，信息最准）；
   - 方案 2：一律取该产品+规格+城市全产地均价（简单，与原逻辑一致，但抹平产地价差）。

### E. 需要你拍板的决策点

| # | 决策 | 建议 |
|---|---|---|
| E1 | `market_products.code`：复用现有内部编号（144/143/145）还是重建 | **复用现有编号**（词典/前端零改动） |
| E2 | 爬取范围与频率 | 仅杭州螺纹钢文章（含盘螺/高线），启动+每小时均可，量级 ~300 行/次 |
| E3 | 多产地取价：命中产地精确价 vs 全产地均价 | 命中产地优先 + 均价回退 |
| E4 | 业务单规格落入市场范围（Φ25→Φ16-25）是否采纳 | 采纳，置信度降级并标注 |
| E5 | `market_prices` 旧表/死代码删除 | 删除 |

### F. 实施顺序（建议）

1. **Phase 1（数据基础）**：V6 迁移（quotes/products/mappings 加 source+origin）→ 字典 3 行切 gtgqw → 新建 `GtgqwCrawlerService`（杭州螺纹钢文章）→ 跑通入库 + 日志
2. **Phase 2（匹配+前端）**：Normalizer 范围规格 + L2.5 范围包含匹配 → web/mini-new 产地筛选与展示 → 对比页多产地取价
3. **Phase 3（mysteel 预留）**：本轮不动；表结构已带 `source`/`origin`，届时实现 mysteel 数据源实现 + 前端数据源切换即可

---

## 附：关键文件索引

- 表结构：`backend/src/main/resources/db/migration/V3__market_data.sql`（V1 `market_prices` L166-177）
- 爬虫：`backend/.../service/market/MarketCrawlerService.java`、`MarketCrawlerScheduler.java`
- 匹配：`.../market/MaterialMarketMatchService.java`、`MarketNameNormalizer.java`、`MarketQueryService.java`
- 接口：`.../controller/MarketController.java`、`.../dto/MarketDtos.java`
- 前端 web：`web/src/MarketPage.tsx`、`web/src/api.ts`（L444-499）
- 前端小程序：`mini-new/pages/index/partials/market.wxml`、`mini-new/pages/index/index.js`
- 探针：`probe/gtgqw_hangzhou.py`（gtgqw 已跑通）、`probe/mysteel_price.py`（mysteel 已跑通）

---

## 三、实施记录（2026-08-21 已完成并验证）

### 已提交代码变更

1. **V6__gtgqw_source.sql**：`market_quotes` 加 `source`+`origin`（唯一键扩为 `(quote_date, source, product_code, spec_name, city, origin)`）；`market_products` 加 `source`（唯一键 `(source, code)`），144/143/145 切 gtgqw 并停用其余；`material_market_mappings` 加 `source`+`origin`；DROP 死表 `market_prices`。
2. **V7__gtgqw_products.sql**：幂等播种 gtgqw 产品字典（螺纹钢144/盘螺143/高线145 → `http://www.gtgqw.com/luowengang/`）。
3. **爬虫重写**（`MarketCrawlerService`）：文章制发现（渠道列表页→按城市找最新文章）→ gb2312 显式解码 → 表头定位（文章表格首行为标题行，次行才是表头）→ 逐行按品名反查 product_code → upsert。文章页需带渠道 Referer 否则 403（已修复）。
4. **匹配增强**：`MarketNameNormalizer` 规格正则支持范围（`Φ28-32` 整体解析）；`MaterialMarketMatchService` 新增 **L2.5 范围包含**（业务 Φ14 → 命中市场 Φ12-14，置信度 85，method=range），mapping 记录 source/origin。
5. **查询/接口**：`MarketQueryService` 支持 origin 筛选、meta 返回 origins/cities（数据驱动）、多产地取价（命中产地 → 城市均价 → 全国均价回退）、默认对比城市改杭州；`MarketController` 增加 origin 参数。
6. **前端**：
   - web：`api.ts` 类型/参数加 origin；`MarketPage.tsx` Tab2 加产地列+产地筛选、对比行展示产地、默认城市杭州。
   - mini-new：`index.js` 加产地状态/加载/切换、默认城市杭州；`market.wxml` 加产地 picker + 行内/对比产地展示；`market.wxss` 筛选行改 4 列。
7. **清理死代码**：删除 `MarketPrice`/`MarketPriceMapper`/`MarketPriceProvider`/`ManualMarketPriceProvider` 四类。

### 验证结果（dev 环境，MySQL 9.0 docker）

- **Flyway V1→V7** 全部 success。
- **爬取**：杭州螺纹钢文章 270 行入库（螺纹钢 198 / 盘螺 59 / 高线 10，产地 39 家）；二次爬取 270 全部 skipped（幂等正确）。
- **接口**：`/api/market/meta` 返回 latestDate=2026-08-21、cities=[杭州]、origins 39 个；`/api/market/quotes` 支持 origin 过滤。
- **匹配链路**（构造批次实测）：Φ25→exact；Φ14→**range**（命中 Φ12-14）；Φ28-32→exact；盘螺 Φ10→range（命中 Ф8-10）。对比页市场价/产地/价差率全部正确。
- **测试**：后端 55 tests 全绿；web `tsc -b` 通过。

### 备注 / 待办

- dev 环境存在 `SERVER__PORT=0`（环境注入的嵌套变量）导致本机起服务端口随机，部署时无此问题；如需本机固定端口可 `SERVER__PORT=8090` 或取消该变量。
- `market_products` 在部分环境被清空，V7 幂等播种已兜底。
- 后续接 mysteel：表结构已带 `source`/`origin`，实现对应爬虫 + 前端数据源切换即可（Phase 3）。
- 生产/测试环境部署时需重新执行 flyway（V6/V7 会自动应用）。
