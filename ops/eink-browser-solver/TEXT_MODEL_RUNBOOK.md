# 文字题评测、私有模型快照与回滚（2026-10-26）

**最新线上状态（2026-10-27）：** 已按用户要求从文字题 10% 调整为 50/50 A/B。A 组旧版，B 组优先 5090
融合，远端不可用时回退应用机同版 CPU 融合，再回退旧版。上线、断线回退、
10% → 0% → 10% 演练和当前快照见 [TEXT_AB_ROLLOUT.md](TEXT_AB_ROLLOUT.md)。
下文原有纯旁路验证记录保留为部署历史，离线成绩没有改变。

## 评测结论

10.26 交接中的 **78.4% 不能作为可部署成绩**。原 `/tmp/decision64.py`
把 `log1p(len(selected))`（本题真实应选数量）放进了逻辑回归特征。
复跑可得到原来的 134/171；删除答案特征后，原阈值 0.2 只得到 108/171。
在同一旧缓存分数上改用 0.5 可得到 126/171，但旧缓存仍有重复图跨组等问题，
这个数也只作诊断。

新实验不读取旧 OCR 伪标签行来覆盖人工修正，使用原始整图裁切，并把像素完全相同的
格子连接到同一组。171 轮共形成 141 个独立组；发现 65 组重复格子，无同目标字二元
真值冲突。训练源共有 617 个正格和 409 个负格；其中 12 个负格没有可信的具体字名，
所以字符分类标签为 1,014 条，整题评测仍覆盖全部 1,026 格。
负格“不是目标字”是人工真值；397 个负格的具体字名仍来自旧 OCR，属于弱标签。

模型方案均用外层 5 折和内层 3 折生成融合头训练分数；run-01/02 按像素完全重复图分组，
run-03 另合并近重复组。
融合阈值只在外层训练部分选择，目标频次也只从该部分计算。

| 版本 | 完全正确题数 | exact | 每题 F1 均值 | 用途 |
|---|---:|---:|---:|---|
| CLAHE OCR | 79/171 | 46.20% | 81.46% | 独立基线 |
| run-01（早层 BN 固定） | 82/171 | 47.95% | 85.72% | 实验/回滚演练对照 |
| run-02（训练集适配 BN，16 epoch） | 122/171 | 71.35% | 93.34% | 完全重复隔离的历史候选 |
| **run-03（另隔离近重复）** | **105/171** | **61.40%** | **89.81%** | 当前采用的更保守评测候选 |

run-02 的 49 道错题中，38 道包含漏选，14 道包含多选，两类有重叠。
最终全数据候选融合阈值为 0.45；各折阈值独立选取，不能把最终模型在训练图上的结果
当作交叉验证成绩。

补充近重复审计（64 位 DCT Hamming ≤3，且 64×64 灰度平均差 ≤2/255）又找到 40 对
跨组格子。它们在 run-03 中保守合并后重新评测；因此 run-02 的 71.35% 必须连同
“只隔离完全重复图”的协议一起引用，不能当作近重复也隔离后的成绩。
该近重复检查是启发式检查，不保证排除所有视觉近重复。
合并后共 107 个独立组；run-03 的 66 道错题中，57 道有漏选、11 道有多选，两类重叠。
run-03 最终融合阈值为 0.40。与 CLAHE 的 46.20% 相比，完整正确题数增加 26 道，
提高 15.20 个百分点。不同分组协议的分数不能直接比较模型优劣。

按日期留出 10 月 24 日 37 题，训练仅用此前数据并排除重复组。测试的 13 个目标字
全部没有作为目标出现在训练题中。run-02 和 CLAHE 都是 **17/37 = 45.95%**。
因此 71.35% 尚不能解释为新字或线上成功率，85%–90% 目标未达到。

另做目标字条件匹配实验：冻结书法特征，投影格子与四种可用字体字形，用人工正例
学习跨字匹配，内层按目标字留出挑阈值。时间留出集上单独模型为 4/37，与 OCR 合并
仍是 17/37，没有收益，**不接入当前候选**。两个损坏字体被检测后排除，字体副本和
审计记录留在私有实验目录。

## 服务与制品

所有图片、标签、权重、预测明细留在私有服务器/本地 `.local/eink-20261026/`，
不进入 Git。以下是应用服务器上的路径：

- 原生产视觉服务：`127.0.0.1:9993`，`eink-clip-service`。
- 独立候选：`127.0.0.1:9994`，`eink-text-candidate`。
- 旁路入口：`127.0.0.1:9995`，`eink-text-shadow`。
- 候选指针：`/home/topview/captcha/text-current`。
- 已封存版本：`/home/topview/captcha/text-releases/text-v2-run01`、`text-v2-run02`、`text-v2-run03`。
- 当前候选：`text-v2-run03`；模型 manifest 指纹 `6cc80d5cde1b81ec`。
- run-03 服务 release manifest SHA256：
  `433d1472069af4c5fc2607483adf6ed9bfa97c0211b876d3ebf8df04f58d28ab`。
- run-02 模型 manifest 指纹：`0c71251268066294`。
- run-02 服务 release manifest SHA256：
  `26c45f55736f34a7c0c7b17941100f0b1e861908e53634c1af8fcbb6abe969e1`。

候选与入口已持久启用，生产 daemon 的 `CLIP_URL` 指向 9995。纯旁路阶段已用真实
图片核对返回和直接访问 9993 的 JSON 完全一致；当前 0% 配置也已重新验证该一致性。
当前实验比例为 50%，B 组返回融合结果。回放排除在实时标注队列和线上统计外；
实时新题依赖正常恢复调度自然产生，未主动增加验证码尝试。

每个 release 同时包含 `service/` 下的四个推理源码，以及 `bundle/` 下的模型、参考库、
训练轮次/字频、融合参数、数据审计、评测结果和 SHA256 清单。创建同名版本会拒绝覆盖。
服务加载模型前校验全部制品；只接受约定的特征顺序和 OCR 预处理契约。

训练机实验目录：`/home/topview/captcha/experiments/text-v2-20261026/`。
其中 `run-01`、`run-02`、`run-03`、`temporal-02`、`pair-01`（字体失败记录）、`pair-02`
互不覆盖；训练仅用 GPU0，显存上限为该卡的 30%，未修改 GPU1 常驻服务。

应用服务器已用十张真实题图进行训练机 CPU / 应用服务器 HTTP 推理一致性验证：
**10/10 通过**，OCR 与选格集合一致，分数最大差约 `1.1e-7`；预热后约 1.4–1.8 秒/题。
这些图只证明服务数值一致，不是独立准确率测试。另已实际完成 run-02 → run-01 →
run-02 切换，并逐次校验健康端点中的模型指纹。
最终 run-03 同样通过 10/10 一致性回放，分数最大差约 `1.1e-7`，此次十题耗时
约 1.3–2.5 秒/题；另核对了 run-03 的旁路返回完全等于旧服务、后台采用最新指纹。
已实际完成 run-03 → run-02 → run-03 的回滚与恢复。

run-02 和 run-03 全数据最终模型、参考库、训练清单的 SHA256 分别完全相同；新版本
的变化是近重复分组产生的折外评测与融合参数。两版均保留原始制品，不混用评分协议。

## 当前生产旧服务的同题复测（2026-10-26）

将上述全部 **171 道人工标注题**重新发送到应用机当前旧服务
`http://127.0.0.1:9993/pick`，串行执行，每题前等待生产调度空闲。
使用与模型评测一致的原始图片和六格坐标；请求仅包含图片、目标字、坐标，
不向服务发送答案。未调用浏览器、未提交外部验证码、未切换生产服务。
171 题全部返回成功，题目无缺失、无重复；本地重新计分与服务端报告一致。
被测生产源码 SHA256 在回放前后保持
`320599ecf8cd90507b500a1fffd431ea0970ee21319f0d7cb1af6b44d0fdde0d`。

| 版本/测法 | 完全正确 | 每题 F1 均值 | 含漏选题数 | 含多选题数 |
|---|---:|---:|---:|---:|
| 当前生产旧服务，实际 HTTP 重放 | **46/171（26.90%）** | 69.28% | 125 | 3 |
| run-03，已有分组折外预测 | **105/171（61.40%）** | 89.81% | 57 | 11 |

新版整题净多对 **59 题，提高 34.50 个百分点**。逐题对照：两者均正确 43 题，
仅新版正确 62 题，仅旧版正确 3 题，两者均错误 63 题。新版主要减少漏选，
但含多选的题从 3 增至 11。仅旧版正确的题为：

- `r3-2026-10-21T21-08-14-554Z`
- `r5-2026-10-22T15-49-22-498Z`
- `r5-2026-10-24T10-08-28-439Z`

**比较边界：** 旧版是当前生产 HTTP 服务的实际回放；新版是相同题目的已存档
交叉验证折外预测，不是当前全数据训练模型的 HTTP 回放。全数据模型已经见过这批
题，直接用它评分会高估泛化效果，因此没有使用该成绩。结果支持新版在这批历史
标注题上的改进，不代表独立新题或线上验证码通过率。完成此复测时生产仍返回
旧版结果；此后已开启 10% A/B，详情见本文开头的最新状态。

可复核脚本：`evaluate_legacy_text.py`。应用机私有完整记录：
`/home/topview/captcha/evaluations/20261026-legacy-vs-run03/`，包含输入标签/轮次、
执行脚本、日志及 `results/` 下的逐题旧版响应、图像与输入 SHA256、汇总和逐题对照。
本地报告副本：`.local/eink-20261026/legacy-vs-run03/`（已忽略）。
`results/comparison.json` SHA256：
`10b856d063cb4ddce1823a2c44eebb59fe1cccbc67a18a661cac0a361788a3b7`。

## 切换候选与回滚

当前 run-03 的完整配置/制品快照：

```text
/home/topview/captcha/snapshots/20261026-best-text-run03-shadow
```

共 57 个文件，包含最终模型、源码、运行配置、自动队列代码、近重复审计、时间留出及
两版回放报告。manifest SHA256 为
`3a5c63815be4bda3ac3f171e30492c01aee7d8031fa68b555f2b89bc226c7a09`。

```bash
sudo python3 /home/topview/captcha/capture_text_snapshot.py \
  --snapshot /home/topview/captcha/snapshots/20261026-best-text-run03-shadow --verify-only
```

保留的 run-02 历史完整配置/制品快照：

```text
/home/topview/captcha/snapshots/20261026-best-text-run02-shadow
```

它封存了 run-02 的代码、模型、参考库、全部服务配置、Node 依赖和验证报告，共 53 个
文件；两版候选的 Python/共享模型运行时都锚定下面的完整原生产基线，**基线也必须保留**。
manifest SHA256 为 `daf983665f2a108fe4da62203f0be3c55edffe54d89ad914e8dd0389bc11202f`。
该名称中的 best 指当时已完成服务回放的候选，不代表已达到 85% 或新字泛化验收。

```bash
sudo python3 /home/topview/captcha/capture_text_snapshot.py \
  --snapshot /home/topview/captcha/snapshots/20261026-best-text-run02-shadow --verify-only
```

候选单元始终从 `text-current/service/clip_service.py` 启动，模型从同一指针的
`bundle/` 加载。不要单独替换某个权重或参考库。

```bash
sudo python3 /home/topview/captcha/text_release.py activate \
  --release /home/topview/captcha/text-releases/text-v2-run03 \
  --link /home/topview/captcha/text-current \
  --service eink-text-candidate \
  --health-url http://127.0.0.1:9994/health
```

回到 run-02 或 run-01 时只替换 `--release` 的版本名；当前保留近重复校准的 run-03。
切换前校验所有文件，指针原子更新，启动或模型指纹检查失败时自动恢复旧指针。
`text-current.previous` 保留上一次成功版本，历史 release 不自动删除。

如果原 release 目录意外丢失，先从私有快照复制到一个新的 release 目录并恢复所有权，
再用上面的 `activate` 切换，不能直接把候选指针指向 root 私有的快照目录：

```bash
sudo cp -a /home/topview/captcha/snapshots/20261026-best-text-run02-shadow/release \
  /home/topview/captcha/text-releases/text-v2-run02-restored
sudo chown -R topview:topview /home/topview/captcha/text-releases/text-v2-run02-restored
```

生产服务完整基线：

```text
/home/topview/captcha/snapshots/20261026T035356Z-production-baseline
```

它含原视觉代码、daemon、模型、完整 venv、systemd 单元、受限环境配置及历史调试/标注，
共 31,650 个文件、2,441,580,993 字节，已经逐文件 SHA256 校验。
manifest SHA256 为 `22d73715cb22de092a304f35a5eac6836463967292b7fb7e5cea877b1f3cf6df`。

```bash
# 只校验；不重启服务
sudo python3 /home/topview/captcha/restore_vision_baseline.py \
  /home/topview/captcha/snapshots/20261026T035356Z-production-baseline

# 恢复原代码、daemon 环境配置和原单元，随后检查健康
sudo python3 /home/topview/captcha/restore_vision_baseline.py \
  /home/topview/captcha/snapshots/20261026T035356Z-production-baseline --apply
```

只有 Python 环境/共享模型也损坏时才加 `--restore-runtime`；它保留恢复前目录，
不会覆盖应用账号状态、数据库或后续采样数据。完整恢复后原 daemon 的 `CLIP_URL`
恢复为直接访问 9993；新增的候选/旁路服务可以继续留在回环地址，或手动停止。

训练机历史实验快照：
`/home/topview/captcha/snapshots/20261026T035403Z-training-historical`，
manifest SHA256 为 `7e20428c77d27c11ee98343323e71e0c51faa455f89cad8ef622b903b3b9b636`。
该历史实验带答案特征，只用于审计，不作为高分服务回滚目标。

## 旁路采样与人工标注

纯旁路/0% 阶段返回原 9993，9994 在独立、有界工作队列中计算并记录预测。
当前 A 组沿用该模式，B 组同步选择远端或本机融合结果，再异步保存记录。
场景题不采样，seq 请求转给旧服务。样本按图片和指令去重，图片与 JSON 原子写入
`/home/topview/captcha/text-shadow/`，权限为私有；入参及推理结果均用字段白名单，
不保存 ticket、randstr 或账号凭据。回放样本带 `source=replay`，不进入新题人工队列。

```bash
python3 /home/topview/captcha/text-shadow-service/build_text_review_queue.py \
  --samples /home/topview/captcha/text-shadow \
  --output /home/topview/captcha/text-review-queue.jsonl --limit 100
```

后台样本完成后自动原子刷新人工队列；上面的命令也可手动重建。
队列优先级：未见目标字 → 新旧选格分歧 → 临近阈值 → 推理异常。
所有新样本保持 `human-label-required`，禁止把模型预测当人工真值继续训练。
先补新目标字和稀有字的多个真实外观，再另留新日期题做验收。

## 本地验证与部署边界

```bash
python3 -m unittest discover -s ops/eink-browser-solver -p 'test_text_*.py' -v
python3 -m py_compile ops/eink-browser-solver/*.py
git diff --check
```

代理测试会绑定本机随机回环端口，需要环境允许本地监听。测试覆盖制品损坏、覆盖保护、
启动失败回滚、错误模型健康状态、成功切回、准确率门槛、慢候选隔离和采样白名单。

本次未改 Kotlin `deploy/current`；源码按训练评测、模型服务与回滚、A/B 路由、文档
分阶段提交，尚未 push。现有 `ops-artifact` 工作流仅打包制品，
不会自动部署这些视觉服务；后续不能把 Git push 当成视觉服务已更新的证明。
