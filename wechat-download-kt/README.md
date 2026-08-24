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
| **文章列表抓取** | 调 `appmsgpublish` 接口，仅取元数据（零风控风险） |
| **SQLite 存储** | 订阅表 + 文章表，对齐 Python 版 schema |
| **feed 接口** | `/api/feed/articles.json` 增量游标拉取 |
| **适配壳** | `/api/v1/wx/*` 路由，让 Worker 不改代码就能对接 |
| **后台轮询** | 定时拉取所有订阅号的最新文章 |
| **登录提醒** | 凭证过期前 24h/6h 自动 webhook 通知 |

## 快速开始

### ADB 自动恢复设备管理

Worker 不保存手机 IP 或序列号。启动时执行 `adb devices -l`，运行期间默认每
15 秒重新探查一次：只有状态为 `device` 的设备进入在线池，`offline` 和
`unauthorized` 不参与自动恢复。

```yaml
adb:
  enabled: true
  poll_interval_ms: 15000
```

当前恢复模式可通过以下接口查看：

```bash
curl http://localhost:5000/api/adb/status
```

当至少有一台 ADB 设备在线时模式为 `AUTO_RECOVERABLE`；没有在线设备时为
`MANUAL_ONLY`。设备接入、断开或授权状态变化会在下一次探查后自动切换。当前
恢复调度器使用返回的 `serial + user_ids` 槽位路由到主空间或分身空间，并通过
`/api/weread/account/bind` 保存账号与设备用户空间绑定。

失效账号可通过 `/api/weread/account/recover` 入队恢复。目标设备离线时，固定绑定账号
保持挂起并等待热插拔；只有明确设置 `ELASTIC_DEVICE` 才允许借用其他在线设备。没有在线
设备时不会执行任何 ADB 命令，只走机器人通知降级。

AAOS 登录责任链位于 `archive-system-crawling-android-master/crawlingservice`，部署前先构建
`app-debug.apk`，再运行 `./deploy_hyperos_phones.sh [/path/to/app-debug.apk]`。脚本会动态解析
每台设备的 Android 用户 ID，不假定 XSpace 为 999。

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
./gradlew build
```

### 3. 运行

```bash
./gradlew run
# 或
java -jar build/libs/wechat-download-kt-1.0.0.jar
```

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
│   └── ArticleStore.kt       # 文章存储 + feed 查询
├── feed/FeedRoutes.kt        # /api/feed/articles.json 增量接口
├── adapter/WxAdapterRoutes.kt# /api/v1/wx/* 适配壳
├── notify/
│   ├── WebhookNotifier.kt    # 企业微信 + 飞书 + 二维码推送
│   └── LoginReminder.kt      # 登录过期提醒
└── poller/RssPoller.kt       # 后台轮询器
```

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
