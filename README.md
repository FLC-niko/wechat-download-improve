# WeChat Download Improve

> 基于上游 `wechat-download-api` 进行二次开发与深度整合的工作区仓库。

本仓库作为 **工作区 / 聚合仓库**，通过 Git Submodule 引用并管理核心项目，便于在此基础上进行改造、集成与维护。

## 仓库结构

```
wechat-download-improve/
├── README.md                  # 本文件（工作区说明）
└── wechat-download-api/       # 子模块：微信公众号抓取 / RSS / 多格式导出服务
```

| 目录 | 说明 |
|------|------|
| `wechat-download-api` | [Fork 自 tmwgsicp/wechat-download-api](https://github.com/FLC-niko/wechat-download-api)，本工作区二次开发的核心服务 |

## 核心项目：wechat-download-api

一款完全开源的微信公众号文章获取、RSS 订阅 API 服务，支持：

- **RSS 订阅** — 订阅任意公众号，自动定时拉取新文章（含完整内容和图片），生成标准 RSS 2.0 源
- **MCP 接入** — 内置 MCP 服务，Claude / Cursor / Codex 等 AI 客户端可直接搜索、订阅、读文章
- **多格式导出** — 整号文章一键打包成 Markdown / HTML / Word / PDF / EPUB / Excel / JSON 7 种格式
- **反风控体系** — Chrome TLS 指纹模拟 + SOCKS5 代理池轮转 + 自动限频
- **扫码登录 / 图片代理 / Webhook 通知 / API 文档** 等完整功能

详细功能与部署文档见 [子模块 README](wechat-download-api/README.md)。

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

- 对 `wechat-download-api` 的改动需在其**子模块仓库内**提交，再推送到 `origin`（你的 fork）。
- 本工作区用于整合与联调（如 we-mp-rss-kotlin 等周边项目的深度整合），整合设计与方案见 [深度整合设计.md](wechat-download-api/深度整合设计.md)（本地文件）。

## License

`wechat-download-api` 遵循 **AGPL-3.0** 协议，详见 [LICENSE](wechat-download-api/LICENSE)。
