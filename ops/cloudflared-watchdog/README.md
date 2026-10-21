# cloudflared 隧道自动维持（生产：dag2.0-backend 10.21.76.61）

公网入口 `einkrecovery.989gdut989.dpdns.org` 由本机 cloudflared（token 模式，
systemd 服务）提供。cloudflared 自身会重连，但边缘连接可能"假死"（进程存活、
systemd 无感知、公网 502/1033）。本目录提供两层兜底：

1. `cloudflared.service` drop-in：`Restart=always`、`RestartSec=5s`、
   `StartLimitIntervalSec=0`（永不放弃重启）；
2. `cloudflared-watchdog`：每分钟读取 cloudflared 指标端点
   `127.0.0.1:20241/metrics` 的 `cloudflared_tunnel_ha_connections`，
   连续 3 次为 0（或指标端点不可用时公网探测连续失败）则重启服务。

## 部署

```bash
sudo install -m 755 cloudflared-watchdog.sh /usr/local/bin/cloudflared-watchdog.sh
sudo install -m 644 cloudflared-watchdog.service /etc/systemd/system/
sudo install -m 644 cloudflared-watchdog.timer /etc/systemd/system/
sudo mkdir -p /etc/systemd/system/cloudflared.service.d
sudo install -m 644 cloudflared-restart-override.conf \
    /etc/systemd/system/cloudflared.service.d/override.conf
sudo systemctl daemon-reload
sudo systemctl enable --now cloudflared-watchdog.timer
sudo systemctl restart cloudflared
```

## 验证 / 运维

```bash
systemctl status cloudflared cloudflared-watchdog.timer
systemctl list-timers --all | grep cloudflared
curl -s http://127.0.0.1:20241/metrics | grep cloudflared_tunnel_ha_connections
sudo journalctl -t cloudflared-watchdog --since '-30 min'
```

- 健康时 watchdog 不产生日志；异常时会记录
  `tunnel unhealthy (connections=...)` 和 `restarting cloudflared ...`。
- 故障演练：`sudo systemctl stop cloudflared` 后等待 1 分钟内自动拉起，
  或手动执行 `sudo /usr/local/bin/cloudflared-watchdog.sh` 立即恢复。
- 公网探测默认用 `https://einkrecovery.989gdut989.dpdns.org/api/health`
  （401 属正常鉴权响应），可用环境变量 `PROBE_URL` 覆盖；
  失败阈值用 `WATCHDOG_FAIL_THRESHOLD`（默认 3）覆盖。
- cloudflared 由 `cloudflared-update.timer` 负责自动更新；重启期间
  watchdog 会按上述逻辑补位，不会误判为长时间故障。
