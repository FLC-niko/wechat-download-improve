# image_select 本地 cn-clip 后端补丁

上游 `crackTCaptcha` 的 `image_select` 只有 LLM vision 一条路（`README` 宣称的
`[clip]` extra 在源码中并未实现）。本补丁补上本地后端：

- `solvers/clip_vision.py`：把 6 个候选区域裁剪出来，用 **cn-clip**（中文 CLIP）
  与题面文本（如“灰色敞篷车”）做图文相似度，取最高分区域；模型只加载一次。
- `pipelines/image_select.py`：优先走本地 clip，异常/不可用时回落到上游 LLM
  实现；`TCAPTCHA_IMAGE_SELECT_BACKEND=clip` 可强制只用本地（失败即报错），
  `=llm` 可强制只用 LLM。

## 前置依赖（安装一次）

```bash
sudo /opt/crack-tcaptcha/venv/bin/pip install \
  --index-url https://download.pytorch.org/whl/cpu torch torchvision
sudo /opt/crack-tcaptcha/venv/bin/pip install cn-clip cn2an
# 首次运行会下载 cn-clip ViT-B-16 检查点到 /opt/crack-tcaptcha/models（约 720MB）
```

## 应用 / 回滚

```bash
sudo bash apply.sh            # 应用并重启 crack-tcaptcha
curl http://127.0.0.1:9991/health
```

回滚（恢复上游文件后重启）：

```bash
sudo git -C /opt/crack-tcaptcha/source checkout -- \
  src/crack_tcaptcha/pipelines/image_select.py
sudo rm /opt/crack-tcaptcha/source/src/crack_tcaptcha/solvers/clip_vision.py
sudo /opt/crack-tcaptcha/venv/bin/pip install --force-reinstall --no-deps \
  /opt/crack-tcaptcha/source
sudo systemctl restart crack-tcaptcha
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `TCAPTCHA_IMAGE_SELECT_BACKEND` | `auto` | `auto` / `clip` / `llm` |
| `TCAPTCHA_CLIP_MODEL` | `ViT-B-16` | cn-clip 检查点名称 |
| `TCAPTCHA_CLIP_DEVICE` | `cpu` | torch 设备 |
| `TCAPTCHA_CLIP_MODEL_DIR` | `/opt/crack-tcaptcha/models` | 检查点目录 |

## 注意

- 本补丁会让 `/opt/crack-tcaptcha/source` 成为 dirty checkout，上游安装器会拒绝
  更新；升级上游前先按上面的回滚步骤恢复，再重新应用本补丁。
- 准确率需用真实题样回归（见项目验收记录）；相似度 `margin` 已写入 sidecar 日志，
  便于统计与调参。
