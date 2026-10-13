# 当前公共场景协议：Traveler 联合选择与 Manager Basic

2026-10-12。入口为 `MorningExperiment`，默认 `hybrid`，Manager 默认 `basic`，收费为全天固定金额。

## 固定协议

| 项目 | 当前默认与口径 |
| --- | --- |
| 目标人群 | 输入原始计划中目的地活动开始时间在 `[0,09:00)` 的合格单 car-leg 行程；全目的，人群在运行前固定 |
| Traveler 动作 | `routeId` + `departureSeconds` + 短 `reason`，一次联合选择；路线池最多四条 |
| 时间选项 | 相对原始计划出发时刻，提前 90 分钟至推迟 30 分钟，每 15 分钟一项，通常九项；不以昨日选择继续偏移 |
| 时间边界 | 不早于原始出发活动的开始时刻或 00:00，不到达/越过 QSim 结束时刻；保留原始出发选项；不因预计迟到删掉选项 |
| 日程 | 默认保留原生 TimeAllocationMutator 的全天时刻调整，再写入受控目标行程的选择；背景及返程保留原生时刻。前序活动和拥堵可导致实际出发更晚 |
| Manager 政策调用 | 前 20 个仿真日无收费，第 21 日首次政策调用，此后默认每 30 日；`--manager-update-interval` 可作为实验参数 |
| Manager 记忆调用 | 每个已完成仿真日日末调用，包括前 20 个免费日；仅更新记忆，不执行收费调整 |
| Basic 记忆 | 每次完整替换，最多 **1000 英文词**；Java 按字母/数字词段计数，单词内部连接符保留；不是 1000 字符或 token |
| Basic 规则 | 完整政策最多 **40 个收费 link**，每个 link 一条规则、一个全天金额；不预设金额、路段位置、调整步长或探索顺序 |
| Manager 收费时窗 | basic / controlled 均固定 `start=0`、`end=QSim endTime`，覆盖 `[0,QSim endTime)`；当前 30 小时场景为 `[0,108000)`，不可选择高峰时窗或分时价格 |
| Traveler 记忆 | 日末完整摘要最多 500 Unicode 字符，包含全部长期信息及收费印象；默认另提供最近三天事实经历 |

时间范围参数：`--departure-before-seconds 5400 --departure-after-seconds 1800 --departure-step-seconds 900`。
PowerShell 对应 `-DepartureBeforeSeconds 5400 -DepartureAfterSeconds 1800 -DepartureStepSeconds 900`。
默认时间网格锚定原始时刻，例如 07:07 出发者得到 05:37、05:52……07:37，并不强制舍入至整点的 :00/:15。

原生 `TimeAllocationMutator` 当前配置权重 0.1；权重按策略选择器归一化，不等于严格 10% 的执行概率。
变异范围沿用当前场景配置每次最多 3600 秒（60 分钟），默认围绕所选计划继续变异，累计变化可超过 60 分钟；不使用 LLM 的九项时间网格。
使用 `--no-native-time-mutation` / `-DisableNativeTimeMutation` 关闭后，恢复旧协议：移除原生时刻变异并每日恢复原始活动结束时刻，LLM 目标选择仍单独应用。
新增运行名 `tm0.1`，完整参数及策略停用时刻见 manifest；目标人群仍由原始日程筛选，不随实际到达时间变化。
`selectedDepartureSeconds` 同时记录原生与受控出行；`nativeDepartureTimeSwitches`、`nativeDeparturesShiftedFromOriginal`、`meanNativeSelectedDepartureShiftSeconds` 供核查原生变异。
manager 公共环境明确区分原生时刻变异与 LLM 联合选择，不把固定 OD 需求误报为固定出发时刻。

## Basic 的信息与判断边界

单一目标为降低全路网拥堵延误。简短 world model 解释容量、排队、路线/时刻响应、收费执行及测量含义。
输入为已执行仿真的全局聚合路网事实、实际政策及版本、历史效果、执行机会和 Manager 自己的记忆。
不提供个体画像、私人记忆、单人出行或支付记录；不提供按评分参数换算的收费金额指导。
决定收费、等待、调整、撤销和修正解释均由 Manager 作出。

Basic 不运行自动回滚、绩效阈值触发、冷却期、一走廊限制、不可绕行比例门槛、金额上限或涨价步长限制。
校验存在的 car 路段、有限非负金额、覆盖完整仿真日的时窗、每个 link 只出现一次、最多 40 条规则、精确 JSON 和动作含义。
`hold` 保持价格函数，`test/adjust` 改变价格函数，`remove` 实际降低/撤销且不能同时涨价。
政策输出经过有限修复仍不合法时保留旧政策。日末记忆调用独立校验；失败、截断或超时保留旧记忆并记录，下一次成功更新包含缺失日期汇总。
程序或请求预算失败仍可能中止实验，不能把未完成运行当作有效政策结果。

旧 v5 限制与自动保护只在显式 `--manager-profile controlled` / `-ManagerProfile controlled` 下用于其他实验条件。
两种 profile 的每日记忆均为 400–600 英文词目标、1000 词硬上限；controlled 的政策保护仍详见 `manager_v5.md`。
`--manager-max-*` 等旧行为限制在 Basic 不参与判断，PowerShell 默认也不传入它们。

全天收费不改变前 20 天免费或定期政策更新安排，不增加每日支付上限。同一天离开后重新进入收费 link 会再次支付；仅调整出发时刻不能避开同一路段的收费。收费窗口覆盖真实 QSim 结束时刻，即使它不是 15 分钟或整小时边界。固定收费文件和历史收费记录仍保留原时窗语义。
最后一个交通观测箱若超过 QSim 结束时刻，Manager 输入将其 end 裁剪到仿真结束，保留实际观测计数，避免漏算全天收费末尾的通行或触发旧分箱对齐错误；原始 link-hours 档案仍保留原分箱标签。
Manifest schemaVersion=23，`pricing.chargingMode=full_day` 与 `pricing.chargingWindow` 记录全天口径，Manager 模板为 `morning-pricing-v10-daily-memory`。

## Traveler 信息边界

知情者接收当前候选相关的公告；按每个候选时刻的自由流进入时间估计收费，实际支付可能不同。
不知情者没有当前公告、政策版本、当前报价或全网拥堵历史；当前费用保留未知，不能把未知当免费。
其已发生的收费路段、时刻、支付进入最近事实经历，之后可由自身摘要保留印象。
**不会从永久精确收费表检索后重新注入 LLM。** 精确收费档案仍供研究分析，原生其他行程的既有私人收费路由机制独立保留。
收费印象若被摘要遗忘，程序不会从档案自动恢复。固定画像、候选路线与输入日程不计入动态记忆预算。
两组均只获得静态自由流路线信息和自身最近结果；智能体之间不交换信息。

## 反馈和发布字段

- cohort：原始计划到达、出发及固定时间候选。
- decisions / choice-transitions：路线、原始和选择时刻、短理由及候选。
- executed-route-checks：选择是否写入活动结束时刻，实际出发、执行路线及延迟；允许有依据的实际晚于选择。
- experiences / trips：原始、选择、实际出发，实际到达、耗时、迟到、支付与收据；experiences 另关联实际政策 ID、版本和指纹。
- policy-applications：每一天实际政策 ID、版本、价格函数指纹；相同价格但新版本不能当作新干预。
- manager-memory-updates：完整记忆、词数、来源状态，明确记忆是否更新。
- policyFeedback.trackedChargingWindows：过去收费道路的同一时窗，在收费前、收费中、撤销后的实际观测；零流量也计入，撤销不删除观测。
- link-hours：历史文件名保留；Basic 的行实际上为 900 秒进入时间分箱，manifest 记录 `linkMeasurementBinSeconds`，以每行 start/end 为准。

全日路网延误用于 Manager 目标；固定早高峰/分析窗口是辅助口径，不能用移出窗口的交通冒充全日改善。
完成率与未完成行程用于核对测量结果。政策期变化是描述性反馈，不能代替同期无收费对照。
离线联调仅验证工程闭环，不证明模型自主适应、政策效果、均衡或现实行为有效性。

PowerShell 与两份 Slurm 脚本均支持当前时间候选参数；Qwen 3.5 脚本显式传入 Manager profile。
服务器运行前需上传本次构建的 JAR 和同步后的脚本；此修改不自动同步服务器或启动付费模型实验。
