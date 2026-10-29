# CI 部署（GitHub Actions → Cloudflare Tunnel SSH）

2026-10-22 起，`wechat-download-kt` 不再用服务器每分钟轮询 + 生产机构建的方式部署。
push 到 `main` 后：

1. GitHub runner（Ubuntu）执行 `./gradlew test` + `installDist`；
2. 打包 `kt-<commit12>.tar.gz` 并计算 SHA256；
3. deploy job 通过 **Cloudflare Tunnel + Access Service Token** SSH 到
   `deploy.989gdut989.dpdns.org`；
4. 服务器上的 `deploy` 用户（强制命令）接收制品，校验 SHA256 后调用
   `/usr/local/bin/deploy-kt.sh`；
5. 解压到 `deploy/releases/kt-<commit12>` → 原子切换 `deploy/current` →
   `manage.sh restart` → 健康检查 → 失败自动回滚 `deploy/previous`。

## 服务器布局

```text
wechat-download-kt/
├── config.yaml data/ logs/ qrcode/ app.pid     # 运行态，部署永不触碰
├── build/install -> ../deploy/current          # 由部署脚本创建
└── deploy/
    ├── releases/kt-<sha12>/wechat-download-kt/...
    ├── releases/legacy-<ts>/install/...        # 首次迁移的旧 build/install
    ├── current -> releases/kt-<sha12>
    ├── previous -> releases/kt-<旧>
    ├── backups/rss-*.db                        # 部署前 SQLite 备份（保留 3 份）
    ├── state.json                              # last_tag/last_sha/时间/health
    └── logs（部署日志走 journald: journalctl -t deploy-kt）
```

> 注意：`build/install` 现在是符号链接，**不要再在服务器上手动跑
> `./gradlew installDist`**（会写穿到当前 release）。构建统一在 CI 完成。

## GitHub Secrets（仓库 → Settings → Secrets and variables → Actions）

| Secret | 说明 |
|---|---|
| `CF_ACCESS_CLIENT_ID` | Access Service Token Client ID（`xxxx.access`） |
| `CF_ACCESS_CLIENT_SECRET` | Access Service Token Secret（只显示一次） |
| `DEPLOY_SSH_KEY` | `deploy` 用户的 ed25519 私钥（无口令） |
| `DEPLOY_KNOWN_HOSTS` | `deploy.989gdut989.dpdns.org ssh-ed25519 AAAA...`（服务器 host key） |

建议同时在仓库设置里开启 `production` environment 的保护规则（若 GitHub 套餐支持）。

## 首次/重新安装（服务器，root）

```bash
sudo bash ops/deploy/install-server.sh /path/to/deploy_key.pub
```

它会创建 `deploy` 用户、安装两个脚本、写入 sudoers（仅允许执行 deploy-kt.sh）。

## Cloudflare 侧（已配置）

- Access 应用：`eink-deploy-ssh`（`deploy.989gdut989.dpdns.org`，Service Auth 策略）
- Service Token：`github-deploy-ssh`
- 隧道 `公众号采集器` ingress：`deploy.989gdut989.dpdns.org → ssh://localhost:22`
- DNS：`deploy` CNAME → `1004ec58-85ab-43c4-abd5-8b5b63eeb15f.cfargotunnel.com`（proxied）

## 回滚

- 自动：部署后健康检查失败会切回 `previous` 并重启。
- 手动：`ln -sfn releases/<旧tag> deploy/current.tmp && mv -T deploy/current.tmp deploy/current`
  然后 `sudo -u topview -H ./manage.sh restart`。
- 数据库：部署前已备份 `deploy/backups/rss-*.db`；二进制回滚不保证旧版本能读新库，
  必要时手工恢复备份。

## 旧的轮询部署

- `crontab` 里的每分钟 `github-poll-deploy.sh` 已注释（`DISABLED-FOR-CI`）。
- `@reboot manage.sh start` 保留。
- 如需紧急回退：取消注释该 cron，并删除 `build/install` 符号链接恢复为真实目录。
