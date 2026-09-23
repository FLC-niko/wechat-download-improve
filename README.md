# WeChat Download Improve

> 微信公众号数据采集系统的工作区仓库：以 `wechat-download-kt`（服务端）为核心，配合独立仓库的手机端 `archive-system-crawling-android`（AAOS）使用。

本仓库作为 **工作区 / 聚合仓库**，原生跟踪服务端项目 `wechat-download-kt`；手机端与 Worker 侧项目以独立克隆形式放在工作区目录中，便于联调（历史备注：Python 版 `wechat-download-api` 子模块已移除，仅保留 `wechat-download-kt` 作为其核心路径的 Kotlin 迁移实现）。

## 仓库结构

```
wechat-download-improve/
├── README.md                              # 本文件（工作区说明）
├── 交接文档.md                            # ⭐ 交接文档（含凭据，本地维护、不入库，见 .gitignore）
├── 三阶段执行计划.md                      # 系统路线与执行状态（设计基线）
├── wechat-download-kt/                    # 服务端：Kotlin 采集服务（本仓库原生跟踪）
├── archive-system-crawling-android-master/  # 手机端 AAOS（独立仓库，目录内克隆）
└── we-mp-rss-all/                         # Worker 侧工作副本（独立克隆，未纳入本仓库版本管理）
```

| 目录 | 说明 |
|------|------|
| `wechat-download-kt` | Kotlin 服务端：MQ 任务采集（微信读书 Web/E-Ink 账号池）、feed/适配壳接口、ADB 代扫、通知机器人 |
| `archive-system-crawling-android-master` | [FLC-niko/archive-system-crawling-android](https://github.com/FLC-niko/archive-system-crawling-android)：手机端无障碍自动化采集（公众号），MQ 消费/死信兜底与 ADB 扫码 |
| `we-mp-rss-all` | Worker 侧（`we-mp-rss-kotlin` 等）独立克隆，配套使用但不由本仓库管理 |
| `交接文档.md` | ⭐ 交接入口：架构、部署、运维、故障排查、验收清单、凭据汇总（**本地维护、不入库**，见 `.gitignore`） |
| `三阶段执行计划.md` | 系统路线（官方直连 / 账号池 / 手机集群）与调研结论 |

## 核心项目

本工作区围绕两个项目构成一套「服务端 + 手机集群」的公众号采集系统：

| 项目 | 角色 | 文档 |
|---|---|---|
| `wechat-download-kt` | 服务端：MQ 任务消费、微信读书通道与账号池、ADB 代扫、通知机器人 | [README](wechat-download-kt/README.md) · [服务器部署运维](wechat-download-kt/docs/服务器部署运维.md) |
| `archive-system-crawling-android` | 手机端：无障碍自动化采集公众号、消费死信任务兜底 | [README](archive-system-crawling-android-master/README.md) |

- 系统架构、两个项目的联动方式、故障排查与交接验收清单：**[交接文档.md](交接文档.md)**（本地维护、不入库）
- 历史背景：`wechat-download-kt` 是 Python 版 `wechat-download-api` 的**核心路径 Kotlin 迁移版**（Python 子模块已从本仓库移除）。

## Kotlin 迁移版：wechat-download-kt

`wechat-download-kt` 是 `wechat-download-api` 的 **核心路径 Kotlin 迁移版**，与 Kotlin Worker 技术栈统一（Ktor + OkHttp + Exposed + Coroutines）。

### 迁移范围

只迁移 Worker 依赖的主链路，不迁移正文抓取、多格式导出、MCP server、前端管理页面：

| 模块 | 说明 |
|---|---|
| MQ 任务采集 | 消费 BTA 任务（pro/test/xdag/thdag），多通道降级采集后回写 ATD 终态 |
| 微信读书账号池 | 多账号调度 + 频控冷却 + 请求节流与伪装；Web 与 E-Ink 双通道 |
| 文章列表抓取 | 缓存优先 + 覆盖度判定；`appmsgpublish` 主通道默认关闭（`200013` 永久失效） |
| SQLite 存储 | 订阅表、文章表、覆盖度记录，对齐 Python 版 schema |
| feed 接口 | `/api/feed/articles.json` 增量游标拉取 |
| 适配壳 | `/api/v1/wx/*` 路由，Worker 无需改代码即可对接 |
| ADB 代扫 | 可选：二维码推送手机 + 自动点击授权 + 离线 OCR 自愈 |
| 登录提醒 | 凭证过期前 24h/6h 自动通知；失效自动推送二维码供人工扫码 |

### 技术栈

- **Ktor 2.3** + Netty（Web 框架）
- **OkHttp 4.11** + Gson 2.10（与 Worker 一致）
- **Exposed 0.46** + SQLite（ORM）
- **kotlinx.coroutines 1.7**（协程）
- **Logback 1.4**（日志）

详细使用说明见 [wechat-download-kt/README.md](wechat-download-kt/README.md)。

## 克隆与初始化

直接克隆本仓库即可（服务端为仓库原生部分，无需子模块）：

```bash
git clone https://github.com/FLC-niko/wechat-download-improve.git
```

手机端为独立仓库，需要时单独克隆到工作区同级目录：

```bash
git clone https://github.com/FLC-niko/archive-system-crawling-android.git
```

## 二次开发说明

- **wechat-download-kt**（Kotlin）：直接在本仓库内提交，是工作区的原生部分；部署方式见 [服务器部署运维](wechat-download-kt/docs/服务器部署运维.md)。
- **archive-system-crawling-android**（手机端）：独立仓库，改动在其仓库内提交与构建。
- 系统设计与执行状态见 [三阶段执行计划.md](三阶段执行计划.md)；交接与联动细节见 [交接文档.md](交接文档.md)（本地维护、不入库）。
- 设计目标：与 Kotlin Worker [we-mp-rss-kotlin](https://github.com/FLC-niko/we-mp-rss-kotlin) 技术栈统一，通过 `/api/v1/wx/*` 适配壳 + RabbitMQ 任务协议对接，Worker 代码无需修改。

## License

- `wechat-download-kt` 遵循 **AGPL-3.0** 协议（与上游 `wechat-download-api` 保持一致）。
- `archive-system-crawling-android` 基于上游开源项目二次开发（署名 Kokomi 2022），仓库内暂未见 LICENSE 文件，使用与分发前请与上游/作者确认许可条款。
