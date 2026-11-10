# Token 计费 / 积分功能 · 实施记录

> 日期：2026-08-22 ｜ 状态：已实现（后端编译通过、web 类型检查通过）

## 一、核心结论

1. **token 由大模型返回**：每次响应体 `usage` 字段含 `prompt_tokens / completion_tokens / total_tokens`。
   图片 token 已被模型计入 `prompt_tokens`，**无需自行按分辨率估算**。
2. **计费公式**：
   ```
   costCredits = totalTokens × pricePerToken × ratio
   ```
   - `ratio`（商业加价倍率，默认 10，客户无感知，后台可调）
   - `pricePerToken`（积分换算系数，默认 1；"1 真实 token = 1 积分"）
   - 用户感知的只有「积分」，不看到 token 与倍率。
3. **决策（已确认）**：
   - 计费主体 = 单 `user` 账号（暂不做 company 聚合）
   - 余额不足 = 拦截停用（chat 直接 402；market 匹配降级跳过计费不影响主流程）
   - "10 倍"含义 = 客户按真实成本 10 倍计价（纯商业加价，非图片额外放大）
   - 初版 `ratio=1`、`pricePerToken=1`（先看效果再调）

## 二、数据层（V8__billing.sql）

| 表 | 作用 |
|---|---|
| `ai_usage_logs` | 每次模型调用明细（user/project/batch/model/business_type/prompt/completion/total tokens/bill_tokens/cost_credits/ref_message_id） |
| `user_credits` | 每用户一行（balance/total_granted/total_used/monthly_quota/period_month/period_start/period_end） |
| `credit_transactions` | 流水（consume / grant 审计） |

动态参数预留：`billing_config`（ratio / price_per_token / monthly_quota），当前由 `BillingProperties` + yml 兜底，后续可接库表实时调。

## 三、代码改动

### 后端
- `QwenClient`：`completeJson/completeMultimodal` 返回 `QwenResult{content, usage}`，解析响应体 `usage`。
- `ChatAiService`：`analyzeText/analyzeFile` 返回 `AiAnalysisResult{reply, usage}`（mock 分支 usage=0）。
- `BillingService`：`charge(userId, projectId, batchId, businessType, model, usage, refMessageId)` 记账+扣余额；`chargeMarketMatch(batchId, usage, model)` 按 batch→owner 解析用户、余额不足降级跳过；`snapshot` / `usageLogs` / `countUsageLogs`。
- `DocumentService`：聊天 `sendText`/`uploadAndRecognize` 拿到 AI 结果后 `charge("chat", ...)` 并写 `chat_messages.credits_used`。
- `MaterialMarketMatchService.matchByAi`：`chargeMarketMatch("market_match", ...)`。
- `ChatService`：`InsufficientCreditsException` → 转 HTTP 402。
- `BillingController`：`GET /api/billing/credits`、`GET /api/billing/usage-logs?page&size`（返回 `{items, total}`）。
- `CreditAccount.getOrCreate`：新用户建账户，默认月配额 1500。
- 每月刷新：定时任务（每月 1 号 0 点）重置 balance = monthly_quota，写 grant 流水。

### 前端
- **web**：`api.ts` 加 `billingCredits`/`billingUsageLogs`；`ConnectedWorkspacePage` AI 气泡下方显示「本次消耗积分 N」；`App.tsx` 用户菜单加「积分与消费明细」入口 + 弹层（余额/本月已用/月配额/消费记录）。
- **小程序**：`domain.decorateCardMessage` 透传 `creditsUsed`；`workspace.wxml` AI 消息下显示积分；`profile.wxml` 加积分余额卡片 + 菜单入口 + 消费明细底部弹层；`actions.js` 加 `openBilling/closeBilling/noop`；`api.js` 加 `billingCredits/billingUsageLogs`。

## 四、验证
- `mvn -o clean compile` → BUILD SUCCESS
- `npx tsc --noEmit --project tsconfig.app.json` → exit 0

## 五、后续可调项
1. `ratio` / `pricePerToken` 初值看效果后调整（落 `billing_config` 表实现客户无感知实时调）。
2. `user_credits.origin` 历史脏数据（之前批次）可清 NULL（与计费无关，无害）。
3. 每月刷新定时任务需在线上环境验证首次发放。
