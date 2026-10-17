# E-Ink 浏览器自动解题服务（生产）

服务端用真实 Chromium 跑通腾讯验证码（image_select / 包含文字），把 ticket/randstr
交回 App 完成官方人工验证。替代默认关闭的 crack-tcaptcha sidecar（见
`../eink-captcha-sidecar/`，其 `turing` 端点与旧版 `verify` 契约已失效）。

## 架构

```
App auto_solver (127.0.0.1:9991) ──POST /solve──▶ solver-daemon.mjs (Node)
    │                                                  ├─ Chromium（Xvfb 有头）
    │                                                  ├─ 本地选格 → clip_service.py (127.0.0.1:9993)
    │                                                  │     ├─ 场景/物体题：cn-clip 图文相似度（多选集合）
    │                                                  │     └─ 包含文字题：ddddocr / YOLO+Siamese（相似字）
    │                                                  └─ ticket/randstr ◀── cap_union_new_verify (errorCode=0)
    └─ WeReadEinkHumanVerification.complete(ticket, randstr) → 账号恢复
```

关键行为（均为实测结论）：

- 服务端常要求**连续多轮正确**才放票（单轮正确返回 errorCode=51“请再作答一次”）；
  答错返回 51“已更换题目”并轮换；errorCode=12/52 表示会话被污染，需整页 reload
  重开会话；`terror_` 开头票据为客户端降级伪票，必须丢弃。
- 点击必须走贝塞尔人类鼠标轨迹；UA 必须与平台一致（Linux Chromium 用 Linux UA）；
  无头 shell 会被降权，生产用 Xvfb 有头模式。
- 验证页自身的 `/complete` 请求会被拦截作废，保持“App 单方完成”语义。

## 出口要求（重要）

解题浏览器的出口 IP 必须可信：新加坡机房 IP 答对也被无限续考（51 循环），
大陆住宅/校园直连 IP 答对 1~2 轮即放票。

生产环境通过 iStoreOS（10.21.76.16）OpenClash TUN 的自定义规则实现——
fake-IP 会让 `GEOIP,CN→Direct` 永远匹配不上，所以必须用显式域名规则：

```
- DOMAIN-SUFFIX,t.captcha.qq.com,DIRECT
- DOMAIN-SUFFIX,ssl.captcha.qq.com,DIRECT
- DOMAIN-SUFFIX,captcha.gtimg.com,DIRECT
- DOMAIN-SUFFIX,turing.captcha.qcloud.com,DIRECT
- DOMAIN-SUFFIX,i.weread.qq.com,DIRECT
- DOMAIN-SUFFIX,weread.qq.com,DIRECT
```

位置：`/etc/openclash/custom/openclash_custom_rules.list`（备份：
`openclash_custom_rules.list.bak-20261017`），改后
`/etc/init.d/openclash restart`（代理闪断约十几秒）。
其余流量零变化；回滚 = 恢复备份文件并重启。

## 部署（服务器）

1. 依赖：Node 18+（`apt install nodejs npm`）、Playwright Chromium
   （`pip install playwright && playwright install --with-deps chromium`，
   本环境路径 `/opt/ms-playwright`）、Xvfb（`apt install xvfb`，`:99`）、
   cn-clip/torch/ddddocr（见 `../eink-captcha-sidecar/patches/image-select-clip/README.md`）。
2. 复制本目录 `solver-daemon.mjs`、`clip_service.py` 到 `/opt/eink-browser-solver/`；
   复制 `systemd/*.service` 到 `/etc/systemd/system/`。
3. 按 `eink-browser-solver.env.example` 写 `/etc/eink-browser-solver.env`
   （`SOLVER_SK` 必须与 App `config.yaml` 中
   `weread.eink.human_verify.auto_solver.secret` 一致），`chmod 600`。
4. App `config.yaml` 中启用 `weread.eink.human_verify.auto_solver`
  （`enabled: true`，两个 vid 进 `vid_allowlist`），重启 App。
5. `systemctl enable --now eink-clip-service eink-browser-solver xvfb`。

## 运维

- 健康：`curl 127.0.0.1:9991/health`（daemon，含 browser/busy/queue）、
  `curl 127.0.0.1:9993/health`（视觉服务）。
- 日志：`journalctl -u eink-browser-solver`（每轮 instruction/pick/verify code）、
  `journalctl -u eink-clip-service`（sims/margin、OCR 文本）。
- 调试样本：`DEBUG_SAVE=1` 时每轮题图与选择存 `DEBUG_DIR`（离线调参用）。
- App 侧：`E-Ink 自动验证结束: vid=..., code=RECOVERED` 即成功；
  `terror_` 票据会被拒绝；`auto_solver.timeout_seconds` 建议 ≥150（多轮耗时）。

## 回滚

1. `systemctl stop/disable eink-browser-solver eink-clip-service`；
2. iStoreOS 恢复规则备份并重启 OpenClash；
3. 可选：重新启用 crack-tcaptcha sidecar（见 `../eink-captcha-sidecar/README.md`，
   注意其 `turing` 端点与旧 `verify` 契约当前不可用）。
