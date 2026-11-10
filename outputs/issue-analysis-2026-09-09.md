# AI-BOT 五类问题分析报告（2026-09-09）

> 范围：web/src + backend/src/main/java，仅做代码走查与原因分析，未修改任何代码。
> 目标：逐项定位根因 → 给出可选方案与推荐 → 等你逐项拍板后再动手。

---

## 问题 1：意图识别不明确时返回「补充字段」卡片，补了也没用，陷入循环

### 现象
用户说明不够明确（或说明与资料内容冲突）时，AI 返回 `clarify_card`。用户按提示补充后再发，依然拿不到可确认的数据卡片，必须重新上传同一份文件。

### 根因（代码定位）

| 环节 | 位置 | 问题 |
|---|---|---|
| AI 侧 | `ChatAiService.systemPrompt()` 规则 12 / 19 | 说明与内容冲突、或方向不确定时直接输出 `clarify_card`，只带 `question / missingFields / options` |
| 后端落库 | `DocumentService.uploadAndRecognize()` L142-151 | **intent 为 `clarify_card` 时不创建 document**：`if (... && !"clarify_card".equals(reply.intent()))`。因此没有 documentId、没有可编辑的数据卡片 |
| 文件引用丢失 | `DocumentService.createDocumentFromPayload()` L436-480；`ChatMessage` 实体 | 文件 `fileUrl / fileObjectKey / sourceText` **只存在 documents 表**。chat_messages 只有 `fileName` 字段，没有文件引用。clarify 不建 document ⇒ 这一轮的文件在 DB 里彻底失去引用 |
| 续接上下文缺失 | `ChatService.send()` L59-71、`upload()` L73-92 | 每次请求只构造 `AiContext(companyName, projectName, batchName, customerName)`，**不携带上一轮的 AI 消息 payload（question/requestedType/detectedType）、也不携带上一轮的文件** |
| 前端 | `ConnectedWorkspacePage.tsx` L1029-1038 | `ClarificationCard.onChoose` 只做两件事：把 `请按"X"处理这份资料：` 填进输入框 + 打开附件面板。**既不回传上一条消息 ID，也不复用原文件**，等于要求用户重新选文件 |
| 结果 | — | 只发文本 → AI 看不到原资料 → 再次 `clarify_card`；重新上传文件 → 上一轮的 requestedType/detectedType 也没回传 → 可能再 clarify。死循环成立 |

### 方案

**A. 会话续接（保留文件 + 追问续答）**
1. `chat_messages` 增加 `file_url / file_object_key / source_text`（或复用 documents 表存一条 `status=needs_clarification` 的记录承载文件与解析文本）。
2. `send`/`upload` 增加可选参数 `replyToMessageId`（或后端自动取本批次最近一条 `status=pending` 的 AI clarify 消息）。
3. 命中时：把「上一轮 AI payload（question/requestedType/detectedType）+ 上一轮文件/解析文本 + 用户新说明」一起送模型，语义变成「针对追问的补充回答」，而不是从零识别。
4. 结果回写到**同一条记录**，不新建、不重复全额计费。

**B. 类型兜底（你要求的行为：能给类型就直接给卡片）**
后端在 intent 为 `clarify_card` 时，先用「用户说明 + AI 给出的 requestedType/detectedType」做规则匹配（供货/送货/运输/请款/付款/收款/发票/结算/仅存档）：
- 唯一命中 → 直接按该类型创建 `pending` document（字段尽量从 payload 抽取，缺失留空），返回 `document_review` 卡片 + `missingFields`，用户点「修改识别字段」补全后即可确认；
- 无法命中 / 命中多个 / 明确冲突 → 才回 `clarify_card`。

**推荐：A + B 一起做。** B 能一次性解决 80% 场景（不用追问），A 兜住真正无法判定的场景（补充不必重传文件）。只做 B 的话，冲突场景仍然要重传。

---

## 问题 2：需补字段的单据没有必填标志

### 根因

| 位置 | 现状 |
|---|---|
| `DocumentService.requiredMissingFields()` L874-909 | 只算出「当前缺失的字段」`missingFields`（docDate/amount/supplierName/invoiceDirection/...），**没有下发「该类型有哪些必填字段」** |
| `DocumentService.buildDocumentCard()` L616-666 | 卡片只有 `missingFields`，没有 `requiredFields` / 字段级 meta |
| `ConnectedWorkspacePage.tsx` L296-304 | 只在按钮文案里提示「请先补充必填字段」 |
| 同上 L464-467（编辑态）、L496-513（展示态） | 字段标签无 `*`、无红色必填态；缺失值统一显示「待补充」，与「非必填为空」视觉一致 |

### 方案

1. 后端新增 `fieldMeta`（按 docType 计算）：`requiredFields` + 每个字段的 label/type/required，随 `documentPayload` 下发（`DocumentService.documentPayload()` L699-734）。
2. 前端：
   - 编辑态：必填字段标签加红色 `*`，空值红框，保存时本地拦截并定位到第一个空项；
   - 展示态：必填且缺值的字段显示红色「待补充（必填）」，与选填空值区分；
   - 卡片顶部汇总一条「还需补充：金额、单据日期」。
3. 后端 `validateForConfirmation()` L755-831 保留兜底（前端漏判也不会写脏数据）。

**推荐：后端下发 requiredFields（单一数据源），前端只负责渲染。** 前端硬编码会与后端校验漂移。

---

## 问题 3：配送单/发货单没有金额时不强制，运输费用只有识别成运输单才计入

### 根因

| 位置 | 现状 |
|---|---|
| `DocumentService.requiredMissingFields()` L882-885 | `if (!"delivery_note".equals(docType) && amount <= 0) missing.add("amount")` → **送货单/配送单/发货单豁免金额** |
| `DocumentService.validateForConfirmation()` L762-763 | 同样豁免；L786-795 只要求「到货数量/重量/金额三者有其一」 |
| `LedgerService.applyContribution()` L287-292 | `delivery_note` → 累加 `deliveryAmount` + 到货量/重量；**只有 `transport_note` → 累加 `freight`（运输费用）** |
| `ChatAiService.systemPrompt()` 规则 10 | 明确「即使送货单中出现运费字段也暂不提取；只有 transport_note_card 可以形成运输费用」 |
| 台账前端 `ReconciliationLedgerPage.tsx` L638-644 | 送货单只展示到货数量/重量与状态，**金额（deliveryAmount）未展示** |

即：金额可选 → 大量配送单/发货单金额为 0 → 台账里既没有送货金额、也进不了运输费用，等于这笔钱「消失」。

### 方案

1. **去豁免**：`requiredMissingFields()` 与 `validateForConfirmation()` 删除 `delivery_note` 的金额豁免，金额强制必填（缺失即 `missingFields: ["amount"]`，卡片直接给出可编辑卡片让用户填）。
2. **金额落账归属（需你拍板）**：
   - 选项 ①（最小改动）：继续写 `deliveryAmount`（送货/到货金额），台账「送达」列增加金额小字展示；
   - 选项 ②：配送单/发货单的金额同时计入 `freight` 运输费用 —— 与现有「只有 transport_note 才形成运输费用」的规则冲突，需要同步改 prompt 规则 10 与台账归集逻辑，影响面较大。
3. **类型纠偏**：允许在卡片上把 `delivery_note` 直接改判为 `transport_note`（现有类型切换按钮在已确认状态被 `editDocument()` L241-247 拦截，需确认是否放宽到已确认单据——已确认改类型要冲正台账，风险较高）。

**推荐：先做 1 + 2①（金额必填、写入 deliveryAmount 并在台账展示）**，运输费用归属的规则调整单独评估。

⚠️ 连带影响：`editDocument()` L268-270 在编辑**已确认**单据时会重新跑 `validateForConfirmation()`。去豁免后，历史已确认且无金额的送货单，一编辑就会报错要求补金额。需要你确认这是期望行为（我认为是：强制补金额）。

---

## 问题 4：发票方向判错（销售方是我方，却返回「进项发票，购买方为本单位」）

### 根因

链路：`ChatAiService.parse()` → `normalizeFinancialPayload()` → `DocumentService.createDocumentFromPayload()` → `intentToDocType()`。

| 位置 | 问题 |
|---|---|
| `ChatAiService.normalizeFinancialPayload()` L436-442 | **方向完全由 intent 决定**：`purchase_invoice_card → invoiceDirection="purchase"`，直接覆盖 AI payload，**不做任何事实校验** |
| `ChatAiService.parse()` L283 | `reply` 文案直接采用 AI 输出。AI 输出「已识别为进项发票，购买方为本单位…」与它自己填的 `sellerName`（我方）矛盾，后端不检测、不纠正 |
| `DocumentService.intentToDocType()` L1351-1360 `invoiceDocType()` | 用 `reply.contains("销项"/"进项")` 猜方向 —— 依赖 AI 文案，脆弱且与上一条互相放大错误 |
| `DocumentService.extractCounterparty()` L1362-1369 | `purchase_invoice` 取 `sellerName` 作为 supplierName ⇒ 卡片「销售方」位置显示的是本单位，用户一眼看出错误 |
| `DocumentService.mergeFinancialFields()` L865-869 | 再次按 docType 强写 `invoiceDirection`，没有反向校验 |
| `DocumentService.documentPayload()` L699-734 | **没有下发 `sellerName / buyerName`**，前端无法展示双方名称，用户也没有纠正入口（只有 `invoice_unknown` 才有方向切换按钮，见前端 L516-527） |
| 数据侧 | users.company_name 有值（baseline 为「杭州葭安科技有限公司」），`AiContext.companyName` 实际能拿到，只是**没人用它做校验** |

即：AI 判断错了以后，整条链路没有任何一层用「本单位名称 vs 销售方/购买方」做确定性校正，且错误的 AI 文案被原样透出给用户。

### 方案

1. **后端确定性校正（新增，最高优先级）**：在 `ChatAiService.parse()` 之后、`DocumentService` 落库之前，用 `companyName` 与 `sellerName / buyerName` 做归一化比对（去空格、括号、全半角、去掉「有限公司/股份/」等通用后缀后做包含匹配）：
   - 仅 sellerName 匹配本单位 → 强制 `sales_invoice_card` + `invoiceDirection=sales`；
   - 仅 buyerName 匹配本单位 → 强制 `purchase_invoice_card` + `purchase`；
   - 两者都匹配或都不匹配、且 AI 方向存疑 → 降级 `invoice_unknown`，让用户点选（比现在直接写错安全）。
2. **reply 文案由后端按最终 docType/direction 生成**（覆盖 AI 文案），杜绝「数据与话术互相打脸」；同时把 `invoiceDocType()` 里 `reply.contains(...)` 的判断删掉。
3. **下发 `sellerName / buyerName`**，前端卡片展示「销售方 / 购买方」，并为 `purchase_invoice / sales_invoice` 也提供「方向纠正」按钮（复用 `onEdit({docType})`）。
4. **prompt 加固**：规则 16 增加「必须先核对销售方/购买方名称与本单位身份；若销售方是本单位必须输出 sales_invoice_card，并在 reply 中与实际方向保持一致」。
5. 已确认发票的方向纠错：未确认可直接切换；已确认需走 `correctConfirmedDocument()` 冲正（当前 L255-257 禁止改类型），是否放开需要你确认。

---

## 问题 5：台账导出要求 1:1 还原表格（含样式、合并单元格）

### 根因

| 位置 | 现状 |
|---|---|
| `ReconciliationLedgerPage.exportCsv()` L472-528 | 导出的是**平铺 CSV**：一行一条明细，项目/批次/财务/销售字段逐行重复，没有表头、没有合并、没有任何样式、数字无格式 |
| 表格真实结构 L558-604 | **三层表头**：分组行（`colSpan=2/6/3/3/13`）+ 主表头（`rowSpan=2`）+ 「销售-开票/收款」子分组（`colSpan=3/2`）；`状态/操作` 列 `rowSpan=3` |
| 数据行 L607-774 | 大量纵向合并：项目/批次 `rowSpan=rows.length`；材料财务 7 列 `rowSpan=materialRowCount`；销售/收款/毛利/项目经理/状态/操作 `rowSpan=rows.length` |
| `App.tsx` L2330 `LedgerPage` | 另有一套旧的 CSV 导出，**该组件未被渲染（死代码）**，不要在这个上面改 |
| 依赖 | 前端 `package.json` 无任何 Excel 库；后端已有 `poi-ooxml 5.3.0`（Apache POI），但当前无导出接口 |

CSV 天然无法承载合并单元格与样式 —— 这是格式选择问题，不是实现细节问题。

### 方案对比

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **① 前端 ExcelJS（推荐）** | web 引入 `exceljs`，复用现有 `buildGroups()` 的聚合结果直接写 xlsx：3 行表头 + `mergeCells` 还原 colSpan/rowSpan、表头填充/字体/边框/居中、金额 `¥#,##0.00`、状态列着色、冻结表头与项目/批次列、自动列宽 | 与页面展示**同一份数据、同一套结构**，不存在逻辑漂移；改动集中在前端；无需后端排期 | 新增约 1MB 依赖；ExcelJS 不支持单元格内富文本（不影响本表） |
| **② 后端 Apache POI** | 新增 `GET /api/reports/ledger/export`，用 POI 写 xlsx（依赖已就绪），前端改为下载链接 | 三端（web/小程序）可复用同一接口；不增加前端体积 | 需**在后端复刻 `buildGroups()` 约 200 行聚合逻辑**，前后端两份聚合必然漂移（除非把聚合整体下沉到后端，前端改为消费后端聚合结果——改造量显著更大） |
| **③ HTML table → .xls** | 拼 HTML 表格另存为 .xls | 零依赖 | Excel 会弹格式警告，样式与兼容性差，不推荐 |

**推荐方案 ①**：一次到位还原样式与合并，且与页面结构同源。若你明确要求小程序端也要导出（现在小程序 `pages/index/actions.js:335` 是「开发中」占位），则选 ②，并把聚合逻辑下沉为后端单一数据源（后续前端改为展示后端聚合结果）。

---

## 待你拍板的决策点

| # | 决策点 | 选项 |
|---|---|---|
| 1 | 问题 1 的兜底策略 | A+B 全做 / 只做 B（类型命中即出卡片） / 只做 A（续接上下文） |
| 2 | 问题 2 必填定义来源 | 后端下发 requiredFields（推荐） / 前端硬编码 |
| 3 | 问题 3 配送单金额归属 | 写 deliveryAmount 并在台账展示（推荐） / 同时计入运输费用 freight / 允许改判为 transport_note |
| 4 | 问题 3 历史数据 | 已确认的无金额送货单再次编辑时是否强制补金额（推荐：是） / 维持原样 |
| 5 | 问题 4 已确认发票 | 是否允许对已确认发票纠正方向（需冲正台账） |
| 6 | 问题 5 导出实现 | 前端 ExcelJS（推荐） / 后端 POI（含聚合下沉，供三端复用） |
