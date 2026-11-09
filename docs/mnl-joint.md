# 路线与出发时间联合 MNL

`--policy mnl` 控制选定目标旅客的路线和出发时间；原路线型 MNL 已替换。使用 `--mode hybrid --llm-agents all` 以覆盖所选目标人群。`--llm-agents 0` 不控制旅客，是 MATSim 原生基线。MNL 自身不调用模型；启用 `--manager` 时，Manager 和其记忆仍需模型服务。

## 决策与信息

MNL 与 LLM 接收相同的固定候选路线、原计划锚定的离散出发时间及信息分组。每个路线 × 时间组合都是可选动作，使用效用

`V = -a·T - b·C - c·L - d·|departure - originalDeparture|`，其中 T、L 与出发偏移单位为分钟，C 为场景货币单位。L 是预计到达时超出工作时限的分钟数；缺少截止时限时不假定迟到。Softmax 温度固定为 1，max-subtraction 防止数值溢出，不额外使用原路线型 MNL 的 beta。

旅行时间估计使用给定路线的静态自由流时间，与最近 `--history-window` 天内**同路线、同计划出发时间分箱**的本人完整实际汽车耗时均值做加权融合；默认权重 0.5、时间分箱 900 秒。没有匹配经历则使用自由流；未完成或 stuck 的记录不作为完整耗时，也不伪造零值。这是显式简化的学习规则，不等于 LLM 的连续摘要。默认经历窗口为近五天；未指定窗口时跟随旅客反思周期，收费经验则是本人各路段、时间分箱的最近一次实际观察。

知情旅客使用当前全网公告；不知情旅客对每个路段和时间分箱使用本人最新实际收费观察（含零），未观察部分用不确定的零预期。实际账单由当前生效政策按实际进入路段的时间执行；收费变化不提前泄漏给不知情者。候选收费计算使用预计进入时间，实际进入时刻可能不同。

## 参数与运行

默认读取 `config/baselines/mnl-joint.example.json`。其中系数是**未校准的工程示例**，不能称作真实人群的估计参数或用于证明 LLM 优于成熟的传统选择模型。应采用独立数据估计或事先声明的文献/假设参数，并预先设定敏感性分析；不得根据比较组结果事后选择使某一方法表现更差的参数。每项的单位和来源必填，数值、字段、重复键及输入文件经过校验；实验 `manifest.json` 和 `baseline-parameters.json` 保存参数与文件 SHA-256。参数路径可通过 `--mnl-parameters`（PowerShell `-MnlParameters`）覆盖。

示例（从项目根目录运行）：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents all `
  -Demand 100 -SamplePersons 40 -Days 4 -Seed 268 `
  -MnlChoiceSeed 268 -Output 'output/mnl-joint-smoke'
```

`-SamplePersons 40` 不缩放容量，仅用于短程调试，不代表正式 20% 实验。正式配对采用相同 JAR、人口种子、信息组、候选文件、动作网格、网络配置、政策和天数；如双方使用 `-SampleRate 0.2`，还应核验相同容量缩放。每个仿真 seed 多次完整运行并报告运行级差异。MNL 选择 seed 默认等于仿真 seed，可用 `--mnl-choice-seed` / `-MnlChoiceSeed` 独立设定；模型按 seed、day、tripId 派生独立随机流，避免调用顺序改变结果。

Linux/集群可从项目根目录直接使用 Java（以下是 20% 配置示例；模型调用为零）：

```bash
java -Xmx12g -jar matsim-example-project-0.0.1-SNAPSHOT.jar \
  --config scenarios/SiouxFalls-public/config.xml --mode hybrid --policy mnl \
  --llm-agents all --demand 100 --sample-rate 0.2 --days 100 \
  --seed 268 --mnl-choice-seed 268 --population-seed 6021 \
  --information-seed 6021 --informed-share 0.5 --max-requests 0 \
  --mnl-parameters config/baselines/mnl-joint.example.json \
  --output output/mnl-seed268
```

该命令不会自动为另一组选择相同的候选文件或参数；如使用预生成候选，应在两组显式传入同一 `--candidate-file`，并检查输入哈希和有效配置。启用 Manager 时要调整请求上限、配置 Manager 服务和密钥。

固定收费实验建议两种旅客使用同一 `--fixed-toll-policy`；闭环 Manager 可能依据各组交通反馈选择不同收费，比较结果属于完整闭环系统差异，不是固定政策下的纯旅客效应。相同初始参数不能保证 LLM 与 MNL 在第一天采取相同行动。

## 核验输出

- `baseline-parameters.json` 和 manifest：参数、来源、文件哈希、随机种子与实现口径。
- `baseline-contexts.jsonl.gz`：每次选择时的共享可见事实与候选动作。
- `baseline-choices.jsonl.gz`：所有路线×时间组合的收费预期、经验、效用分量、概率、随机数和最终选择。
- `decisions.jsonl.gz`、`experiences.jsonl.gz`、`trips.jsonl.gz`、`executed-route-checks.jsonl.gz`：实际选择、经历及执行检查。

人日和模拟日不是独立仿真实验重复；尚无参数校准或真实人群外部验证。
