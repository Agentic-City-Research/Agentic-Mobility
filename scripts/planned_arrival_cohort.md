# 按计划到达时间筛选 LLM 出行者

从这一版本起，新启动的公共场景实验默认使用输入计划中目的地活动的 `start_time` 筛选 cohort：`0 <= start_time < 32400`，即严格在09:00前计划到达。

- 不再要求07:00–09:00出发；较早出发且符合计划到达时间的人同样纳入。
- 包括work和secondary等目的，仍限于每次出行恰有一个car leg。
- 同一个人只有符合条件的行程可由LLM控制；其他行程保留原生选路。
- 人群和计划到达时间在仿真开始前固定，逐日实际早到、晚到、未完成或重新选路均不改变名单。
- 缺少计划开始时间或计划到达早于计划出发的行程不纳入，并写入manifest诊断计数。没有用全局work截止时间、自由流预测或基线实际到达时间替代输入计划。
- 筛选本身不改变原有出发时刻、OD、活动日程与评分截止时间；当前联合动作会在后续决策阶段改变目标行程的计划出发时刻，见 [当前协议](manager_basic_joint.md)。选择控制对象不保证能在09:00前到达。

脚本默认等价于：

```powershell
-CohortSelection planned-arrival -PlannedArrivalBefore 32400
```

只生成名单并验证配置，不发模型请求、不运行交通仿真：

```powershell
.\scripts\run_public_experiment.ps1 -Demand 100 -SampleRate 0.1 -Seed 268 `
  -Policy llm -LlmAgents all -Days 500 -ValidateOnly
```

实际运行一个50人LLM、500日示例（需配置模型API密钥）：

```powershell
.\scripts\run_public_experiment.ps1 -Demand 100 -SampleRate 0.1 -Seed 268 `
  -Policy llm -LlmAgents 50 -Days 500 -MaxRequests 60000
```

结合manager时加 `-Manager`。控制全部符合条件的人时使用 `-LlmAgents all`，并按验证输出的 `minimumLlmCalls` 设置请求预算。选择和记忆反思通常每个受控行程每天各一次调用，repair会增加请求。`-LlmAgents 0`仍然是原生出行者，并不因为筛选改变而自动启用LLM。

匹配的E0也应保留相同筛选条件，仅改为 `-LlmAgents 0` 并去掉 `-Manager`。新cohort统计与历史按出发时间筛选的统计不能直接比较；全体仿真人口和路网容量不因cohort筛选而缩减。

复现旧的07:00–09:00计划出发筛选：

```powershell
-CohortSelection planned-departure -PeakStart 25200 -PeakEnd 32400
```

Java参数对应为 `--cohort-selection` 和 `--planned-arrival-before`。manifest schema更新为15，记录 `cohortSelection`、`cohortWindow`、`cohortDefinition`、`cohortSelectionDiagnostics`。原先出发高峰诊断窗口单列为 `departurePeakWindow`；它与新的到达筛选窗口不是同一个概念。

`morning/cohort.jsonl.gz`、`experiences.jsonl.gz`和`trips.jsonl.gz`新增 `plannedArrivalSeconds`，便于复核固定人群。LLM已有个人日程中保留原始计划活动开始时间；评分的 `latestArrivalSeconds` 保持独立含义。

修改前代码及JAR已保存为 `versions/2026-10-07_before-planned-arrival-selection`。仅切回旧筛选方式不需回退代码，使用上面的 `planned-departure` 参数即可。
