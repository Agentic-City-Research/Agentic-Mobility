# Manager v5：有监测与回退的小规模收费试验

本文描述显式 `-ManagerProfile controlled` 的监测与回退机制。当前默认 Basic 协议见 [manager_basic_joint.md](manager_basic_joint.md)。

当前模板版本：`morning-pricing-v10-daily-memory`；manifest schemaVersion=22。以下监测与回退机制只适用于显式 controlled 条件。

本轮修改针对 v4 的三个问题：收费道路进入次数下降但每次通行延误增加；连续分段费用叠加；模型文字承诺提前撤回而程序只能等 30 天更新。

## 默认运行方式

前 20 个模拟日不收费，第 21 天开始允许模型更新，之后每 30 天更新一次。每日自动回退检查自身不调用模型；日末记忆反思每天另有一次模型调用。

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents 0 -Manager -ManagerProfile controlled `
  -Days 500 -Demand 100 -SampleRate 0.1 -InnovationStopDay 2900 -Seed 268
```

另外分别运行 Seed=4711、9261，并保留相同种子的无收费对照。输出仍使用 output 下日期和简短参数命名，重复编号递增；本轮限制和版本记录在 manifest 中。

## 收费约束

| PowerShell 参数 | 默认值 | 含义 |
|---|---:|---|
| ManagerMaxPricedCorridors | 1 | 同时收费的走廊上限 |
| ManagerMaxCorridorMinutes | 5 | 每条走廊单次经过的条件收费上限，按原生路由时间成本换算 |
| ManagerMaxStepMinutes | 2 | 单次更新在任何 link/时间段上的加价上限；降价和清零不受此限制 |
| ManagerMaxUnavoidableShare | 0.5 | 参考路线使用者中结构上必须经过该 link 的 OD 比例上限 |

每条走廊最多一个正收费 link，禁止细分路段叠加。2026-10-12 起，controlled 与 basic 均使用全天收费：每个 link 只允许一条规则，`start=0`、`end=QSim endTime`，每日每次进入收取同一金额。整小时分箱只用于交通观测，不能选择分时收费。当前 SiouxFalls 权重下，1 货币单位对应约 3.875 路由分钟，因此首次单点收费最多约 0.516 单位；这些是实验控制参数，不是校准后的最优价格或人民币。

参考 OD 诊断在第一天原生路由及仿真完成后，由全体已路由 car leg 聚合并固定，包含背景人群；预检/启动时的诊断注明可能有未路由方案，实际运行第一天后更新 public-environment 文件。移除 link 后检查 origin.to 到 destination.from 的有向 car 可达性，起终点 link 视为必经。无参考路线使用者的 link 不凭空判为可绕行；其 unavoidableShare 为 null，硬比例检查不适用。参考诊断覆盖全日，不等于实际高峰付费车流；可绕行也不代表替代道路有富余容量。manager 仍需结合 public routeAlternatives 与其他道路观测选择入口，并说明不确定性。

## 每日回退

| PowerShell 参数 | 默认值 | 含义 |
|---|---:|---|
| ManagerReviewDays | 7 | 更新前的参考窗口及更新后的滚动观察窗口 |
| ManagerAdaptationDays | 7 | 收费后普通延误检查的适应期；严重失败可提前触发 |
| ManagerBreachDays | 3 | 连续超阈值的每日检查次数 |
| ManagerCooldownDays | 7 | 自动撤回后的无收费冷却期，期间跳过模型更新 |
| ManagerDelayTolerance | 0.05 | 全网日均延误允许增幅的下限 |
| ManagerLocalTolerance | 0.10 | 收费时段平均延误、其他道路延误允许增幅的下限 |
| ManagerNoiseMultiplier | 3 | 更新前每日指标标准差的倍数 |
| ManagerCompletionDrop | 0.01 | 全体已开始 car leg 或完整 link 完成率下降的阈值下限，1 个百分点 |

每次真正改变价格时，保存更新前最近 7 日的参考数据，并按**新政策相同收费 link/时窗**重新计算局部参考。阈值取默认增幅与 3 倍参考每日标准差的较大者；版本变化但价格不变时，不重置适应期和连续超阈值计数。

普通回退条件：全网延误持续超阈值；或者收费时段每次通行延误/其他道路延误明显上升，同时没有达到全网延误至少下降 5% 的保护条件。若全体已开始 car leg 的完成率或完整 link 完成率严重下降，则跳过适应期，直接安排下一迭代撤回全部收费。

撤回发生在下一天原生重规划与 QSim 之前，增加实际政策版本，写入 policies 和 policy-applications，原生路由成本与实际扣费同时使用零收费。冷却期只抑制预定模型调用，不额外调用模型；结束后等下一个预定更新点再决策。失败的模型更新保留当前政策，每日保护继续执行。

更新前参考可能仍包含上一项收费政策及自然学习趋势，**不是同状态无收费反事实**。这些阈值用于运行保护，不构成收费因果收益证明；阈值仍需通过匹配无收费实验和多种子结果评估。严重失败的完成率针对全部已开始的 car leg，不是所有计划 OD 的完成率。

## 评估与执行一致性

policyFeedback 同时提供全网、收费 link 全日、收费时段、收费 link 的非收费时段、其他 link 的总延误及每次完整通行平均延误，并提供全体 car leg 的开始、完成、中止、未完成数。总延误按完整路段耗时超过自由流耗时累计；起点部分路段时间不纳入完整通行统计。

hold 必须保持实际价格函数；test/adjust 必须改变价格函数；remove 必须实际减价或撤销，且不得同时加价。程序计算 executionDiff 并写入决策日志，作为新增、移除和调价事实。自然语言的具体假设仍是模型解释，不作为执行规则；提前检查由程序实现，模型只能承诺 executionSchedule 中的模型回顾日期。

新增日志：

- morning/manager-safety-reviews.jsonl.gz：每日参考、阈值、滚动指标、连续超阈值计数和待执行回退。
- morning/manager-safety-actions.jsonl.gz：实际撤回及触发依据。
- morning/manager-safety-skipped-updates.jsonl.gz：因冷却期跳过的预定模型调用。
- morning/manager-decisions.jsonl.gz 中的 executionDiff：程序核对后的真实价格差异。

manager 不读取个人记忆、画像、账单或研究端无收费结果。研究评估应继续比较同种子同期无收费实验，并检查完成率、完整行程时间及全网延误，避免用初期免费均值下降作为收费收益。

## 验证

在项目根目录运行 `mvnw.cmd -B test` 或 `./mvnw -B test`，运行脚本验证使用 `pwsh -File scripts/test_run_public_experiment.ps1`。测试使用离线客户端或本机测试接口。历史实验报告与个人代码快照不随开源目录分发。
