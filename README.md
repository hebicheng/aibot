# 衡账 AI 项目对账平台

以“项目 + 批次”为核心数据单位，通过智能助理接收图片、PDF、Excel 和文字资料，将供货、到货、运输、请款、付款、发票与收款信息持续补充到项目批次台账。

## 当前核心链路

```text
创建项目与批次
  → 上传附件或输入业务文字
  → 千问多模态识别单据类型和结构化字段
  → 生成聊天待确认卡片
  → 人工修改、确认或处理重复资料
  → 写入业务数据
  → 增量更新材料、运输、票款和回款台账
  → 数据中心追溯原始资料并支持全链路修正
```

当前支持：

- 供货单确认后建立批次采购台账。
- 送货单回填材料到货数量与重量。
- 运输费用作为独立业务类目进入台账。
- 请款、付款和进项发票按材料、运输分别归集。
- 收款、销项开票、回款日期、毛利和项目经理按批次汇总。
- 图片、PDF、Excel 和手动文字统一进入智能助理。
- 附件提示词与附件共同展示并持久化。
- 重复文件和重复业务内容检测。
- 待确认卡片支持主字段及材料明细修改。
- 已入库数据修改后增量修正台账，不需要重建。
- 项目、批次、业务数据、原始附件、聊天记录和台账均从真实后端加载。

## 技术栈

```text
web/      React 19 + TypeScript + Vite
backend/  Spring Boot 3.3 + Java 17 + MyBatis-Plus + MySQL + Flyway
miniapp/  微信小程序历史客户端
design/   交互原型参考
```

外部服务：

- 腾讯云 COS：保存原始附件。
- 千问多模态模型：直接识别图片、PDF 和文件解析内容，不再使用独立 OCR。

## 数据职责

- AI 负责非结构化资料理解、单据分类、字段提取和冲突追问。
- 后端负责权限、数据库查询、状态流转、数据校验、重复检测和台账计算。
- AI 识别结果只是候选数据，人工确认后才会成为正式业务数据。
- 未付款、未回款、汇总金额和项目进度由后端确定性计算，不由 AI 推测。

## 配置与敏感信息

仓库里不放任何真实密钥，两类私有配置都在 `.gitignore` 中，只提交 `.example` 模板：

| 私有配置 | 位置（已忽略） | 提交到仓库的模板 | 用途 |
| --- | --- | --- | --- |
| 后端本地配置 | `backend/config/application-local.yml` | `backend/config/application-local.yml.example` | 数据库密码、微信 AppSecret、千问 API Key、COS 密钥 |
| 服务器环境变量 | `/opt/aibot/.env` | `deploy/aibot.env.example` | systemd 读取的线上配置 |

`application.yml` 本身全部是 `${环境变量:默认值}` 形式，默认值里没有任何真实凭据；
未显式指定 profile 时默认 `local`，Spring Boot 会自动加载工作目录下 `config/` 目录里的同名配置，
优先级高于 classpath 里的 `application.yml`，所以改密钥只改本地文件，不动仓库代码。

## 本地运行

### 后端

准备 MySQL 数据库 `purchase_ai_bot`，复制一份本地配置并填写：

```bash
cd backend
cp config/application-local.yml.example config/application-local.yml
# 编辑 application-local.yml：数据库账号、微信 AppID/AppSecret、千问 Key、COS 密钥
mvn spring-boot:run
```

想先跑起来、不接外部服务时：`application-local.yml` 里保持 `ai.mock: true`、`storage.type: local`，
三处密钥留空也能启动（聊天走结构化兜底，附件落本地 `./uploads`），然后建库即可：

```sql
CREATE DATABASE purchase_ai_bot DEFAULT CHARSET utf8mb4;
```

默认端口为 `8090`，Flyway 会自动执行数据库迁移。
用 IDE 启动时工作目录要指向 `backend/`，否则读不到 `config/application-local.yml`。

也可以用环境变量覆盖任意配置项：

```text
DB_URL  DB_USERNAME  DB_PASSWORD
TOKEN_SECRET  TOKEN_TTL_SECONDS
WECHAT_APP_ID  WECHAT_APP_SECRET  WECHAT_QR_ENV
QWEN_API_KEY  QWEN_MODEL
COS_SECRET_ID  COS_SECRET_KEY  COS_REGION  COS_BUCKET  STORAGE_TYPE
```

健康检查：

```bash
curl http://localhost:8090/actuator/health
```

### Web

```bash
cd web
npm install
npm run dev
```

默认访问地址为 `http://127.0.0.1:5173/`。

生产构建：

```bash
npm run build
```

## 验证

后端测试：

```bash
cd backend
mvn test
```

核心验收建议：

1. 创建项目和批次。
2. 上传供货单并确认，检查台账是否立即建立。
3. 乱序上传送货单、运输费用、请款、付款和发票。
4. 检查资料是否先进入待确认卡片，确认后台账是否增量更新。
5. 在数据中心修改已入库字段，检查相关金额和台账是否同步修正。
6. 刷新页面，检查聊天记录、附件提示词、业务数据和台账是否保持一致。

## 部署

服务器上只需要一份本地环境变量文件，密钥跟着机器走、不进仓库：

```bash
sudo mkdir -p /opt/aibot
sudo cp deploy/aibot.env.example /opt/aibot/.env
sudo chmod 600 /opt/aibot/.env
sudo vi /opt/aibot/.env          # 填真实 DB / 微信 / 千问 / COS 值
sudo cp deploy/aibot-backend.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl restart aibot-backend
```

`aibot-backend.service` 已通过 `EnvironmentFile=-/opt/aibot/.env` 读取上述文件（文件不存在时不报错）。
前端与 jar 分发沿用 `deploy/deploy.sh`。

## 当前阶段

核心端到端链路已经跑通。下一阶段重点是完善聊天意图路由，让业务查询由后端数据库工具执行，并继续补充异常匹配、审计和真实单据回归测试。
