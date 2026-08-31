# wechat-download-kt

> `wechat-download-api` 的 Kotlin 技术栈迁移版，聚焦 **核心路径**（登录 / 文章列表 / 存储 / feed / 适配壳 / 通知），配合 Kotlin Worker [we-mp-rss-kotlin](../) 使用。

## 为什么有这个项目

原 `wechat-download-api` 是 Python/FastAPI 项目，与 Kotlin Worker 的技术栈不统一，部署和二次开发成本高。本项目把 Worker 实际依赖的主链路迁移到 Kotlin（Ktor + OkHttp + Exposed），让整个系统统一在一个技术栈下。

**只迁移核心路径**，不迁移正文抓取、多格式导出、MCP server、前端管理页面（这些在 Python 生态更强）。

## 技术栈

| 模块 | 选型 |
|---|---|
| Web 框架 | Ktor 2.3 + Netty |
| HTTP 客户端 | OkHttp 4.11（与 Worker 一致） |
| JSON | Gson 2.10（与 Worker 一致） |
| 数据库 | SQLite + Exposed ORM 0.46 |
| 配置 | SnakeYAML + 环境变量覆盖 |
| 协程 | kotlinx.coroutines 1.7 |
| 日志 | Logback 1.4 |

## 功能模块

| 模块 | 说明 |
|---|---|
| **微信登录** | 服务端主动扫码登录，自动获取 token/cookie/fakeid |
| **通知机器人** | 企业微信 + 飞书，支持文字通知 + 二维码图片推送 |
| **文章列表抓取** | 微信读书 Web 为当前主采集通道；`appmsgpublish` 默认关闭 |
| **SQLite 存储** | 订阅表 + 文章表，对齐 Python 版 schema |
| **feed 接口** | `/api/feed/articles.json` 增量游标拉取 |
| **适配壳** | `/api/v1/wx/*` 路由，让 Worker 不改代码就能对接 |
| **MQ 任务采集** | 消费 BTA，按覆盖度执行多通道降级，向同一 vhost 回复一条终态 ATD |
| **后台轮询** | 可选；MQ Worker 模式默认关闭，避免与任务争抢账号配额 |
| **登录提醒** | 凭证过期前 24h/6h 自动 webhook 通知 |

## 快速开始

### 当前上线模式：不依赖 ADB

默认配置不会执行 `adb devices`，也不会启动 ADB 设备监督；凭证会被主动探测并记录状态，但自动二维码恢复默认关闭，避免服务重启或无人值守时意外发起扫码。需要时可显式打开对应的自动恢复开关。已有本地订阅优先使用微信读书 E-Ink 通道，并由 Web 与本地缓存兜底。`appmsgpublish` 返回 `200013` 已确认是通道失效标志，不是降低频率后可恢复的临时频控，因此主通道默认关闭。

```yaml
auth:
  auto_recovery_enabled: false

weread:
  auto_relogin_enabled: false
  eink:
    enabled: true
    auto_relogin_enabled: false

adb:
  enabled: false
  poll_interval_ms: 15000

rss:
  enabled: false

wx:
  main:
    enabled: false
```

需要恢复凭证时使用服务端扫码登录接口和机器人通知。将 `auth.auto_recovery_enabled`、`weread.auto_relogin_enabled` 或 `weread.eink.auto_relogin_enabled` 显式设为 `true` 后，才会在对应通道失效时自动恢复；ADB 设备恢复还需要显式设置 `adb.enabled: true`。ADB 状态仍可通过以下接口查看：

```bash
curl http://localhost:5000/api/adb/status
```

关闭 ADB 时 `/api/weread/account/recover` 会明确拒绝设备恢复请求，可使用 `/api/weread/login` 发起微信读书手动扫码。

### 1. 配置

```bash
cp config.example.yaml config.yaml
```

编辑 `config.yaml`：

```yaml
server:
  host: 0.0.0.0
  port: 5000
  site_url: http://localhost:5000

store:
  db_path: data/rss.db

rss:
  enabled: false
  poll_interval: 3600
  articles_per_poll: 10

notification:
  wecom_webhook_url: "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=你的key"
  feishu_webhook_url: "https://open.feishu.cn/open-apis/bot/v2/hook/你的token"
  interval: 300

adapter:
  api_key: "你的适配壳密钥"
```

### 2. 构建

```bash
./gradlew clean test installDist
```

### 3. 运行

```bash
./build/install/wechat-download-kt/bin/wechat-download-kt
```

普通 Gradle jar 不包含全部运行时依赖，生产环境请使用 `installDist` 生成的目录整体部署。

## MQ 任务与降级语义

任务路径如下：

```text
BTA 解码 → Inbox 租约/幂等 → 校验时间窗口 → 本地解析公众号身份
  → E-Ink（默认开启，需先扫码取得凭证）→ appmsgpublish（默认关闭）→ WeRead Web → SQLite 合并
  → 覆盖度决策 → 发布一条 ATD → Inbox COMPLETED → ACK
```

- `SUCCEEDED`：窗口内至少采集到一个去重 URL。
- `EMPTY`：远端通道已经翻到窗口起点以前或明确到底，并确认窗口内没有文章。
- `FAILED`：输入非法、账号无法解析，或所有通道都不能证明窗口已覆盖；不会把“不确定”误报成空。
- ATD 发布使用 publisher confirm；发布失败会抛出，由原 BTA 走 broker 重试。重试复用 Inbox 中的 `resultEventId`，下游可按事件 ID 幂等。
- 任务执行期间每分钟续租；broker redelivery 可立即接管因进程崩溃遗留的 `PROCESSING` 记录。
- 重试耗尽或消息格式非法会进入当前 vhost 的 `dead-message-queue`，不再静默丢弃。

当前 Web 通道采用 `createTime` 游标翻页，识别 `-2012`（凭证失效）、`-2014`（频控）、`-2041`（人工验证）、HTTP 429 与 5xx。单账号限流状态在进程内全局共享，冷却到期无需重启即可恢复。

主微信 `ret=200013` 单独归类为 `INVALIDATED`：进程不会冷却后重试，状态接口会暴露 `main_invalidated` 和原因。写入新凭证可以清除旧凭证的失效标记，但只有显式设置 `wx.main.enabled: true` 才会重新启用该通道。

## 开源项目调研后的取舍

- 借鉴 `weread-omni`：E-Ink `/mp/chapters`、synckey 增量、`-2014/-2041` 明确错误语义和 refresh token 会话模型。当前将 E-Ink 作为首选采集通道；未配置凭证时自动跳过，不阻塞 Web 与缓存兜底。
- 借鉴 `weread-mp`：Web `/web/mp/articles` 使用最老文章 `createTime` 作为下一页游标，短页表示到底。
- 结合 `we-mp-rss` 的频控反馈：按账号冷却、全局串行与最小请求间隔，避免多个入口各自限流造成叠加请求。

### 4. 发起登录

```bash
curl -X POST http://localhost:5000/api/login/start
```

系统会自动：
1. 初始化微信登录会话
2. 获取二维码并保存到 `qrcode/wx_qrcode.png`
3. 推送二维码到企业微信（图片）和飞书（卡片消息含链接）
4. 轮询扫码状态
5. 扫码确认后自动保存凭证

## API 速览

### 登录

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/login/status` | 获取登录状态 |
| POST | `/api/login/start` | 发起服务端主动登录 |
| POST | `/api/login/manual` | 手动提交凭证 |
| POST | `/api/login/logout` | 清除凭证 |

### 文章

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/public/articles?fakeid&begin&count` | 直接调微信 API 获取文章列表 |
| GET | `/api/articles?fakeid&limit&offset` | 查询本地已存储的文章 |
| GET | `/api/article/{id}` | 查询文章详情 |

### Feed（增量同步）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/feed/articles.json?since&fakeid&limit` | 按 `publish_time` 游标增量拉取 |

### 适配壳 `/api/v1/wx/*`（供 Worker 调用）

| 方法 | 路径 | Worker 对应调用 |
|---|---|---|
| POST | `/api/v1/wx/auth/login` | `AuthApi.login()` |
| GET | `/api/v1/wx/mps?limit&offset&kw` | `MpApi.getMpList()` |
| POST | `/api/v1/wx/mps` | `MpApi.addMp()` |
| GET | `/api/v1/wx/mps/search/{keyword}` | `MpApi.searchPublicAccounts()` |
| GET | `/api/v1/wx/mps/update/{mpId}?start_page&end_page` | `MpApi.refreshMpArticles()` |
| GET | `/api/v1/wx/articles?mp_id&limit&offset` | `ArticleApi.getArticles()` |

统一返回格式：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "list": [...], "total": N }
}
```

## 通知机器人配置

### 企业微信群机器人

1. 在企业微信群中添加机器人，获取 Webhook URL
2. 填入 `config.yaml` 的 `notification.wecom_webhook_url`
3. 支持的能力：
   - markdown 文字通知
   - 图片推送（base64 + md5，用于二维码）

### 飞书机器人

1. 在飞书中创建自定义机器人，获取 Webhook URL
2. 填入 `config.yaml` 的 `notification.feishu_webhook_url`
3. 支持的能力：
   - interactive 卡片消息（标题 + lark_md 内容）
   - 二维码推送（卡片含可点击的二维码链接）

### 通知事件

| 事件 | 说明 |
|---|---|
| `login_success` | 登录成功 |
| `login_failed` | 登录失败 |
| `login_expiring_soon` | 登录 24h 内过期 |
| `login_expiring_critical` | 登录 6h 内过期 |
| `login_expired` | 登录已过期 |
| `qr_code_pushed` | 二维码已推送 |

## 与 Python 版的差异

| 能力 | Python 版 | Kotlin 版 |
|---|---|---|
| 登录方式 | 浏览器代理扫码 | 服务端主动扫码 + 机器人推送二维码 |
| 文章正文抓取 | 支持（有风控风险） | 不支持（只取元数据，零风控） |
| 多格式导出 | Markdown/HTML/PDF/DOCX/EPUB/XLSX/JSON | 不支持 |
| MCP server | 支持 | 不支持 |
| 前端管理页面 | 13 个 HTML 页面 | 不支持 |
| 适配壳 | 无 | `/api/v1/wx/*` 全套（Worker 无需改代码） |

## 项目结构

```
src/main/kotlin/com/wamp/download/
├── Main.kt                   # Ktor 入口 + 后台任务启动
├── config/AppConfig.kt       # YAML + 环境变量配置
├── auth/
│   ├── AuthManager.kt        # 凭证管理（文件持久化）
│   ├── LoginService.kt       # 微信服务端主动扫码登录
│   └── AuthRoutes.kt         # /api/login/* 路由
├── article/
│   ├── ArticleFetcher.kt     # appmsgpublish 文章列表抓取
│   └── ArticleRoutes.kt      # /api/articles /api/public/articles
├── store/
│   ├── Database.kt           # SQLite + Exposed 表定义
│   ├── SubscriptionStore.kt  # 订阅管理 CRUD
│   ├── ArticleStore.kt       # 文章存储 + feed 查询
│   └── InboxStore.kt         # MQ 幂等、稳定结果事件 ID 与执行租约
├── task/
│   ├── TaskProcessor.kt      # 覆盖度驱动的采集编排与终态发布
│   └── CollectionCoverage.kt # 覆盖范围和 EMPTY 判定纯函数
├── rabbitmq/                 # 多 vhost、confirm、重试、ATD 与 DLQ
├── weread/                   # Web 多账号池、全局限流、错误分类与翻页
│   └── eink/                 # 可选 E-Ink 增量实验通道
├── feed/FeedRoutes.kt        # /api/feed/articles.json 增量接口
├── adapter/WxAdapterRoutes.kt# /api/v1/wx/* 适配壳
├── notify/
│   ├── WebhookNotifier.kt    # 企业微信 + 飞书 + 二维码推送
│   └── LoginReminder.kt      # 登录过期提醒
└── poller/RssPoller.kt       # 后台轮询器
```

`bin/` 是历史镜像目录，Gradle 构建只使用 `src/`；发布和修改均以 `src/` 为准。

## 上线前安全检查

不要把 `config.yaml`、账号 cookie、RabbitMQ 密码、Webhook URL 或适配壳密钥提交到仓库。若这些值曾出现在受版本控制的文档或历史提交中，应先轮换凭证，再清理仓库历史。

## 与 Worker 对接

Worker 的配置中指向本服务即可，无需修改 Worker 代码：

```yaml
# Worker 的 config.yaml
api:
  base_url: "http://localhost:5000"
  username: "adapter"
  password: "你的适配壳密钥"
```

详见 [深度整合设计](../深度整合设计.md)。
