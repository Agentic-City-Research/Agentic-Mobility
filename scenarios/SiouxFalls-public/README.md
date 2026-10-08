# 公开 Sioux Falls 全天场景与早高峰路线选择实验

本目录是当前全天场景入口；`SiouxFalls-AM` 保留独立的早高峰单程场景。

**2026-10-07 当前协议**：LLM 联合选择路线与出发时间，原始时刻前 90 分钟至后 30 分钟，间隔 15 分钟。
Manager 默认 Basic，完整记忆最多 1000 英文词、最多 40 条规则，收费时窗与 15 分钟观测分箱对齐，无自动策略干预。
完整接口、信息边界及输出字段见 [当前协议](../../scripts/manager_basic_joint.md)。
下文历史 Manager 策略诊断及精确收费检索只适用于显式 controlled 条件/原生路由，不能混用于当前 Basic + LLM。

## 来源与复现边界

使用项目已有的 enriched Sioux Falls 网络、设施、全天人口文件：282 个节点、334 条路段。
这是 Chakirov/Fourie 场景的 car-only 数据谱系，不是文章里的经典 24 节点/76 路段 BPR 网络。
公开数据入口：[Mendeley v3，DOI 10.17632/2332kg48c8.3](https://data.mendeley.com/datasets/2332kg48c8/3)，
场景介绍：[MATSim Sioux Falls](https://matsim.org/gallery/sioux-falls/)。
本实现不重新下载或宣称本地文件与公开下载逐字节相同，实际输入的 SHA-256 记录在 manifest 中。

`config.xml` 将本地 `Souix Falls 2014/Siouxfalls_config.xml.orig` 的关键配置适配为 MATSim 2026：
全天 home/work/secondary 评分；work 开始最晚 09:00、开放 08:00–18:00；car 时间效用 0、
performing 0.96、money 0.062、car constant -0.562、distance rate -0.0004；lateArrival=-2.88、earlyDeparture=0。
hybrid 与 reference 默认保留 ChangeExpBeta 0.7、ReRoute 0.1、TimeAllocationMutator 0.1、计划容量 6，
创新策略在 2900 之后关闭；配置 lastIteration=3000，即 3001 次模拟。
运行脚本则要求明确 Days（默认 100）；缩短运行不等于完成了原配置的收敛实验。
hybrid 默认保留原生时刻变异，LLM 目标出发时刻由其联合选择覆盖；使用 `--no-native-time-mutation` / `-DisableNativeTimeMutation` 恢复旧固定时刻协议。

兼容性/工程差异：显式使用 MATSim 2026 的 accessEgressModeToLink，沿用原 walk 速度 0.8333 m/s
和绕行系数 1.3；线程数 4；较稀疏的原生 events/plans 输出；额外早高峰观测。
因此是公开场景的现代版本实验，不是对历史软件结果的严格复刻。
需要纯路网耗时请使用 carSeconds，不要将含接驳步行的 doorSeconds 混入比较。

200pct 默认为 168220 人的拥堵需求。`-Demand 100` 使用此前恢复的 84110 个原始 ID，
这属于需求情景变化；两个实验必须用相同人口、容量、接驳设置和随机种子才能直接比较。
本目录默认配置的流量容量和存储容量系数均为 `1.0`，使用原始路网的完整基础容量。
全量运行不抽样。`-SamplePersons` 仅用于程序冒烟测试，不同步缩放道路容量。

`-SampleRate` / Java `--sample-rate` 以采样率 `k` 固定抽取人口，并设置 QSim
流量容量系数 `flowCapacityFactor=k`、存储容量系数 `storageCapacityFactor=k^0.75`。
例如 `k=0.1` 使用 10% 人口，流量系数为 `0.1`，存储系数约为 `0.177828`；
100pct 得到 8411 人，200pct 得到 16822 人。
这些容量系数相对于路网 XML 原始容量，直接覆盖配置值；基础系数为 `1.0`。
例如比例 `0.25` 使用 25% 人口、流量系数 `0.25` 和存储系数约 `0.353553`；比例 `1.0` 使用全量人口与完整容量。
在本目录的默认配置中，省略参数也使用全量人口与两个容量系数 `1.0`。

比例须在 `(0,1]`，人数按 `floor(总人数 × 比例)` 计算；不能得到零人，也不能与非零
`-SamplePersons` 同用。`-PopulationSeed` / Java `--population-seed` 独立控制人口抽样，默认固定为 `4711`；也支持 SampleSeed / sample-seed 别名。
相同输入人口、Demand、比例与 PopulationSeed 选中同一批 person ID，更换仿真 Seed 也不改变该人口样本。
相同输入人口与 PopulationSeed 下，较小采样率选中的人员包含在较大采样率的样本中。
保留入选人员的全部计划，不按天重新抽人；早高峰 cohort 在抽样后重新统计。manifest 记录
sampleRate、populationSeed、actualPopulationSampleRate、两项有效容量系数与 populationSampling（含实际 seed 和 power-law 容量公式、存储指数 0.75），
统一采样不标为 diagnosticSubsample。默认输出放在 `sample0.1` 等独立目录中。

manager 和 no manager 对照使用相同 Demand、SampleRate、Seed、Days 及其余设置：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 300 -Seed 9261 -Manager
.\scripts\run_public_experiment.ps1 -Mode hybrid -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 300 -Seed 9261
```

抽样可减少计算量；离散车辆、链路存储下限与随机波动仍可能使不同采样率的结果有差异，
不能视为与全量仿真逐项相同。比较 manager 效果须在同一采样率下进行。

## 一个入口、三种实验模式

| 模式 | 路线与时间决策 | 用途 |
|---|---|---|
| hybrid（默认） | 所有人全天原生 ReRoute＋ChangeExpBeta＋TimeAllocationMutator，保留计划容量与时刻变异。选中人员的目标路线/时刻在原生重规划之后由候选策略覆盖，其他出行继续原生调整 | 主实验；LlmAgents=0 是原生基线，正数/all 是混合控制 |
| reference | 原生 MATSim 评分选计划、重路由、时间变异；全天均可演化 | 公开场景参考，不是 LLM 的严格对照组 |
| fixed-routes | 固定输入活动结束时间；所有人的计划不重规划；全部早高峰 car trip 使用离线候选策略 | 保留原有冻结背景/返程的辅助对照，不再作为主实验默认模式 |

hybrid 原生策略沿用 config 的权重（当前 ChangeExpBeta=0.7、ReRoute=0.1、TimeAllocationMutator=0.1），不是每天每人都重路由或调时。
`ReRoute` 产生新路线计划，`TimeAllocationMutator` 变异活动时间，`ChangeExpBeta` 根据既有评分选择计划；策略按权重抽取。
变异范围参数为 3600 秒，围绕所选计划继续变异，累计时刻变化可超过 60 分钟。
计划容量仍是 6。`-InnovationStopDay` 可关闭后续原生路线和时刻创新，不关闭 LLM 逐日决策。
只支持不改变活动结构/OD/mode 的上述三种策略；不使用整个 llm 子群的 KeepLastSelected，
因为它会同时冻结返程。LLM 人员其他出行照常参与原生计划选择、重路由和时刻变异。

本次控制逻辑整合没有调整 config 的评分、容量、车辆插入顺序或迟到惩罚。
当前 car 时间评分为 0，与旧 baseline 入口自动改成 -6 不同，不能直接比较旧 score。
QSim 的插入规则等有效设置记录在 manifest/output_config；改变控制方式不保证消除已观察到的堵塞。

出行者按自己的 OD 分别获得**最多四条候选路线**，不是所有人共享四条线路。
候选生成使用已有文献方法 **BFS-LE（Breadth-First Search with Link Elimination）**：
从原始 car 路网的自由流最短路开始；以“已排除的路段集合”表示一个子网络；
对每个子网络找到的路线逐条排除内部链路，按广度优先次序继续计算最短路。
对子网络集合与完整路线序列分别去重，默认先收集最多 16 条不同路径，再筛选最多四条。
始终保留根子网络的自由流最短路线 c0；其余路径按自由流时间、距离、完整 link 序列排序，
依次保留通过绕行限制、且与已保留路线重叠不超过阈值的路径。
这是基础 BFS-LE 后增加的本项目成本/差异筛选，不宣称该筛选或默认阈值来自原论文。
每一步最短路使用 MATSim 自带 Dijkstra，仅通过其链路通行检查排除当前子网络的边。
排除仅作用于独立 car 图上的搜索，不删除/改容量/改速度于 QSim 仿真网络。
这与旧项目“只排除第一条路线的边”不同：会继续探索新找到路线的下一层排除组合。

来源：[Rieser-Schüssler、Balmer、Axhausen，Route choice sets for very high-resolution data](https://doi.org/10.1080/18128602.2012.671383)，
方法说明也见 [AequilibraE 官方文档](https://www.aequilibrae.com/latest/python/route_choice/choice_set_generation.html)。
这里实现基础 BFS-LE，按 link ID 确定扩展顺序，不宣称复刻论文的全部优化、pass-node reduction 或 BFS-LE+LP。
它是有来源的候选生成方法，不是 MATSim 默认 ReRoute，也不是原生预热计划收集或四条 K 最短路径。

候选搜索成本明确为静态自由流秒数，不使用场景评分的随机广义成本，也不添加 epsilon 成本下限。
此成本只用于生成可选路线；公开评分参数、原生 reference 重规划及后续真实 QSim 都保持原逻辑。
候选不随随机种子、旅客身份和当日路况变化；LLM/离线候选策略每天看到同一静态自由流耗时，不接收全网历史或实时路况。
c0 是自由流最短时间路线；新生成的 c1–c3 按筛选后的成本次序编号。
导入候选文件时保留其原有编号、顺序和路径，不重新筛选或悄悄替换。
不足四条时不复制路线、不捏造路径；原始 OD 不可达则直接报错。
默认最多评估 10000 个不同子网络/OD，未收集到初始池目标就达到资源限制会标记 searchLimitReached，
只有真正穷尽子网络时才标记 searchSpaceExhausted，不能把有限搜索结果说成网络只存在这些路线。
即使最终已有四条候选，初始池未达到目标仍可能标记搜索受限。
旧候选 ID 与新版候选 ID 的路径含义不同，不能混用旧经历。
相同 OD 的计算结果缓存以节约时间，c0/c1 只在该 OD 内有意义。
路线集合在一次运行内保持稳定，以便累积个人路线记忆和定义 DSR；不同 OD 的集合自然不同。

基本检查：car 可通行、链路相接、OD 端点一致、无重复路径、内部路线无重复节点。
起点 link 不再进入内部路径；终点 link 只追加一次。
自由流时间与行驶距离均不含起点 partial link、包含终点 link，保持当前 MATSim 汽车路由口径。
起终点相同 link 的特殊 OD 只有一条路线；内部起終节点相同的 OD 不人为加入循环绕行。
质量诊断写入 candidate-quality.jsonl.gz：自由流时间比、距离比、两两重叠率。
前两项以 c0 为基准；距离比不是相对于“最短距离路径”。
重叠率=共享的有向 link 长度/min(两条路线行驶距离)，含共同终点、不含起点。
零距离路线与不同路线的重叠记为 0；比值分母为 0 且分子非零时记为 null。
默认只记录这些指标，不启用未经校准的绕行/重叠剪枝。需要筛选时显式设置参数并记录，
不要将自行设置的阈值说成论文或公开 Sioux Falls 的已验证参数。

cohort 默认按输入原始计划中目的地活动开始时间筛选 `[00:00,09:00)` 的合格汽车行程，含 secondary、
符合条件的返程等，不要求 home–work–home，也不是只选第一段；缺少/无效计划到达时间排除并归档。
同一个人可有多个受控 trip。接驳步行可能使 car departure 晚于活动出发时间。
全局路网观察窗口独立设置为 `[06:30,11:00)`，统计所有车辆，不限 cohort。
QSim 继续跑全天到 30:00，cohort 超过 11:00 才完成的行程仍被追踪。

fixed-routes 仍模拟全天。非早高峰行程与返程保留初始化后的路线和计划时刻，
但拥堵仍可使其实际出发/到达延误，因此不能称为“完全物理隔离返程影响”。
hybrid 同样模拟全天，但返程和背景路线可以原生演化，并非物理隔离返程影响。
fixed-routes 恢复当前 selected plan 的原始活动结束时间；hybrid 默认保留原生时刻变异，只有 `-DisableNativeTimeMutation` 才每日恢复原始活动结束时刻。
受控目标出行的离线策略写入原始出发时刻，LLM 写入所选候选时刻；背景和其他行程保留对应模式的原生时刻。路网拥堵及之前行程延误仍可能改变实际出发时刻。
09:00 是固定的 work 日程目标/迟到观测阈值，不是强制提前到达保证；本公共配置 lateArrival=-2.88，
本次联合动作修改保持此评分配置，全天活动效用仍然存在。

## PowerShell 运行

### 无 manager 的固定收费

在 `hybrid` 中指定 `-FixedTollPolicy`，加载并校验 JSON 文件中的 link、时窗、金额；该参数与 `-Manager` 互斥。
默认前 20 个模拟日免费，从第 21 天起每天重复相同规则。`-FixedTollFreeDays 0` 可从第一天收费。
只固定收费政策，原生路线选择和金钱评分仍正常运行；`-Policy mnl -LlmAgents 0` 不需要 API Key，也不调用模型。

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 500 -InnovationStopDay 2900 -Seed 9261 -FixedTollPolicy '.\scenarios\SiouxFalls-public\fixed-tolls-56_8-1unit.json' -FixedTollFreeDays 20
```

示例在 `56_8` 的 08:00—09:00 每次进入收取 1 个场景货币单位，是供对照实验使用的诊断策略。
文件格式、生效时间、规则校验、日志与修改方法见 [固定收费说明](fixed-tolls.md)。

### 可选的全路网自主拥堵收费

在主实验 `hybrid` 中添加 `-Manager`（Java 参数 `--manager`）启用管理者。
同时不指定 `-Manager` 和 `-FixedTollPolicy` 时保留无管理者、无拥堵收费的对照组。管理者也可与
`-LlmAgents 0` 原生出行者基线组合；是否调用 Manager 与出行者策略独立。

- 前 20 个仿真日不收费，第 21、51、81……天在原生重规划之前更新政策。
  MATSim 从 0 编号，因此迭代号为 20、50、80……；若 firstIteration 非零，
  节奏相对于该次运行的首日计算。政策持续到下次通过校验的更新。
- Manager 可从全部允许 car 的路段自主选择路段、时段及有限非负金额；
  Basic 没有候选收费路段表、价格档位或金额上限；完整政策最多 40 条规则。
  同一路段可有多个不重叠收费时窗，范围为 `[0, QSim endTime]`，支持跨 24:00 的时刻。
  检查只约束 JSON、路段可执行性、时间、金额及同路段时窗不重叠，合法报价不重设、不取整。
- 单位为场景货币单位。每次实际进入收费 car 路段收一次，含
  `VehicleEntersTrafficEvent` 对应的起点 partial link；其他进入由 `LinkEnterEvent` 计费。
  同一次进入的重复通知只收一次，离开后再经过同一路段再次收费；时窗按 `[start,end)` 查询。
  正收费生成负金额 `PersonMoneyEvent`，purpose 为 `congestion_toll`，并进入原生金钱评分。
- 原生 car 重路由保留原评分参数。参与信息实验的人员，包括其非受控行程，
  根据所属信息组使用当前公告或本人历史支付，路径时间仅使用自由流；范围外背景人员沿用原生公开收费路由。
  informed 候选费用包含当前公告下的预计收费；LLM uninformed 仅根据最近事实和自身摘要解释历史支付，当前费用未知，
  未知部分不当作免费。以静态自由流耗时推算进入时刻，预测仍可能与实际支付不同。
  实际行程支付总额提供给次日选路和日末独立记忆更新，
  完整收费收据归档；出行失败前已发生的收费仍计入支付。
- Manager 读取公共模型参数、截至前一日的全天路段统计、完整 car 路网、当前政策及自己的管理记忆；
  默认另提供结构化公共政策期历史，详见下节。
  全路段汇总加精简路段小时反馈是观测摘要，不限制收费动作空间。
  输入使用字段白名单构造，不能自动读取 Traveler 私人记忆、画像或个人支付日志。
  `manager-contexts.jsonl.gz` 是实际可见输入；其他研究日志不被自动注入 Manager。
- 非法政策默认最多 3 次输出尝试；耗尽时显式记录回退，保留旧政策和管理记忆。
  传输失败在现有有限重试耗尽后停止运行。Manager、出行者、记忆更新共用请求预算，
  启动前最低调用数检查包含所有预定 Manager 更新；`-ManagerMaxOutputTokens` 默认 8192。

在已有真实 LLM 命令中追加 `-Manager` 即可，例如：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy llm -LlmAgents 50 -Manager -Demand 100 -Days 100 -Seed 4711 -MaxRequests 30000
```

上述命令需要已配置 API 密钥。仅验证配置和导出候选时，可追加 `-ValidateOnly`，不调用 API。
输出在 `morning/`：`policies.jsonl.gz` 保存政策 ID、版本及完整规则；
`policy-applications.jsonl.gz` 保存每一天实际生效版本；`manager-contexts.jsonl.gz`、
`manager-attempts.jsonl.gz` 和 `manager-fallbacks.jsonl.gz` 保存管理者输入、响应与异常；
`toll-payments.jsonl.gz` 保存每次支付及政策版本；`person-payments.jsonl.gz`
保存付费人员的全天总额。`experiences.jsonl.gz` 与 `trips.jsonl.gz` 增加行程支付和收据，
`api-usage.json` 增加 `attemptedManagerCalls`。

### 历史 Manager 公共环境与反馈说明（controlled 条件）

当前 Basic 不提供行为成本指导、避让优先位置或自动保护，只提供事实及执行协议；详见顶部当前协议。

`morning-pricing-v4-public-exploration` 将公共环境和历史反馈合并为一个 manager 输入，
启用 `-Manager` 即同时提供公共环境说明、当前交通观测和历史政策事实反馈。
保留原有所有合法 car link、连续金额和时窗动作空间。
默认仍为 20 天免费、之后每 30 天更新。可通过 `-ManagerUpdateInterval N` / `--manager-update-interval N`
单独改变更新间隔；不会改变 QSim 容量、需求、原生策略、计分和逐 link 收费口径。

- `publicEnvironment` 给出实际人口比例、flow/storage 系数、容量周期、原始容量与名义缩放小时流量容量；
  两项容量系数分别解释，不对已缩放的网络再次缩放。还给出原生计划记忆和实际启用的路线/时间/方式响应，
  识别原生创新关闭时刻；没有时刻、方式或取消选择时，价格不能主动消除固定 OD 需求。
- `routingCostScale` 使用有效默认评分参数解释路由成本。本场景 1 个场景货币单位相当于 3.875 分钟路由时间成本，
  car 时间效用为 0 时仍包含 performing 的机会成本。这不是现实货币校准或完整计划下个人时间价值。
- `roadCorridors` 将中间 car 节点只有一个入链/一个出链的连续道路分段分组，孤立有向环也完整保留；
  当前路网的 334 个 car link 分为 76 条有向链。保留原逐 link 定价，并展示跨分段累积收费的时窗与时间等价。
  合计是假设所有相关进入均发生在该时窗的条件成本，不是实际个人支付；跨时窗、链中起终点须分别解释。
- `policyFeedback` 始终与公共环境一起提供，按每日实际执行的 policy ID/version 计算各轮延误、
  完整路段通行时间、进入/完成/失败/部分次数，
  给出各轮日均、按人口归一、汇总分母的每次通行指标和末最多 7 个观测日指标。模型自写记忆仍独立保留。
- 输入中展示最近 3 个实际版本期，同时按收费函数 fingerprint 汇总所有连续等价政策期；
  新版本编号不等于新干预，零金额规则不构成实际收费。完整版本期始终归档。
- manager 实际输入的 `network` 和 `linkTrafficTotals` 使用数组表格，分别按 `networkColumns` / `linkTrafficTotalsColumns`
  逐列解释；保留全部 car link 和公开统计，只省去每行重复字段名。启动环境归档仍保留对象形式的路网。
- `routeAlternatives` 展示延误最高的至多 8 条道路链在各自峰值时间箱的同方向替代路径，
  路径连接同一组有向链端点并避开整条原链。展示同箱路段旅行时间、通行量、长度、条件累计收费与路由时间等价。
  缺少完整通行观测时明确标为自由流估计，不能据此宣称畅通或存在剩余容量。
  这是路段时间之和的诊断代理，不是实际 OD 路径、用户可用替代或收费效果预测，也不限制收费动作空间。
- `executionSchedule` 给出真实更新间隔、下一次可执行更新日和剩余实验日。
  rules 的 start/end 是每日内秒数；最近 7 天统计不赋予 7 天后撤销政策的能力。政策更新失败时继续原政策。
- 输出要求 `rules`、`decision`、`memory` 三个字段。decision 记录 `action=hold/test/adjust/remove`、
  `evidenceBasis=untested/observational/observedTrial`、hypothesis、expectedNetworkEffect、uncertainty、evaluationPlan、revisitCondition。
  每项说明最多 400 个 Unicode 字符。允许不收费，但须区分尚未试验与已观察收费试验；缺少反事实不禁止开展试验。
  主目标仍是全网延误，不要求路段通行数必须下降，也不擅自将目标替换成最小化支付。
- memory 目标 800–1,200 字符、硬上限 2,000。完整 JSON、rules 与 decision 合法后，若仅记忆不合格则锁定政策，
  后续只请求记忆修复，计入原 repair-attempts 和请求预算。修复失败保留旧记忆并执行已验证政策，显式记录降级行为。
  非法规则、缺失 decision、非法/重复键 JSON 和截断响应仍不能执行。
- 免费前 20 天参照与相邻政策期变化均明确为描述性比较，没有匹配无收费反事实；不把自然学习改善当成收费因果效果。
  不注入个人记录、研究者预先跑好的 baseline、私人记忆或支付；不增加硬限价、自动政策回退或预设优选路段。

不再区分两轮信息模式，也无需指定反馈模式参数。
manifest schemaVersion=17，`pricing.mode` 区分 `none`、`fixed` 与 `manager`；启用 manager 时，`managerContext.feedbackMode` 固定为 `combined`，
`informationComponents` 记录实际组合的信息类别，模板/环境/反馈版本分别记录。

```powershell
# 统一 manager：每次更新同时读取两轮修正的信息；真实运行需要已有 API key
.\scripts\run_public_experiment.ps1 -Manager -Mode hybrid -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 500 -Seed 268

# 更新频率的独立实验条件；7 天不代表已验证足以适应
.\scripts\run_public_experiment.ps1 -Manager -ManagerUpdateInterval 7 -Mode hybrid -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 500 -Seed 268
```

`morning/manager-public-environment.json` 在启动/validate-only 时保存公共环境与初始道路链，便于运行前检查。
`manager-contexts.jsonl.gz` 保存模型实际输入。`manager-policy-evaluations.jsonl.gz` 在每次更新前与退出时
保存完整公共政策期事实，包括最后一次政策后的尾段；`scheduledManagerUpdate` 标明是否在预定更新时归档。
v4 的完整归档与模型中的压缩历史不是同一对象，`includedInManagerContext=false`；实际输入以 contexts 为准。
`manager-attempts.jsonl.gz` 记录实际请求和校验结果；记忆修复的 role 为 `manager-memory`。
`manager-decisions.jsonl.gz` 是最终接受决策，记录实际 policy fingerprint、是否改变收费、规则来源 attempt 和 memoryStatus。
`manager-memory-fallbacks.jsonl.gz` 记录合法政策保留旧记忆的情形；`manager-fallbacks.jsonl.gz` 记录整次政策更新跳过。
原始响应的 valid=false 不一定表示政策更新跳过，需要结合最终接受决策判断。
实际输入的 `managerMemoryMetadata` 标明记忆状态及最后写入迭代，保留旧记忆时 `previousDecision` 仍给出最新接受决策。
统计只覆盖已记录的过去日；完整路段时间不等于完整 OD 耗时，link 通行次数也不等于出行需求。

### 收费公告的信息实验：50% / 50%

当前 LLM 长期收费信息仅来自 500 字符摘要；以下精确 link/bin 检索描述保留为原生路由机制说明。

默认 `-InformedShare 0.5 -InformationScope controlled`：按受控人员分组，
同一个人的全部日期、OD 与行程使用同一分组，不逐日重新抽签。默认分组种子为仿真 Seed，
也可指定 `-InformationSeed`；informed 人数四舍五入，奇数人数两组最多相差一人。
`-InformationScope all` 可覆盖全部人口，包括传统背景人员；`-InformedShare 1` / `0`
分别构造全部知情 / 全部不知情对照。Manager 的公开统计输入与实际收费执行不受分组影响。

| 输入 | informed | uninformed |
|---|---|---|
| 基础路径 | 固定候选、距离、静态自由流时间、固定日程 | 完全相同的信息规则 |
| 当前收费 | 当前候选相关路段的公告时窗、金额、政策版本，计入预计路线费用 | 不提供公告、当前政策 ID/版本或当前报价；不能通过合计费用间接获取 |
| 历史支付 | 本人实际支付事件 | 仅本人实际支付事件；不会从他人、研究日志或政策表补全 |
| 全网历史/实时路况 | 不提供 | 不提供 |

uninformed 的收费记忆按 **personId + linkId + 时间分箱** 保存最近一次实际正支付。
默认 `-TollHistoryBinSeconds 3600`（同一仿真日时刻的小时区间），可配置；
历史事件只证明该次进入的支付，不推出公告的收费时窗，不能保证今天仍收同样金额。
日末收费进入个人经历与反思；其他行程的本人支付也可用于次日同路段同时间分箱的估计。
没有实际正支付的路段保持未知，即使此前未扣款也不会被自动标记为当前免费。
未完全观察的路线 `estimatedToll` 和 `estimatedMonetaryCost` 为 **null**，
另提供 `baseMonetaryCost`、`knownTollSubtotal` 及未知路段名单，让已有历史收费进入费用的可见小计。
已覆盖全部路段时给出历史费用估计，仍明确当前收费未知。未启用 Manager 的运行明确标注 charging_disabled。

原生路由的数值成本接口不能表示未知金额；uninformed 的非受控行程只加入已观察的历史收费分量，
无观察部分不加入该数值成本。这是计算上的缺省处理，不在 Traveler 输入或记忆中生成零价事实。

例如在已有运行命令后添加：

```powershell
-Manager -InformedShare 0.5 -InformationScope controlled -InformationSeed 4711
```

`information-groups.jsonl.gz` 记录固定人员分组，`cohort`、`decisions`、`experiences`、`trips` 和支付事件
记录 informationGroup；`metrics` 增加 informed / uninformed 的 cohort 统计。
`decision-contexts` 的 information/pricingKnowledge 保存真正可见的信息及其本人支付来源；
`payment-memory-final.jsonl.gz` 归档最终本人收费记录，属于研究日志，不提供给 Manager。

在项目根目录先打包（Java 25）：

```powershell
.\mvnw.cmd -B package
```

启动脚本为每次运行复制独立 JAR，结束后删除该副本。因此后续 `mvn package`
不会覆盖正在运行的实验所加载的类。启动复制期间须等待正在进行的打包完成。
完整控制台输出（包括 Java stderr）保存在 `output/_logs/public-*.log`，
启动时显示日志路径并记录实际运行 JAR 的 SHA256；退出码非零时也会显示该路径。
`morning/progress.json` 的 `daysCompleted` 只说明迭代进度，
完整结束仍须检查 `morning/status.json` 和最终导出是否成功。

主实验的原生基线：84110 人、全天原生路线与时刻调整，无 API；增加 `-DisableNativeTimeMutation` 可运行旧固定时刻条件：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -LlmAgents 0 -Demand 100 -Days 200 -Seed 4711
```

先验证混合流程（100 人低负荷诊断，控制其中早高峰人群，不调用 API）：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mock -LlmAgents all -Demand 100 -SamplePersons 100 -Days 3 -Seed 4711
```

真实 LLM：`-LlmAgents 50` 固定选择50位原始早高峰人员；`all` 包含全部目的，不限通勤者。
人员由种子固定抽取，同一个人的所有 cohort trip 均由候选策略控制，不按天重新抽人。
真实出行者 API 在 `-Policy llm` 且选中人数非0时启用；`-Manager` 在预定更新日独立调用管理者 API。
`-ValidateOnly` 始终不调用 API。
密钥只从 `AGENTIC_API_KEY` 环境变量读取，绝不写进配置或输出。
模型、地址、thinking、输出 token 上限可用环境变量或 `-Model`、`-BaseUrl`、`-Thinking`、`-MaxOutputTokens`，命令行优先。
新增可选 `-Temperature`、`-TopP`、`-GenerationSeed`、`-TokenLimitField`、`-ResponseFormat`、`-GenerationParams`。
只设置服务支持的参数；未设置的 temperature/top-p/seed 不发送，不能声称不同服务的默认采样行为完全一致。
Thinking=auto 仅对 api.deepseek.com 发送 disabled，其他服务不发送；本地兼容服务建议显式 omit。
MaxOutputTokens 是选路响应 token 上限；MemoryMaxOutputTokens（默认1024）单独控制仿真后摘要响应。
ReasonMaxChars（默认240）限制理由。摘要目标长度为240字符，MemoryMaxChars默认及最大值为500 Unicode字符，可显式降低；超长输出进入修复而非静默截断。

```powershell
# 请在当前 PowerShell 设置自己的 AGENTIC_API_KEY；下例只是命令，不自动执行。
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy llm -LlmAgents 50 -Demand 100 -Days 200 -Seed 4711 -Concurrency 8 -MaxRequests 30000
```

全量 LLM 请先只预检查，不发请求：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy llm -LlmAgents all -Demand 100 -Days 200 -Seed 4711 -ValidateOnly
```

当前100pct cohort是58450段/日，200日全量至少11,690,000次选路；默认再加同样数量的摘要更新，合计至少23,380,000次调用。
LLM 出行者固定使用 MemoryMode=llm，每日执行选路和摘要更新；输出修复与传输恢复可能继续增加调用。
默认预算25000不足；真实运行会在发请求前检查最少调用数，要求明确指定足够的 `-MaxRequests`。
此处不给出自动启动全量收费实验的命令。可通过 `-TimeoutSeconds`、`-LlmStallSeconds`、
`-RepairAttempts` 设置完整响应超时、批量停滞阈值和非法输出修复次数。
API失败或修复耗尽会停止运行，不静默改用 MNL。
预算按客户端调用次数计数，包括非法输出修复和批次重试；并发提交前预留额度，防止超额。
api-usage 的 attemptedDecisionCalls、attemptedMemoryCalls 分别记录选路和摘要尝试，attemptedTotalCalls 是共用预算的总次数，requests 记录成功响应。
客户端内部 HTTP 重试不单独计入此预算；它不是严格的账单金额或 HTTP 请求总量上限。

`hybrid -Policy mnl/shortest/mock -LlmAgents N/all` 将同一批人的决策替换为明确标注的离线策略。
它不是 LLM，manifest 的 llmPersons=0，目录增加 controlledN 区分不同控制人数。
原生基线与真实 LLM 的搜索空间不完全相同；判断能力的辅助对照可使用 hybrid 离线策略，
保持相同人口、候选文件、背景/返程调整与信息来源，仅更换早高峰选择器。

先检查全量原始人口、OD 可达性和候选路线，无 QSim、无 API：

```powershell
.\scripts\run_public_experiment.ps1 -Mode fixed-routes -Demand 100 -Seed 4711 -ValidateOnly
```

可用以下参数调整初始候选池和筛选；Java 参数名同时适用于 Slurm：

| PowerShell 参数 | Java 参数 | 默认值 |
|---|---|---:|
| `-CandidateSearchLimit` | `--candidate-search-limit` | 10000 |
| `-CandidatePoolSize` | `--candidate-pool-size` | 16 |
| `-CandidateMaxTimeRatio` | `--candidate-max-time-ratio` | 2.5 |
| `-CandidateTimeSlackSeconds` | `--candidate-time-slack-seconds` | 300 秒 |
| `-CandidateMaxDistanceRatio` | `--candidate-max-distance-ratio` | 2.5 |
| `-CandidateDistanceSlackMeters` | `--candidate-distance-slack-meters` | 2000 米 |
| `-CandidateMaxOverlap` | `--candidate-max-overlap` | 0.85 |

时间和距离分别满足“比值不超过限制 **或** 相对 c0 的绝对增加不超过余量”；两项都需通过。
例如时间比超过 2.5、但只多 200 秒的短途备选仍可通过时间筛选；距离需另外检查。
余量设置为 0 可使用严格比值限制；比值设置为 Infinity 可关闭对应成本限制。
重叠上限约束所有保留路线对，设置为 1 可关闭。距离基准是最快 c0，非最短距离路线。
这些默认值用于启动敏感性分析，不代表已校准的合理通勤阈值。

初始搜索不以质量过滤提前剪枝，重复路径的排除组合也继续扩展。
初始池达到目标、搜索空间耗尽或资源上限到达后才统一筛选；不为凑足四条扩大绕行或复制路线。
扩大初始池可能增加准备时间；更严格筛选可能只留下 1–3 条。
`candidate-quality` 新增 generatedCandidateCount、initialPoolTargetReached、costRejectedPaths、
overlapRejectedPaths、selectionOmittedPaths 和 searchStopReason；qualityRejectedPaths 为成本与重叠拒绝数之和。
selectionOmittedPaths 表示成本合格但在已选满四条后未再评估重叠的路径；导入文件的初始生成数和目标状态为 null。
新生成时 manifest 的候选选择版本为 cost-diversity-v1，导入时为 frozen-file-validation-v1；schemaVersion 为 13。

建议先在同一采样率/seed 下生成候选，再为有无 manager、不同 LLM 覆盖范围和模型复用同一个文件。
导入文件仍检查合法性及当前质量限制，违反限制会报错而不会自动删改。
若复用旧版无限制候选池作对照，显式关闭新增限制：

```powershell
.\scripts\run_public_experiment.ps1 -Demand 100 -SampleRate 0.1 -Seed 9261 -ValidateOnly `
  -CandidateFile '旧实验目录/morning/candidates.jsonl.gz' `
  -CandidateMaxTimeRatio ([double]::PositiveInfinity) `
  -CandidateMaxDistanceRatio ([double]::PositiveInfinity) -CandidateMaxOverlap 1
```

不同策略/随机种子可复用预检查导出的同一 candidates.jsonl.gz；旧 candidates.jsonl 也可直接导入：

```powershell
$candidatePool = '你的预检查目录/morning/candidates.jsonl.gz'
.\scripts\run_public_experiment.ps1 -Mode fixed-routes -Policy mnl -Demand 100 -Days 100 -Seed 4711 -CandidateFile $candidatePool
.\scripts\run_public_experiment.ps1 -Mode fixed-routes -Policy shortest -Demand 100 -Days 100 -Seed 4711 -CandidateFile $candidatePool
```

将路径替换为实际预检查输出。导入会校验所有行的 car 连通性、去重、ID、自由流时间，
并对 cohort 使用的 OD 复核 c0 为最短自由流路线及当前阈值；缺少任何受控 OD 会报错，不自动补建。
manifest 记录候选文件 SHA-256，便于确认对照组使用相同候选。
重跑相同配置会自动追加 `-2`、`-3` 等序号，已有目录不覆盖。

真实公开输入的 100 人、2 日程序冒烟测试（不作为科研结果）：

```powershell
.\scripts\run_public_experiment.ps1 -Mode fixed-routes -Demand 100 -SamplePersons 100 -Days 2 -Seed 4711
```

公开场景参考，保留原配置 3001 次模拟、2900 后停止创新（运行耗时较大）：

```powershell
.\scripts\run_public_experiment.ps1 -Mode reference -Demand 200 -Days 3001 -Seed 4711
```

明确缩短为 500 次、450 后关闭创新的研究运行（参数覆盖会记录，不能称历史原样复现）：

```powershell
.\scripts\run_public_experiment.ps1 -Mode reference -Demand 100 -Days 500 -InnovationStopDay 450 -Seed 4711
```

冻结背景的辅助离线对照，固定出发、每个 OD 内按预计汽车耗时做 MNL 路线选择，无 API：

```powershell
.\scripts\run_public_experiment.ps1 -Mode fixed-routes -Policy mnl -Demand 100 -Days 100 -Seed 4711
```

`-Policy shortest` 是最小预计时间规则；`-Policy mock` 是保留已选择最多的路线，
用于测试接口，不是 LLM 实验，也不是有真实人类偏好参数的模型。
MNL beta 默认 0.3/min 是显式研究参数，不宣称来自公开场景校准或文章最优设置。
直接 Java 可通过 `--logit-beta` 修改。历史记忆不使用 EWMA，`--ewma-alpha` 已移除。
MNL/shortest 的目标是预计汽车耗时，费用、到达目标和习惯不会自动进入选择概率。
fixed-routes 的 MATSim score 用于报告；KeepLastSelected 不按 score 重新选路。
因此改变 lateArrival 不会自动改变此离线策略的行为。后续对比 LLM 时应明确双方的信息与偏好条件。

## 输出目录命名

PowerShell 脚本与直接 Java 入口默认都把每次实验直接放在项目 `output/` 下，
不再按模式、采样比例等嵌套多层目录。无需填写 `-Output`。
日期默认使用上海时区的当天日期，`-RunDate 2026-10-06` 可指定批次标签；不会改变仿真时间。

例如，100% 原始需求、10% 人口采样（流量系数 0.1、存储系数约 0.177828）、500 日、seed=268 的原生基线：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 500 -Seed 268
```

在当前公开配置下，生成：
`output/2026-10-07_day500_llm0_noMgr_rr0.1_tm0.1_seed268/`（以实际实验日期为准）。
第二次运行相同名称，末尾自动变为 `seed268-2/`，第三次为 `seed268-3/`。
已有文件或空目录也视为占用；同时启动的实验分别占用独立序号。

目录名保留日期、实际天数、实际 LLM 人数、manager 开关、ReRoute 权重、启用的时刻变异权重 `tm0.1` 和仿真种子。
启用管理者标为 `mgr`，无管理者标为 `noMgr`，例如 `output/2026-10-07_day500_llm0_mgr_rr0.1_tm0.1_seed268/`。
固定收费且无 manager 时增加 `fixedToll`，例如 `output/2026-10-07_day500_llm0_noMgr_fixedToll_rr0.1_tm0.1_seed268/`。
模式/路线策略名称、需求、人口采样、flow/storage 系数、manager 版本、更新间隔、创新停止迭代和模型不显示在目录名中。
这些参数完整保存在 manifest；不同隐藏参数的实验若同名，也自动追加 `-2`、`-3`，不会覆盖结果。
LLM 旅客保留记忆模式，离线受控策略标出 `ctrl人数`，MNL 额外标出 beta。
`-LlmAgents all` 使用实际筛出的 LLM 人数命名，离线策略的 LLM 人数仍为 0。

预检查在种子前增加 `_validate`；采样方式及实际容量请从 manifest 核对。
非常长的显示参数组合会缩短并附加哈希，日期、种子和预检查标记保留可读；
完整参数始终保存在 `morning/manifest.json`；其中 `outputNaming` 记录参数名称、实际目录和重复序号。

`-Output` / `--output` 仍可显式指定完整目录，重名时同样自动追加序号。
旧 AgenticMobilityApplication 保留旧命名与双向决策流程，早高峰实验使用这里的入口。
旧实验目录保持原样。控制台日志统一存放在 `output/_logs/`。
建议独立使用 4711/9261/278 三个种子。
如 Windows 执行策略阻止脚本，可以直接运行 Java，无须修改系统策略：

```powershell
java -Xmx8g -cp .\matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment `
  --mode fixed-routes --policy mnl --demand 100 --seed 4711 --days 100 `
  --run-date 2026-09-28
```

Java `--help` 显示全部参数；时段参数为午夜起秒数，07:00=25200，09:00=32400。
控制全缓冲时段人群可用 `--peak-start 23400 --peak-end 39600`，这会改变受控人群，必须标注。
不要通过旧 AgenticMobilityApplication 入口运行本设计，它仍保留此前双向决策逻辑。

## 输出与定义

原生 MATSim 的 scorestats/output_config/events 等照常输出，额外文件在 `<output>/morning/`：
新实验所有 JSONL 均逐行直接写入 GZIP 压缩流，文件后缀为 `.jsonl.gz`。
输出流只保留固定大小缓冲区；MorningMemory 另外保留每 trip 的完整经历，因此长实验的内存占用会随人数×天数增长。
默认 TripOutputInterval=1；experiences 无论该参数如何设置，均保留全部 cohort 的每日事实。
压缩不是抽样。manifest schemaVersion=11 记录记忆、模板、候选展示顺序、实际模型生成设置、记录输出间隔，以及可选 Manager 的政策节奏、收费口径与输入隔离边界；travelerInformation 记录分组范围、比例、种子、实际人数及历史收费分箱；outputNaming version=4 记录最终目录、显示名称和重复序号。
每轮刷新缓冲，正常关闭时写入完整 GZIP 校验尾；写入/刷新/关闭失败会报错。
进度、状态、manifest、api-usage 等小型 JSON 仍可直接读取；原生 `.zst` 输出沿用 MATSim 设置。
旧输出目录保持原样，新版本不会自动转换已有文件，也不会覆盖已有实验目录。

| 文件 | 含义 |
|---|---|
| manifest.json | 输入哈希、人数、时窗、候选数量分布、评分/实验设计与覆盖参数 |
| cohort.jsonl.gz | 全部追踪的原始 trip，含真实人口属性、目的、日程时刻、OD、controlled 标志 |
| candidates.jsonl.gz | 每个不同 OD 的固定路线 ID、link 序列、自由流时间与质量指标；可通过 -CandidateFile 复用，兼容旧 .jsonl |
| candidate-quality.jsonl.gz | 每 OD 评估子网络数、实际最短路调用数、断连/重复/筛除数、搜索上限或穷尽标记及各路线质量 |
| decisions.jsonl.gz | 受控 trip 的每日选择、原生路线及覆盖后路线指纹，不含出发时间改动字段 |
| context-samples.jsonl.gz | 每日前 ContextSamples 个受控上下文样本 |
| decision-contexts.jsonl.gz | 每次真实LLM决策的原始事实输入，完整保留，可用于固定情境回放；不含当天结果 |
| experiences.jsonl.gz | 全部 cohort 每个 trip 每日实际经历与执行路线指纹，不随 TripOutputInterval 抽样；未知值为 null |
| route-use.jsonl.gz | 结束时各受控 trip 累计候选路线使用/完成次数与完整经历数；仅供分析，不传给 LLM |
| choice-transitions.jsonl.gz | 上次经历、本次选择、前后预测信息；用于分析换路与迟到/拥堵/费用信息变化的关系，不自动推断因果 |
| llm-results.jsonl.gz | 有效选择、理由、输出修复次数、总调用耗时、上下文/提示哈希与模型设置 |
| executed-route-checks.jsonl.gz | 按 VehicleEnters/LinkEnter/VehicleLeavesTraffic 事件核对实际经过路线；完成段需全匹配，未完成段检查已执行前缀 |
| trips.jsonl.gz | cohort 每日真实汽车/OD耗时、延误、迟到、完成标志及 controlled/decisionMaker |
| metrics.jsonl.gz | cohort 汇总、native/controlled 分组、全路网车辆积累；分组含全体通勤者准时率 |
| attempts.jsonl.gz / api-usage.json | 实际逐次 system/user 提示、回复、输出或调用错误、耗时、用量与模型参数；用量缺失时明确标注，不当成已知零 |
| link-hours.jsonl.gz | 全路网按进入小时分箱的路段通过量、耗时/自由流比、延误、未完成/中止数量 |
| progress.json / status.json | 逐日状态与是否完成指定模拟次数 |

压缩文件无需整体解压即可逐行分析。例如实验结束后用 Python 读取：

```python
import gzip
import json
from pathlib import Path

path = Path(r"你的实验目录/morning/trips.jsonl.gz")
with gzip.open(path, "rt", encoding="utf-8") as stream:
    for line in stream:
        trip = json.loads(line)
        # 在这里逐条处理，避免 list(stream) 或一次性读入全部记录。
```

Java 的 JsonlIO.newReader(Path) 同样支持逐行读取新 `.jsonl.gz` 与旧 `.jsonl`。
压缩后的文件不能直接用 Get-Content 当作文本读取；实时进度查看 progress.json。

hybrid/fixed-routes 首轮在 IterationStarts（PrepareForSim已路由）完成覆盖；首轮没有重规划事件。
后续轮在普通 ReplanningListener 中覆盖：核心 PlansReplanningImpl 已经完成，
而核心 BeforeMobsim 的 plans 输出尚未发生。不是在 IterationStarts 覆盖后再让 ReRoute 重写。
当天 plans、decisions 的 appliedRouteFingerprint 和 trips 的 routeFingerprint 应一致，回归测试会核对。

DSR 用固定 cohort 的所有 trip 路线序列变化数 / cohort trip 数；首日为 null，
reference 也追踪原始 cohort，因此时间变异后还会报告 actualDeparturesInPeak。
平均耗时只对已完成相应汽车段或 OD 的样本计算；未完成/未出发必须同时报告。
excessCarSeconds=max(0,实际汽车耗时−当日实际选路自由流时间)，不相对于最短路，
会包含入网等待和离散时间步带来的差值，不能全部解释为纯排队延误。
迟到以真正进入 work 活动的时刻计算，不把 car arrival 当作到公司时间。
路网车辆积累基于 VehicleEnters/LeavesTraffic，不把停车车辆或接驳步行当作在网汽车。
link-hours 是完整小时/全天数据；6:30–7:00 不能从 6:00–7:00 的整小时汇总精确切出，
需要精细时窗的路段分析时使用保留的原生 events。网络积累窗口则已精确裁切。
逐日汇总始终输出；长实验可 `-TripOutputInterval 10` 降低逐 trip 输出量，首个日程/候选准备不受影响。

## LLM 决策边界

实现 `MorningRoutePolicy.choose(Context)`，返回 `Decision(routeId, reason)`；
真实 API 适配器为 MorningLlmPolicy，复用现有 DeepSeekClient、批量并发/停滞检测及用量记录。
返回 JSON 仅允许 routeId、reason；伪造路线 ID、偏移、返程或其他字段均被拒绝并要求修复。
`MorningRoutePolicy.validate` 会拒绝非该旅客 OD 候选中的路线 ID；接口没有时刻偏移或返程重选字段。

选路提示包含每条路线预计耗时、费用、预计到达、原始个人属性、固定画像、活动日程、目的与到达目标、
旧自写摘要，以及最近 5 次未经平滑的逐日真实经历（可通过 HistoryWindow 修改；不含当天结果）
（实际耗时、到达、迟到、中止/未完成标记；未知耗时不当成零）。
全部候选的预计耗时统一使用静态自由流时间，每一天都相同，不使用全网历史/实时旅行时间。
预计到达包含计划接驳步行耗时；本人实际拥堵经历仍可通过私人记录和摘要提供。
forecastObservedDay 固定为 null，estimateSource 为 static_free_flow；收费知识与时间基础信息分开记录，
lastPersonalExperienceDay 标注个人最近一次完成该路线的日期。
不偷看当天结果；不传 MATSim 总评分。
MorningPromptRenderer 将内部 Context 转换为统一英文自然语言模板 morning-information-v6。
它不发送人员ID或 MATSim 总评分；收费公告与本人支付会提供相关路段ID以对应收费记录，
其余输入为可解释的出行事实、日程和明确输入的个人属性。
日程从初始输入保存，不把仿真后实际时刻冒充原本的计划日程。
长期完整经历保存在 MorningMemory，并另存 experiences；选路提示包含长期自写摘要和窗口内具体经历，不再提供累计使用记录。
内部选路 Context 为离线 mock 与核查保留统计字段，但发送给模型的自然语言提示不含这些字段；原始 Context 存档不等于实际模型输入。
不使用习惯权重，不要求优先熟悉路线，也没有强制探索概率；是否形成习惯应按实际选择行为评估。
静态路径信息可能遗漏拥堵、入网等待等延误，提示明确说明这一限制。
习惯/记忆按个人 trip（稳定 OD 日常出行）累积。初始真实习惯未知，不捏造历史；
年龄/性别/是否就业/汽车可用性沿用公开人口属性，缺失的数值收入、时间价值、迟到容忍度仍未知。
默认 PersonalProfiles=legacy 延续旧版高/中/低收入、保守/积极探索、三类守时倾向，明确标注为合成画像而非人口实测数据。
画像按独立的人员ID散列和 ProfileSeed（默认实际 Seed）生成，整个实验不变，也不受控制人数或候选遍历顺序影响。
同一随机种子并不保证复现旧版顺序抽样得到的逐人画像；要精确沿用旧实验，应指定 ProfileFile 为旧 agentic/roster.json。
也可复用新 morning/profiles.jsonl.gz 或带 profile 的 cohort 文件；缺少任一受控人员或画像冲突会直接报错，不静默补随机画像。
PersonalProfiles=input 可关闭合成画像，只使用原始属性；导入画像时必须选择 legacy。
固定到达要求优先：SLIGHT_LATE 描述的是主观敏感度，不会放宽工作9点截止时刻；非工作出行不会因守时类型而被添加工作截止时刻。
要研究真实异质偏好，需要另提供有来源的偏好数据或明确标注为合成实验，而非称其已由公开场景验证。
当前自然语言个人信息识别 age、sex、employment、employed、carAvail、income、incomeUnits、lateToleranceMinutes、valueOfTime、preferences、scheduleRequirements。
其他原始属性仍存档，但不会不加区分地全部送入模型；如另有个人偏好字段，先显式映射并记录单位。
llm 是真实 API 策略；mnl/shortest/mock 是离线策略。开发验证不启动收费调用。
MorningLoop 收集整批有效选择后，才在控制线程写入当天目标路线，避免并发修改 MATSim 计划。
全程没有把 MATSim 总评分交给 LLM；原生全天评分仍可影响其其他出行的计划选择。

研究重点是 LLM 决策能力时，LLM 与直接对照应使用同一人口、OD 候选文件、
固定出发时刻、评分参数、路况信息及原始经历格式；不要同时更改场景与路由方法。
reference 保留原生选计划与时间变异，可作为公开场景参照，但不是单变量的 LLM 对照。
MNL/shortest/mock 只作为明确标注的离线比较或接口诊断，不能称为原生 MATSim 评分选计划。
只有一条候选的 trip 没有路线选择空间，应在判断能力分析中单列，不当成 LLM 选择能力的证据。


## 自写长期摘要、事实档案与统一模型比较

MemoryMode 只支持 llm，省略参数时也使用 llm：截至昨天的摘要+最近 HistoryWindow 次经历+固定画像+今日预测 → LLM选路 → QSim → 保存真实经历 → LLM更新摘要 → 下一天。
摘要从空开始，各 agent 的行程摘要按 personId/tripIndex 隔离，不向其他人提供。morning-reflection-information-v6 更新输入含旧摘要、最近 5 次真实经历（包括今天；不足 5 次则全部）、个人属性与固定画像、到达期限、可用路线 ID，以及本人其他行程的实际支付记录；不提供当前公告或全网路况。
摘要窗口固定为 5 次，不受 HistoryWindow 影响；HistoryWindow 只控制选路前的具体经历窗口。更新不传累计选择/完成次数、全量历史或交通预测。
累计次数继续保留为内部分析统计和 route-use 输出，不作为习惯权重，也不经选路提示传给 LLM。
摘要让模型自主保留重要事件、熟悉程度、认识和不确定性，不设置习惯权重、不强迫熟悉路线优先；摘要是主观解释，不替代原始事实。
摘要提示目标为240字符，硬上限500 Unicode字符；241至500字符的摘要不会仅因超过目标长度而修复。
保留模型自然产生的遗忘和失真，不人为注入错误，不执行事实纠正或从完整档案检索补回遗忘内容。
摘要中的不可用cN路线编号仅写入memory-attempts的unknownRouteReferences诊断字段，不因此拒绝、重写或覆盖摘要。
程序只对摘要的JSON结构、字符长度和输出截断进行修复；输入时序仍严格检查。实际选路的routeId必须属于当前可执行候选。
批量更新全部返回有效结果后才提交摘要；输出修复/传输恢复耗尽或调用预算不足会使实验失败，不悄悄沿用旧摘要。
LLM 出行者没有关闭自写摘要的模式；mnl/shortest/mock 与原生 baseline 不会启动摘要模型调用。Manager 的记忆机制独立于此参数。

新增日志全部位于 morning，使用 jsonl.gz：

- profiles：固定个人画像，可直接复用。
- memory-contexts：每次更新的旧摘要、最多 5 次含当日的实际经历、个人属性与画像、到达期限、可用路线 ID，以及人员/出行段/日期核查元数据；无累计次数。
- memory-attempts：实际发送的提示、原始响应、修复次数、耗时、用量和错误。
- memory-updates：已提交的逐日摘要、上一版内容、版本、人员/出行段与模型设置。
- memory-final：退出时的最终摘要和更新日期；异常退出同样留存已成功提交的状态。

完整事实存档和最终摘要不是MATSim的完整断点：本版本仍不支持自动恢复计划、路况和所有运行状态后续跑。
固定回放会保留输入里的画像与摘要，但不运行QSim、不更新摘要；旧 facts-v1 文件缺少新字段时按空摘要、无合成画像读取。

本地兼容模型示例（地址仅适用于模型与仿真在同一台机器，模型名必须匹配服务；须自行设置对应AGENTIC_API_KEY）：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy llm -LlmAgents 3 -Demand 100 -Days 7 -Seed 4711 -Model qwen3.8-27b-fp8 -BaseUrl 'http://127.0.0.1:8001/v1' -Thinking omit -MemoryMode llm -PersonalProfiles legacy -HistoryWindow 5 -MaxOutputTokens 512 -MemoryMaxOutputTokens 1024 -MemoryMaxChars 500 -MaxRequests 100 -Output 'output/summary-check/new-run'
```

Thinking=omit 不代表关闭模型自身的思考模式；如具体Qwen模板支持，可额外指定 GenerationParams='{"chat_template_kwargs":{"enable_thinking":false}}'，需在实际模型与服务版本上验证。
复用同一 CandidateFile 和 ProfileFile，并保持两个调用阶段的模型参数、摘要限制及模板一致，再比较不同模型。

先保持完整交通背景，只调整受控人数，避免 SamplePersons 改变拥堵环境。首轮记忆为空。
建议先跑3人、7天真实模型，检查提示和执行，再增加人数与天数。下面的命令需要自行设置 API key 才会调用服务：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy llm -LlmAgents 3 -Demand 100 -Days 7 -Seed 4711 -HistoryWindow 5 -Model deepseek-flash -Thinking disabled -MaxOutputTokens 512 -MaxRequests 100
```

跨模型闭环实验须复用同一 CandidateFile、ProfileFile、人口、seed、出发时刻、历史窗口、MemoryMode、摘要限制、候选展示顺序和信息模板；每个新输出目录从空记忆开始。
后续历史由各自选择和拥堵共同产生，不应强制两组日后经历完全相同。输出理由只是辅助记录，不代表已证实人类式习惯。
ReasonMaxChars 控制理由长度，RepairAttempts 沿用旧名称，实际指总输出尝试上限3，即最多2次输出修复。
MaxRequests 以 client.complete 调用次数计，包含输出修复；HTTP内部短暂错误重试仍可能重复发送请求，不能把此预算当成精确计费上限。

固定情境回放从同一份 decision-contexts 或 context-samples 读取事实，不更新经验，也不运行交通仿真。
编辑 replay-models.example.json 的模型名、地址和 apiKeyEnv；不要在文件中存 API key。
建议每个服务显式设置其支持的采样参数，并记录不支持的参数，不能只凭统一参数名断言采样过程完全可比。

```powershell
.\scripts\replay_morning_choices.ps1 -InputFile '你的实验目录/morning/decision-contexts.jsonl.gz' -ModelsFile '.\scenarios\SiouxFalls-public\replay-models.example.json' -Output 'output/replay/models-v1' -Orders 'original,reverse,shuffle' -OrderSeed 4711 -Limit 100 -Repetitions 3
```

只检查回放流程、不调用API：同一命令另加 -Mock。Mock 永远选展示的第一条，不能用于模型能力结论。
回放输出包含 source-contexts、results、comparisons 和各 model/order/repeat 的原始提示、尝试与有效选择。
comparisons 标注同一展示顺序和重复次序下的模型分歧，以及同模型跨顺序的选择变化。
有随机采样时，顺序间变化不全是展示顺序的因果作用；应采用多次重复、统一支持的采样控制并报告失败/有效样本数。
闭环可用 CandidateOrder=original/reverse/shuffle 和 CandidateOrderSeed；只改变展示，候选ID和可执行线路保持不变。

本地全背景验证使用本机HTTP测试接口、完整84110人、3受控人、7天，不调用收费模型。
实验结束后可运行 `python scripts/analyze_morning_choices.py --morning "你的实验目录/morning"`，
统计前次迟到/未完成/中止后，以及费用信息变化、耗时预测变化后换路的比例、每日换路、展示位置、修复/用量/耗时。
这些是描述性关联，分组可以重叠，不能据此直接证明因果影响或人类式习惯；未出现的变化情境不虚构结果。
费用估算仍使用原场景规则；这一版未引入新的动态收费政策，未来可在固定回放输入中单独设计收费变化对照。
开发者可显式用 Maven 的 morning.fullTrafficValidation=true 启用 MorningFullTrafficValidationTest；普通测试默认不跑这项大场景验证。
完整事实记录使用无损紧凑存储，但内存和磁盘占用仍随人数×天数增长。不要为了降低验证成本裁掉交通背景；长实验应检查 Heap 和磁盘空间。

旧“四次原生随机路由”版本在 2026-10-02 的 100pct、seed4711 全量冷启动预检查结果：58450 个早高峰 trip 中，
55097 个只有 1 条不同路线、3348 个有 2 条、5 个有 3 条，没有获得 4 条的 trip。
该结果仅是候选准备检查（simulationRun=false），不是交通实验结果。
这些是历史生成器找到的路线数量，不是每个 OD 实际可选路线总数，也不适用于本次 BFS-LE 版本。
当前已改为上述文献 BFS-LE；新版本全量数量分布与资源限制情况以新 manifest 为准。
不需要先启动原生预热才能生成候选；现在可通过 hybrid 的 llm policy 接入真实 API。

2026-10-03 的旧“找到四条即停止、默认无限制”BFS-LE 版本 100pct、seed4711 预检查：84110 人、58450 个早高峰 trip，
涉及 6178 个不同 OD。5670 个 OD 获得四条候选（对应 51362 个 trip，87.87%），
508 个 OD 在当前无循环路线定义下只有一条（对应 7088 个 trip）；无 OD 达到资源上限。
508 个单候选 OD 中，24 个为同 link、40 个为相邻 link、444 个为其他单一路径。
备选 c1–c3 的自由流时间/c0 比按 OD-路线样本统计，中位数约 1.523、P95 约 3.685，最大约 8.159；
与 c0 的重叠率中位数约 0.448、P95 约 0.787。
这表明“找到四条”不等于四条均为同样合理的通勤备选；正式实验前应检查 candidate-quality，
若设置绕行/重叠阈值，需重新预检查、报告阈值与数量变化，并为所有决策组复用同一候选文件。
这些数字是候选质量诊断，不是全量交通仿真或 LLM 效果。
另已完成 100 人两天程序冒烟测试；其中 71 个早高峰 trip 两天均完成，无 API 调用，
不可用其低负荷路况评价全量拥堵。
