# 智能聊天全链路对齐审查报告（web ↔ mini-new ↔ backend）

审查日期：2026-08-01
审查范围：**智能聊天全链路** —— 消息收发、附件上传、四类业务卡片（单据确认 / 重复处理 / 操作结果 / 澄清追问）、字段修改、金额差异、费用关联、确认入库、下一步建议、失败恢复
审查方式：三端源码逐行交叉比对（`web/src/ConnectedWorkspacePage.tsx`、`web/src/App.tsx`、`mini-new/pages/index/partials/workspace.wxml`、`mini-new/pages/index/index.js`、`mini-new/pages/index/actions.js`、`mini-new/utils/domain.js`、`backend/.../DocumentService.java`、`ChatService.java`、`ChatAiService.java`）
前提：本轮只做静态审查，未修改业务代码，未在微信开发者工具/真机运行。

> **整改状态（同日）**：第一、二批 + 部分 P2 已按本报告完成修复（P0-1、P1-1、P1-2、P1-3、P1-4、nextSteps 截断、非标准标签、欢迎语），详见文末「9. 已执行整改清单」。

---

## 1. 结论摘要

**总体判断：聊天主链路（上传 → 识别 → 卡片 → 确认 → 台账）两端已基本对齐，四类卡片结构一致，确认/修改/金额差异/费用关联的服务端协议完全同源。**

但存在 **1 个逻辑断裂（P0）**、**3 个核心交互不对齐（P1）** 和一批 P2 细节差异，且 **7 月 27 日报告中的 P1-1（局部失败触发全屏错误态）仍未修复**。

最需要优先处理的三个点：

1. **澄清卡（ClarificationCard）两端行为完全不同** —— Web 点选项后是「预填输入框 + 打开附件菜单引导重新上传」，Mini 是「把选项文字当普通业务文本直接发送」。Mini 端这条链路是断的，回答澄清后原单据无法继续处理。（P0）
2. **Mini 端局部操作失败仍会触发整页全屏错误态**，聊天/上传/所有模块全部消失，只剩「重新加载」。（P1，7-27 遗留未修复）
3. **发票方向 / 结算归属的聚焦确认（invoice_unknown / settlement_unknown）在 Mini 卡片上缺失**，且编辑浮层的类型 chip 中也没有对应的快捷转换入口，用户不知道该往哪改。（P1）

---

## 2. 全链路对齐矩阵

| 链路环节 | Web（ConnectedWorkspacePage） | Mini（workspace.wxml + index.js） | 结论 |
|---|---|---|---|
| 消息流渲染（TEXT / FILE / CARD） | ✅ | ✅ | 一致 |
| 消息时间 / 失败标记 | ✅ `failed → 发送失败` | ✅ `failed → 发送失败，请重试` | 一致 |
| 处理中动画（busy） | ✅ | ✅ | 一致 |
| 空态欢迎语 | 简短一句 | 长欢迎语 + emoji | 风格差异（P2） |
| 上传文件 + guidance 说明 | ✅ 附件菜单选择 | ✅ 拍照/相册/聊天文件/手动 | 一致（入口适配） |
| 附件待发送预览 + 移除 | ✅ | ✅ | 一致 |
| 单据识别卡 document_review | ✅ | ✅ | 一致（结构） |
| 卡片状态（待确认/已写入/已归档/待回填） | ✅ | ✅ | 一致 |
| 确认按钮文案与禁用条件 | ✅ | ✅ | 一致 |
| 修改识别字段（编辑表单） | ✅ 卡片内联编辑 | ✅ 浮层编辑 | 交互形式不同，功能对齐（P2 差异见 4.3） |
| 材料明细表格（7 列） | ✅ | ✅ 横向滚动 | 一致 |
| 金额差异卡（含决策按钮） | ✅ | ✅ | 一致 |
| 费用候选选择（expenseCandidates） | ✅ | ✅ | 一致 |
| 发票方向确认（invoice_unknown） | ✅ 聚焦二选一 | ❌ **缺失** | P1 |
| 结算归属确认（settlement_unknown） | ✅ 聚焦二选一 | ❌ **缺失** | P1 |
| typeOverride 提示条 | ✅ | ⚠️ 仅副标题文字 | P2 |
| 重复单据卡 duplicate_review | ✅ | ✅ | 一致 |
| 操作结果卡 operation_result | ✅ | ✅ | 一致 |
| 澄清追问卡 clarify_card | ✅ 选项→预填+引导上传 | ❌ **选项→直接发文本** | P0 |
| 下一步建议（nextSteps） | ✅ 侧栏全部展示 | ✅ 消息流底部折叠展示 | 数据口径一致，但 Mini 截断为 4 条（P2） |
| 上下文切换刷新 + 竞态保护 | ⚠️ 依赖 useEffect | ✅ contextRequestId | Mini 已修复（7-27 P1-3） |
| 401 自动重登 | ✅ | ✅ | 一致 |
| 局部失败错误边界 | ✅ 聊天区内提示 | ❌ 全局 error → 全屏错误页 | P1（7-27 P1-1 未修复） |
| 上传失败重试恢复 | ❌ 丢失附件与说明 | ❌ 丢失附件与说明 | 两端共同优化（P1） |

---

## 3. P0：逻辑断裂（必须先修）

### P0-1 澄清卡选项在 Mini 端被当作普通文本发送，原单据无法继续处理

**Web 端行为**（`ConnectedWorkspacePage.tsx:1033-1036`）：

```tsx
onChoose={async (answer) => {
  setChatInput(`请按"${answer}"处理这份资料：`);
  setAttachmentOpen(true);
}}
```

即：用户点击澄清选项后，**不会直接发送**，而是把答案预填进输入框并打开附件菜单，引导用户**重新上传原资料**（携带 guidance）。因为后端在 `clarify_card` 时**不创建 document、不保留原文件与 AI 结果的关联**（`DocumentService.uploadAndRecognize` 中 `doc = null`），所以「回复澄清」只能通过「重新上传 + 处理说明」完成。

**Mini 端行为**（`index.js:1033-1036`）：

```js
chooseClarification(event) {
  const answer = event.currentTarget.dataset.answer;
  this.sendPlainText(answer, 'clarification_reply');
}
```

直接把「按用户说明处理 / 按识别结果处理 / 重新上传并说明」这类选项文字当成业务文本发给 `/api/chat/send`。后端 `sendText` → `analyzeText("按用户说明处理")`，AI 大概率返回 `non_business` 或再次 `clarify_card`，**用户选完选项后什么都不会发生，原单据既没被处理也没被追问**，并且 `businessType: 'clarification_reply'` 根本不会传给后端（`api.sendChat` 只传 text/projectId/batchId），是无效参数。

**影响**：Mini 端「AI 追问」功能实际不可用——用户回答后系统无法继续，属于体验上的断链。

**建议**：
1. 短期对齐 Web：`chooseClarification` 改为「把 `请按"${answer}"处理这份资料：` 填入输入框 + 打开附件菜单（openAttachmentMenu）」，让用户重新选择原附件。
2. 根治（后端 + 两端）：给 `clarify_card` 消息持久化「待澄清单据草稿」（文件名、临时文件 key、AI 识别中间结果），提供「对澄清作答并继续识别原附件」的接口，用户选完选项无需重新上传。这是两端共同的体验优化，优先级最高。

---

## 4. P1：核心交互不对齐

### 4.1 Mini 端局部失败仍触发全屏错误态（7-27 P1-1 未修复）

`index.wxml:30-36`：`<block wx:elif="{{error}}">` 只要 `error` 非空就隐藏全部五个业务模块，显示「😿 + 重新加载」。

而 `index.js` 中**局部失败同样写入全局 error**：

- `sendPlainText` 失败 → `this.setData({ messages, error })`
- `sendMessage` 失败 → 同上
- `runDocumentAction`（确认/重复/费用关联/金额决策）失败 → `this.setData({ error })`

**影响**：一次「发送失败」或「确认失败」会让整个工作台变成全屏错误页，聊天记录、卡片、所有模块全部不可见，只能「重新加载」。Web 端同样场景只是在消息流底部显示错误行。

**建议**：拆分 `bootstrapError`（初始化失败 → 全屏）与 `chatError`（局部失败 → 聊天区提示，即 workspace.wxml 里已有的 `chat-error` 块），全屏错误只保留给初始化阶段。

### 4.2 invoice_unknown / settlement_unknown 聚焦确认缺失

Web 端在单据卡内有两类「聚焦确认」块（`ConnectedWorkspacePage.tsx:516-540`）：

- `invoice_unknown`：显示「已确认这是一张发票」+ 两个按钮：**本单位购买，进项发票** / **本单位销售，销项发票**（点击即 `onEdit(docType)`）
- `settlement_unknown`：显示「已确认这是结算资料」+ 两个按钮：**作为销售结算资料** / **仅作原始凭证**

Mini 端 `workspace.wxml` 的 `doc-card` **完全没有这两个块**。用户遇到「待确认发票 / 待确认结算资料」时：
- 卡片上只有「还需补充：发票方向 / 结算归属」的橙色提示
- 「修改识别字段」浮层的类型 chip 里也没有这两个未知类型（`EDITABLE_DOC_TYPES` 只含 10 种正式类型），用户需要自己意识到「要在类型里手动改成进项/销项发票」

**影响**：AI 无法确定发票方向/结算归属时，Mini 用户缺少 Web 端的一键决策路径，很容易卡在「请先补充必填字段」上不知所措。

**建议**：在 Mini `doc-card` 中补齐这两个聚焦确认块（两按钮 → 直接 `api.editDocument(id, { docType })`），并保持与 Web 一致的文案。

### 4.3 供货单明细编辑缺少件数 / 单重 / 总重量字段

Web 端编辑态：所有支持明细的类型（含供货单）都能编辑**全部 7 列**（材料/规格/件数/总重量/计价数量/单价/金额 + 单位）。

Mini 端浮层 `overlays.wxml` 的 `item-edit`：只有 `docType === 'delivery_note'` 时才渲染「件数/件数单位/总重量/重量单位」输入框（`overlays.wxml:212-221`）；**供货单等其他类型只能编辑材料/规格/计价数量/计价单位/单价/金额**，件数与总重量不可改。

**影响**：AI 识别供货单的件数/单重/总重量出错时，Mini 端无法修正（虽然 `decorateCardMessage` 和台账都用了这些字段）。Web 端能改，Mini 不能，能力不对等。

**建议**：把 `item-edit` 中件数/单重/总重量/单位四列改为对所有 `supportsItems` 类型开放（与 Web 一致），或至少在供货单编辑时也显示。

### 4.4 上传失败无恢复、附件与说明丢失（两端共同）

两端行为一致地「发送前清空 pendingFile + 输入框，失败后只把消息标红」：

- Web：`submitChat` 先 `setPendingFile(null); setChatInput('')` 再 `onUpload`；失败仅 `failed: true` + error
- Mini：`sendMessage` 先 `setData({ pendingFile: null, composerText: '' })`；失败仅 `failed: true`

**影响**：弱网/后端抖动时，用户必须重新选文件、重新填写处理说明，且失败提示是「请重试」但没有重试按钮。7-27 报告 P1-9 未修复，两端同样存在。

**建议**：失败时保留附件与说明（乐观消息挂载 filePath/文件名/guidance），消息旁提供「重试 / 删除」；成功后再释放。

---

## 5. P2：细节差异与共同优化点

### 5.1 Mini 端

1. **下一步建议截断**：`buildNextSteps` 返回 `steps.slice(0, 4)`，当批次同时缺「付款记录」「进项发票」等时，建议被截掉，与 Web 侧栏全量展示不一致。建议去掉截断，折叠展示（已有 nextStepsRest 机制）。
2. **欢迎语风格**：Mini 用「你好，我是你的采购业务助手 👋」+ 长文案，与 Web 企业工具风（一句话提示）不一致，且导航副标题已写「AI 生成内容仅供参考」。建议文案与 Web 对齐，去掉 emoji。
3. **WXML 非标准标签残留**：`workspace.wxml` / `overlays.wxml` 仍大量使用 `<strong>`、`<small>`（7-27 报告 P1-11 指出的问题**实际未清理完**）。小程序原生组件不支持这些标签，需在开发者工具与真机验证渲染；建议统一替换为 `<text class="strong|small">`。⚠️ 若真机不渲染，卡片金额、状态文字等关键信息会丢失，务必优先验证。
4. **`resolveAmountMismatch` 与编辑态决策的文案**：卡片快捷决策「保留总额并登记差异」→ 后端 `keep_source`，编辑态浮层「保留单据总额」→ 同一语义，文案两端略有出入，建议统一。
5. **空数字静默置 0**（7-27 P1-5 仍存在）：`constants.js parseAmount` 与 `domain.js number()` 对空值/非法值返回 0，编辑保存时税额/数量可能被无声清零。
6. **创建项目+首批非原子**（7-27 P1-6 仍存在）：`createProject()` 两步请求，第二步失败留无批次项目。
7. **卡片确认按钮的 `is-disabled` 仅为样式**：`confirmCard` 虽有 `canConfirm` 守卫，但「确认」按钮在 `busy` 时只是样式禁用，快速双击依赖守卫逻辑（已有），建议补 `if (busy) return` 统一。

### 5.2 Web 端

1. **发送/上传失败无重试**（与 Mini 相同，见 4.4）。
2. **澄清卡作答需重新上传**：交互上「回答 → 重新选文件」两步略显笨拙，建议后端支持「对澄清作答并继续原附件」，两端一次性解决（见 P0-1 根治方案）。
3. **消息流无分页**：长对话全量渲染，建议按需加载/虚拟滚动（与 Mini 共同问题）。
4. **`nextSteps` 空态判断**：`confirmedTypes` 基于 `status === '已匹配'`（含 archived），但 Web 的 `documentStatus` 把 `archived` 也映射为「已匹配」，口径与 Mini（matched/archived + 排除 skip）一致，无需改；**但建议统一抽成共享规则**，避免后续漂移。
5. **已入库资料编辑**：DataCenterPage 允许编辑已入库单据（后端允许 matched/archived 改字段、禁改 docType），与 Mini 浮层一致，但 Web 卡片上「修改识别字段」在 applied 后被禁用，而数据中心仍可改，语义需要确认是否故意（Web 卡片禁用合理，建议保留并加注释）。
6. **composer 高度动画**：textarea 高度用 `useLayoutEffect` 每次输入重算，高频输入有轻微性能开销，可忽略；若要做可加防抖。

### 5.3 后端（影响两端）

1. **clarify_card 无待澄清草稿持久化**（P0-1 根治的关键，见上）。
2. **接口无分页、无乐观锁、无幂等键**：多端并发修改存在最后写入覆盖风险（7-27 已提，仍未做）。
3. **错误码体系**：目前只有 `success/message`，前端无法机器化区分「缺字段/状态非法/重复」，两端都用文案兜底。
4. **`processingMode` 协议**：Web 类型声明 `'archive_only' | 'normal'`，后端实际 `'archive_only' | 'apply_to_ledger'`（7-27 已提），本轮确认两端运行时都未强依赖该字段，但仍建议统一。

---

## 6. 与 7-27 报告对比：已修复 / 未修复

| 7-27 问题 | 状态 |
|---|---|
| P1-2 抽屉项目切换只改标题不刷新 | ✅ 已修复（chooseContextProject 提交全局 + refreshContext + 失败回滚） |
| P1-3 快速切换请求竞态 | ✅ 已修复（contextRequestId + 上下文校验） |
| P1-1 局部失败触发全屏错误态 | ❌ **未修复** |
| P1-9 上传失败丢失附件与说明 | ❌ **未修复**（两端） |
| P1-11 WXML 非标准标签 | ⚠️ 部分清理，`<strong>/<small>` 仍大量存在 |
| P1-5 空值静默置 0 | ❌ 未修复 |
| P1-6 项目+首批非原子 | ❌ 未修复 |
| P1-8 台账「已对平」与「待补销售/待收款」矛盾 | ⚠️ domain.js `statusLabel: balanced ? '已对平' : '待补充'` 仍可能并存（exceptions 含待收/待补销售时 statusLabel 仍是已对平） |

---

## 7. 建议整改顺序

**第一批（阻断 / 断链，必须优先）**
1. Mini 澄清卡对齐 Web：选项 → 预填输入框 + 引导重新上传（P0-1 短期方案）
2. Mini 全局 error 拆分：bootstrapError / chatError（P1-1）
3. Mini 补齐 invoice_unknown / settlement_unknown 聚焦确认块（P1-2）

**第二批（能力对等）**
4. Mini 供货单明细编辑补件数/单重/总重量字段（P1-3）
5. 上传失败保留附件与说明 + 重试入口（两端）（P1-4）
6. Mini 下一步建议去掉 4 条截断

**第三批（体验与健壮性）**
7. WXML 非标准标签统一替换并真机验证
8. 澄清卡「作答继续原附件」后端草稿机制（根治 P0-1）
9. 空数字校验、项目+首批事务、台账状态口径统一
10. 接口分页 / 幂等 / 错误码

---

## 8. 一句话结论

两端「上传 → 识别 → 卡片 → 确认 → 台账」主链路已经打通且协议同源；**本轮真正要修的是 Mini 端的三处「看似有、实际不工作」**：澄清卡作答（P0）、局部失败全屏错误（P1）、发票/结算方向聚焦确认缺失（P1）；其次是两端共有的失败恢复与明细编辑能力对等。

---

## 9. 已执行整改清单（同日完成）

| 编号 | 整改项 | 涉及文件 | 说明 |
|---|---|---|---|
| P0-1 | 澄清卡对齐 Web | `index.js`（chooseClarification） | 不再直接发文本；预填「请按"X"处理这份资料：」+ 打开附件菜单 |
| P1-1 | 错误边界拆分 | `index.js`、`index.wxml` | 新增 `bootstrapError`（初始化失败→全屏），`error` 仅聊天区局部提示 |
| P1-2 | 发票/结算方向聚焦确认 | `domain.js`、`workspace.wxml`、`chat.wxss`、`index.js`（resolveFocusType） | 新增 `needsFocusConfirm/focusTitle/focusSub` 与二选一按钮 |
| P1-3 | 明细编辑补件数/单重/总重量 | `overlays.wxml`、`actions.js`、`sheets.wxss` | 放开到所有 supportsItems 类型；draft/新增/保存补 `unitWeight`（后端已支持） |
| P1-4 | 上传失败恢复 + 重试 | `index.js`（retryPayload/retryFailedMessage/deleteFailedMessage）、`workspace.wxml`、`chat.wxss` | 失败消息挂载文件与说明，提供「重试/删除」 |
| P2 | nextSteps 截断 | `index.js`（buildNextSteps） | 去掉 `slice(0, 4)` |
| P2 | 欢迎语对齐 | `workspace.wxml` | 改为 Web 同文案，去 emoji |
| P2 | 非标准标签替换 | `workspace.wxml`、`overlays.wxml`、`chat.wxss`、`sheets.wxss` | `<strong>/<small>/<i>` 全量替换为 `text/view` + 类名 |

**校验**：三个 JS `node --check` 通过；WXML 标签平衡检查通过；`decorateCardMessage` 冒烟测试（invoice_unknown / settlement_unknown / applied）通过。

**仍未处理（后续批次）**：
- 后端 clarify_card 草稿机制（根治 P0-1，需要后端持久化待澄清草稿 + 「作答后继续识别原附件」接口）
- P1-5 空数字静默置 0（parseAmount 返回结构改造）
- P1-6 项目+首批非原子创建（后端事务接口）
- P1-8 台账「已对平」与「待补销售/待收款」并存
- Web 端上传失败重试（两端共同项，本轮只改了 Mini）
- 接口分页 / 幂等 / 错误码
