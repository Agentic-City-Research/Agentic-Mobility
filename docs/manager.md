# Manager v1：Sioux Falls 拥堵收费

从安装开始的完整操作流程见 [v0.2 中文详细使用指南](usage-guide.zh-CN.md)。本页主要说明 Manager 的实现口径和实验限制。

本版只有一个 Manager。研究者先用独立的无收费基线确定候选路段；程序加载这些路段及研究者给定的时段、价格档位，Manager 只能在这个有限集合内决策。代码不写死 Sioux Falls 路网，也不让 LLM 自行访问文件系统或扩展收费权限。

默认关闭。仓库中的 `scenarios/sioux-falls/manager.example.json` 故意使用空候选集合，不能直接改成 active 后运行。示例金额、阈值和时间范围均是工程演示参数，不是校准结果。

## 1. 三种运行模式、三种决策方法

| 配置 | 行为 |
| --- | --- |
| `mode: off` 或不设置 `MANAGER_CONFIG` | 不创建 Manager、不改变 car 路径成本、不产生收费文件 |
| `mode: shadow` | 收集客观数据并生成建议，但实际价格始终为 0；出行者不接收建议 |
| `mode: active` | 合法建议在下一轮执行；实际扣款进入 MATSim 评分，路径搜索包含收费成本 |
| `provider: fixed` | 所有候选路段、所有配置时段统一使用 `fixedLevel`；工程和固定政策对照组 |
| `provider: rule` | 有足够完整路段观测时，高于阈值升一档，低于阈值降一档，其余保持 |
| `provider: llm` | LLM 根据知识、场景、观测和政策历史输出完整价格索引表；每格最多升降一档 |

价格索引 0 表示不收费。因此 LLM/规则可以选择哪些候选路段、哪些时段收费。允许的组合是 `candidateLinks × 配置时间格`；本版不支持逐路段不同的许可时段、自动修改路网、区域收费、每日封顶、收入返还或个性化价格。

没有出行者满意度、意见征集、投票、对话反馈或多 Manager 协商。Manager 只读取 MATSim 客观运行结果；这与完全不读取仿真结果的“一次性静态政策生成”不同。

## 2. 先从无收费基线确定候选集合

1. 保持 `MANAGER_CONFIG` 未设置，使用你计划用于政策对照的出行者模型、样本、随机种子和仿真配置跑基线。
2. 选取基线较稳定的迭代事件文件，运行排序工具。不要混合不同场景、重复事件文件或收费实验。
3. 结合总延误、平均延误、通行量、路网位置与绕行条件，人工确认少量候选路段；保存选择依据及原始结果。
4. 固定候选集合后，再比较 no-toll、fixed、rule、LLM。不要针对每个方法分别重新筛选候选路段。

从项目根目录运行示例（替换为实际存在的基线文件）：

```powershell
python analysis/rank_toll_candidates.py `
  --network scenarios/sioux-falls/input/Siouxfalls_network_PT.xml `
  --events output/baseline/ITERS/it.100/100.events.xml.zst `
  --start 21600 --end 36000 --bin-seconds 1800 `
  --output output/candidate-review/baseline-link-time-ranking.csv
```

`--events` 可以接多个迭代文件，支持 XML、`.xml.gz`、`.xml.zst`；读取 zst 需先 `pip install -r analysis/requirements.txt`（使用其中的 zstandard）。脚本同时输出来源说明 `.provenance.json`，拒绝覆盖已有结果；它只排序，不生成或修改候选集合。请按实际网络文件名调整路径。

排序口径：`networkMode=car` 的完整 `LinkEnter → LinkLeave` 通行，按进入时刻分格，延误为 `max(0, 实际通行时间 - length/freespeed)`。不包含起点路段、未完成穿越以及通常没有 LinkLeave 的终点路段；不能视为完整车辆排队时长、边际外部成本或社会福利。脚本假定静态自由流速度；非标准场景若把公交车标成 car，需要先排除。脚本能检测本模块产生的非零拥堵收费事件，但不能证明外部数据没有其他收费：还需核对基线配置和 manifest。

## 3. 配置与启动

复制示例为一个新的实验配置，如 `scenarios/sioux-falls/manager.local.json`，然后手动填写 `candidateLinks` 中的真实 link ID 字符串。它们必须来自当前加载的网络，并允许 car。

重要字段：

| 字段 | 含义 |
| --- | --- |
| `candidateLinks` | 研究者确认的有向路段 ID；不同方向通常是不同 ID |
| `startTimeSeconds`, `endTimeSeconds`, `timeBinSeconds` | 从仿真零时起的秒数，采用 `[start,end)`；时间范围须整除格长 |
| `priceLevels` | 严格递增、有限、非负金额列表，第一档必须为 0；单位为场景货币单位，不自动等于美元 |
| `warmupIterations` | 启动后的零收费观测轮数，默认 5，即迭代 0–4 |
| `updateInterval` | 后续 Manager 决策间隔，默认 5 轮，区间内政策固定 |
| `freezeLastIterations` | 最后多少轮不再改变价格，供适应与评价，默认 5 |
| `minimumObservations` | 每路段、每格在整个观测窗口的最低完整通行次数 |
| `raiseDelaySeconds`, `lowerDelaySeconds` | 规则方法升降档阈值；滞回区间内保持 |
| `fixedLevel` | fixed 方法使用的价格索引，不是金额 |
| `memoryCycles` | Manager 保存并送入 LLM 的最近决策周期数；完整日志不截断 |

默认预热 5 轮、间隔 5 轮时：迭代 0–4 执行零收费；迭代 4 结束形成第一份政策，迭代 5 生效；迭代 9 结束再次决策，迭代 10 生效。若 `warmupIterations=0`，第一份政策也不是开跑前生成，而是在第一个 `updateInterval` 观测窗口结束后生成。没有剩余执行轮次时不调用 Manager。当前仅支持 `firstIteration=0`，不支持从检查点恢复。

```powershell
.\mvnw.cmd test package
$env:AGENTIC_PROVIDER = "mock"
$env:MANAGER_CONFIG = "scenarios/sioux-falls/manager.local.json"
java -jar target/agentic-matsim-0.2.0-SNAPSHOT.jar `
  --config scenarios/sioux-falls/config.xml `
  --iterations 20 --output output/manager-my-first-run
```

先在本地配置中设置 `mode=shadow, provider=rule` 检查审计文件，再设置 `mode=active`。`mock` 出行者用于工程测试，不能代表已经验证的 LLM 政策响应。注意：原应用会删除并重建指定输出目录，请每次使用新的输出目录。

使用 LLM Manager 时，将 JSON 的 `provider` 改为 `llm`，设置环境变量：

```powershell
$env:MANAGER_API_KEY = "你的密钥"
$env:MANAGER_BASE_URL = "https://api.deepseek.com"
$env:MANAGER_MODEL = "deepseek-chat"
```

Manager 的三个连接变量未设置时分别回退到 `AGENTIC_API_KEY/BASE_URL/MODEL`。Manager 与出行者 provider 相互独立；即使出行者是 mock，`provider=llm` 的 Manager 仍会调用付费 API，shadow 也一样。`.env.example` 仅作字段说明，程序不自动加载 `.env`。不要把密钥写进配置或提交到 Git。

关闭：`Remove-Item Env:MANAGER_CONFIG -ErrorAction SilentlyContinue`。切换模式在下次启动生效，不是运行中热切换。

## 4. 模块结构和执行顺序

- `ManagerSettings`：读取权限、时间格、价格档位，校验候选 ID。
- `TollPolicy`：不可变政策快照与范围、档位、步长验证。
- `ManagerDecisionProvider`：fixed/rule/LLM 共用接口。
- `LlmManagerProvider`：读取版本化提示与知识，通过兼容 Chat Completions 的接口请求结构化决策。
- `ManagerRuntime`：客观观测、周期调度、政策发布、费用事件、路径成本和审计。
- `IterativeLlmDecisionController`：先记录本轮旧政策下的体验，再调用 Manager，最后让出行者按新政策决策、重新寻路。

启动时由程序解析 MATSim 网络，自动提取候选路段的长度、自由流速度、有效容量和相邻路段。这些确定性信息提供给 Manager；无需把整个原始 XML 塞进提示词。当前没有完整 OD 矩阵、替代路线预测或出行者个体收入模型。

LLM 接收固定知识摘要 `src/main/resources/knowledge/congestion-pricing-v1.md`、工具/动作规则 `src/main/resources/prompts/manager-pricing-v1.txt`、当前政策、候选路网摘要、观测窗口和近期政策历史。它不能读其他文件、修改配置或运行任意代码。

输出必须包含每个候选路段的完整价格索引数组。缺失/多余路段、错误格数、越界/跳档、观测不足却调价、格式错误或 API 失败都会保留原政策，并记录失败类别。fixed 对照按预设执行，不受观测门槛和单档变化约束。

## 5. 收费、路径和记忆

仅向人口中的 car 驾驶者收费，排除公交车队；每次进入收费路段按进入时刻计价。旅程起点通过 `VehicleEntersTraffic` 上路不收起点路段费；终点路段若实际产生 `LinkEnter` 则收费；相同路段的真实重复经过会再次收费。没有区域入场一次性规则或每日上限。

实际扣款为负的 `PersonMoneyEvent`，由 MATSim 按货币边际效用计入评分；Manager 不额外手动扣分，避免双扣。car 路径搜索成本在原有成本上增加 `toll × marginalUtilityOfMoney`。保留 MATSim 起终点路径搜索约定：终点路段的收费时刻不一定被节点间最短路搜索精确优化，因此不宣称实现了完全精确的时变收费最优路由。正式实验宜优先选择中间通行路段，并检查起终点分布。

出行者提示包含下一轮执行政策及上一轮实际缴费，不把价格表伪装成个体路线费用预测。政策版本变化时，LLM 出行者的旧行动统计先存档，再重置评分比较/回退的最佳分数；近期历史保留且带政策标记，防止直接混合不同收费制度下的成绩。MATSim 普通出行者原有计划选择/学习机制不被替换，旧计划得分仍需要适应期更新；`updateInterval` 和最终冻结期应通过实验检验，不保证 5 轮已收敛。

## 6. 输出与可复现性

| 文件 | 内容 |
| --- | --- |
| `manager-manifest.json` | 生效配置、provider/model、提示/知识版本、收费口径 |
| `manager-scene.json` | 从本次网络提取的候选路段摘要 |
| `manager-observations.jsonl` | 每轮记录当前累计观测窗口，标注实际执行政策；不能把重叠窗口相加 |
| `manager-decisions.jsonl` | 观察所对应的旧政策、建议、校验结果、最终执行政策 |
| `toll-policies.jsonl` | 初始零收费及每次实际变化，含生效迭代 |
| `toll-payments.csv` | 逐笔正金额支出、person/vehicle/link/time/iteration/policy_version |
| `manager-memory.json` | 最近若干决策周期；shadow 历史不冒充已经执行的收费效果 |
| `llm-action-memory-policy-N.csv` | 收费制度切换前的出行者行动统计存档 |

观测包括完整 car leg 数、未完成 leg 数、完成 leg 总时长、完整 link 通行延误、实际收费收入和候选格平均延误。完成行程时长不包含未完成行程的剩余损失；需同时关注未完成量，不能仅比较平均值。无观测为 `null` 而非零拥堵。相关变化不等于政策因果效应。

同时保存基线来源、候选选择说明、Git commit/diff、网络/需求/评分配置、随机种子、Manager 配置和模型标识。LLM 温度 0 也不保证跨时间完全复现。没有累计 token 或本轮成本估计功能。

## 7. 验证和当前边界

`mvnw test` 包含权限/边界/规则/非法 JSON/KEEP/扣款事件/路径成本测试，以及不依赖 Sioux Falls 输入的小路网真实 QSim 评分对照。Python 排序工具测试：`python -m unittest discover -s analysis/tests -p "test_rank_toll_candidates.py"`。

这是可运行的研究原型，不是已经证明有效的 Sioux Falls 收费策略。尚未完成真实 LLM 政策效能评估、边际外部成本定价、公平性/福利核算、参数校准、统计显著性检验和大规模稳定性实验。短预热不替代你用于候选筛选的独立、充分适应的无收费基线。
