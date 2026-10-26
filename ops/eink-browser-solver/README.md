# E-Ink 浏览器自动解题服务（生产）

2026-10-26 文字题更新：可复现分组评测、私有模型快照、旁路采样与回滚命令见
[TEXT_MODEL_RUNBOOK.md](TEXT_MODEL_RUNBOOK.md)。交接中的 78.4% 含答案特征，不能作为
上线成绩；完全重复隔离为 122/171（71.35%），再保守隔离近重复后为
105/171（61.40%）。当前候选采用后者的校准，新字泛化仍需补真实标注。
当前生产旧服务已用同一批 171 题实际 HTTP 复测，得到 46/171（26.90%）；
新版折外评测多对 59 题，提高 34.50 个百分点，详见交接记录的同题复测章节。
当前已开启文字题 **10% A/B 灰度**：A 组返回原 9993，B 组优先使用经私有隧道接入的
5090 融合服务，异常时依次回退本机 9994 同版融合、原 9993。5090 下线不影响本机独立处理。
比例归零可即时恢复全部旧版结果，已演练 10% → 0% → 10%。详见
[TEXT_AB_ROLLOUT.md](TEXT_AB_ROLLOUT.md)。人工候选队列继续刷新，线上结果与回放分开统计。

服务端用真实 Chromium 跑通腾讯验证码（image_select / 包含文字），把 ticket/randstr
交回 App 完成官方人工验证。替代默认关闭的 crack-tcaptcha sidecar（见
`../eink-captcha-sidecar/`，其 `turing` 端点与旧版 `verify` 契约已失效）。

## 架构

```
App auto_solver (127.0.0.1:9991) ──POST /solve──▶ solver-daemon.mjs (Node)
    │                                                  ├─ Chromium（Xvfb 有头）
    │                                                  ├─ 本地选格 → clip_service.py (127.0.0.1:9993)
    │                                                  │     ├─ 场景/物体题：cn-clip 图文相似度（多选集合）
    │                                                  │     ├─ 包含文字题：ddddocr / YOLO+Siamese（相似字）
    │                                                  │     └─ 依次点击题：seq_match.py 多尺度模板匹配（sprite 剪影→主图）
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
2. 复制本目录 `solver-daemon.mjs`、`clip_service.py`、`seq_match.py` 到
   `/opt/eink-browser-solver/`；复制 `systemd/*.service` 到 `/etc/systemd/system/`。
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

## 依次点击（图标序列）题型（2026-10-18）

题面为“依次点击：”+ 提示小图（黑色剪影图标序列，带噪点防 OCR），主图为照片上
随机分布的彩色描边图标（672×480），需按提示顺序依次点击。

实现（`seq_match.py`，端点 `POST 127.0.0.1:9993/seq-pick`）参考
[adnogpu/TXCaptcha](https://github.com/adnogpu/TXCaptcha) 的同款题型方案并做适配：

1. sprite 剪影：自适应暗色/低饱和阈值提取字形，按 bbox 邻近合并碎片；
2. 主图搜索图：彩色描边（饱和度 98 分位自适应）+ 暗色高对比描边，闭运算后填洞成实心字形；
3. 匹配：多尺度（0.8–2.2）× 多角度（-40°…40°）`TM_CCOEFF_NORMED`，
   分数融合相关系数 + recall + precision，每变换取前 5 个抑制峰、全局保留前 10；
4. 组合：对所有字形的候选做全局非重叠最优组合（DFS，近距惩罚）；
5. 门控：`min_score < SOLVER_SEQ_MIN_SCORE`（默认 0.62）不点击，改点“换一组”；
   单次解题 seq 轮数超过 `SOLVER_SEQ_MAX_ROUNDS`（默认 3）快速失败。

调参样本：`DEBUG_DIR/seq-r*-img.jpg`、`seq-r*-sprite.png`、`seq-r*-pick.json`
（后者含 scores/坐标），线上以 verify 的 errorCode（0 成功 / 51 失败）回归评估。

## 回滚

1. `systemctl stop/disable eink-browser-solver eink-clip-service`；
2. iStoreOS 恢复规则备份并重启 OpenClash；
3. 可选：重新启用 crack-tcaptcha sidecar（见 `../eink-captcha-sidecar/README.md`，
   注意其 `turing` 端点与旧 `verify` 契约当前不可用）。

## 运行模式与通知策略（2026-10-17 起：无 ADB 模式）

- 手机离线时服务器 `config.yaml` 置 `adb.enabled: false`：停用 ADB 设备探测与
  AAOS/坐标两条自动恢复管线，不再产生“没有可用的 ADB 设备槽位”重试。
- Web 通道：健康检查每 5 分钟一轮；凭证过期即生成新二维码推送企微+飞书，
  服务端轮询等待人工扫码，扫码成功恢复采集，失败退避后下轮发新码。
  手机上线后可改回 `adb.enabled: true`，扫码即转由手机 AAOS 承接。
- E-Ink 通知策略：仅当自动解题**失败或未配置**时才发人工验证链接+二维码
  （`sendEinkManualFallback` 遇 `RECOVERED` 直接返回）；自动解题成功时机器人
  保持静默（已用成功窗口日志验证）。`auto_solver.timeout_seconds` 建议 ≥150
  以容纳多轮耗时（当前生产值 180）。
