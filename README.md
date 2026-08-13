# WeChat Download Improve

> 基于上游 `wechat-download-api` 进行二次开发与深度整合的工作区仓库。

本仓库作为 **工作区 / 聚合仓库**，通过 Git Submodule 引用并管理核心项目，便于在此基础上进行改造、集成与维护。

## 仓库结构

```
wechat-download-improve/
├── README.md                  # 本文件（工作区说明）
├── 深度整合设计.md            # 整合方案设计文档
├── wechat-download-api/       # 子模块：微信公众号抓取 / RSS / 多格式导出服务（Python）
└── wechat-download-kt/        # Kotlin 迁移版：核心路径（登录/文章/存储/feed/适配壳/通知）
```

| 目录 | 说明 |
|------|------|
| `wechat-download-api` | [Fork 自 tmwgsicp/wechat-download-api](https://github.com/FLC-niko/wechat-download-api)，Python/FastAPI 版本，功能完整 |
| `wechat-download-kt` | Kotlin 技术栈迁移版，聚焦核心路径，与 Kotlin Worker 技术栈统一 |
| `深度整合设计.md` | we-mp-rss-kotlin × wechat-download-api 深度整合设计方案 |

## 核心项目：wechat-download-api

一款完全开源的微信公众号文章获取、RSS 订阅 API 服务，支持：

- **RSS 订阅** — 订阅任意公众号，自动定时拉取新文章（含完整内容和图片），生成标准 RSS 2.0 源
- **MCP 接入** — 内置 MCP 服务，Claude / Cursor / Codex 等 AI 客户端可直接搜索、订阅、读文章
- **多格式导出** — 整号文章一键打包成 Markdown / HTML / Word / PDF / EPUB / Excel / JSON 7 种格式
- **反风控体系** — Chrome TLS 指纹模拟 + SOCKS5 代理池轮转 + 自动限频
- **扫码登录 / 图片代理 / Webhook 通知 / API 文档** 等完整功能

详细功能与部署文档见 [子模块 README](wechat-download-api/README.md)。

## Kotlin 迁移版：wechat-download-kt

`wechat-download-kt` 是 `wechat-download-api` 的 **核心路径 Kotlin 迁移版**，与 Kotlin Worker 技术栈统一（Ktor + OkHttp + Exposed + Coroutines）。

### 迁移范围

只迁移 Worker 依赖的主链路，不迁移正文抓取、多格式导出、MCP server、前端管理页面：

| 模块 | 说明 |
|---|---|
| 微信登录 | 服务端主动扫码，二维码推送到企业微信/飞书 |
| 通知机器人 | 企业微信 + 飞书，支持文字通知和二维码图片推送 |
| 文章列表抓取 | 调 `appmsgpublish` 接口，仅取元数据（零风控） |
| SQLite 存储 | 订阅表 + 文章表，对齐 Python 版 schema |
| feed 接口 | `/api/feed/articles.json` 增量游标拉取 |
| 适配壳 | `/api/v1/wx/*` 路由，Worker 无需改代码即可对接 |
| 后台轮询 | 定时拉取所有订阅号最新文章 |
| 登录提醒 | 凭证过期前 24h/6h 自动通知 |

### 技术栈

- **Ktor 2.3** + Netty（Web 框架）
- **OkHttp 4.11** + Gson 2.10（与 Worker 一致）
- **Exposed 0.46** + SQLite（ORM）
- **kotlinx.coroutines 1.7**（协程）
- **Logback 1.4**（日志）

详细使用说明见 [wechat-download-kt/README.md](wechat-download-kt/README.md)。

## 克隆与初始化

由于 `wechat-download-api` 是子模块，克隆时请使用 `--recurse-submodules`：

```bash
git clone --recurse-submodules https://github.com/FLC-niko/wechat-download-improve.git
```

如果已克隆但未拉取子模块：

```bash
git submodule update --init --recursive
```

## 二次开发说明

- **wechat-download-api**（Python）：改动需在**子模块仓库内**提交，再推送到 `origin`（你的 fork）。
- **wechat-download-kt**（Kotlin）：直接在本仓库内提交，是工作区的原生部分。
- 整合设计与方案见 [深度整合设计.md](深度整合设计.md)。
- Kotlin 迁移版的设计目标：与 Kotlin Worker [we-mp-rss-kotlin](https://github.com/FLC-niko/we-mp-rss-kotlin) 技术栈统一，通过 `/api/v1/wx/*` 适配壳对接，Worker 代码无需修改。

## License

- `wechat-download-api` 遵循 **AGPL-3.0** 协议，详见 [LICENSE](wechat-download-api/LICENSE)。
- `wechat-download-kt` 同样遵循 **AGPL-3.0** 协议（与上游保持一致）。
