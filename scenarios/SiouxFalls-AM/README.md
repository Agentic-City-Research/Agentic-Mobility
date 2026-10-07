# Sioux Falls 早高峰单程场景

从原始规模的 `Siouxfalls_population_100pct.xml.gz`（84,110 人）提取。
原始全天人口和原场景配置没有因本次提取而改变。此目录包含独立的人口、
配置、路网和设施文件，可整体复制到计算服务器。

## 两个版本

| 版本 | 配置 | 人口文件 | 基准出发时段 | 人数/汽车行程数 |
| --- | --- | --- | --- | ---: |
| 核心早高峰 | `config-1.xml` | `population-core.xml.gz` | [07:00, 09:00) | 58,450 |
| 含提前出发缓冲 | `config.xml` | `population-buffered.xml.gz` | [06:00, 09:00) | 59,771 |

核心版本包含 home → work 55,583 人和 home → secondary 2,867 人，
不是只筛选通勤者。缓冲版本额外保留 6:00–7:00 出发的 1,321 名通勤者。
两者中均每人恰好一个 car 行程，没有返程或后续活动链。

筛选依据是原始 selected plan 中起点活动的计划结束时间，7:00 包含、
9:00 不包含。这是“固定基准出发人群”的实验，不等于所有实际在
7:00–9:00 路网上行驶的车辆。缓冲版本用于检查提前出发车辆的影响，
但不能未经验证就认为已完整复现全天背景交通。

## 保留与改变

- 保留原 person ID、原个人属性、OD 坐标/设施、活动类型和基准出发时间。
- 终点活动不设置下一次出发时间，车辆到达后不再返程。
- 原终点活动的 start_time/end_time 另存为 `am_source_destination_*` 属性；
  它们只是原计划信息，不能自动视为真实工作开始时刻或迟到期限。
- 添加 `am_core_peak` 标识，缓冲人口中的核心人群为 true。
- 去除旧线路与旧行程时间，交由 MATSim 重新初始化汽车线路。
- 路网/设施不变，flow/storage capacity factor 均为 1.0。
- 配置只运行第 0 次迭代，使用 KeepLastSelected；不做传统日际方案学习。
- 关闭活动评分，避免单程终点停留至仿真结束产生全天活动效用偏差。
  保留汽车出行效用 -6 utils/hour。此值不是经校准的迟到/早到惩罚，
  当前场景没有新增个人日程、习惯或 LLM 控制逻辑。
- 仿真结束时刻保留原来的 30:00:00，让高峰出发车辆继续到达，
  不在 9:00 强行截断。分析应按自己的口径统计 7:00–9:00 路网状态，
  并单独检查高峰后排队消散与未完成行程。

将来若允许 LLM 改变出发时间，应明确：成员仍按基准时刻固定，
而实际出发可移出高峰；不要每轮重新筛选成员，否则不同天人群会变化。
缓冲版本中哪些车辆由 LLM 控制、哪些作为背景交通，也需要明确设定。

## 验证

### 按随机种子命名输出目录

原生入口 `org.matsim.other.RunMatsimWithoutApplication` 支持在配置的
`outputDirectory` 中使用 `${randomSeed}`，读取配置及命令行覆盖后，
会将占位符替换为 `global.randomSeed`。例如种子为 4711，模板为
`output/siouxfalls-${randomSeed}` 时，实际输出为 `output/siouxfalls-4711`。

在项目根目录运行完整 baseline：

```powershell
java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.other.RunMatsimWithoutApplication scenarios/SiouxFalls-AM/config-1.xml
```

该入口不再强制删除已有输出目录，而是遵守配置的 `overwriteFiles`。
建议保持 `failIfDirectoryExists`，同一目录重复运行会报错，不会删除旧结果。
直接调用 `org.matsim.core.controler.Controler` 或现有 LLM 入口不会进行
此次新增的变量替换。下方只读验证脚本也不是这个运行入口。

已使用当前项目的 MATSim 运行库检查两个版本：
核心 58,450 人、缓冲 59,771 人均成功加载，汽车 OD 全部连通且可生成路线。
另使用核心人群中按 ID 排序的前 300 人做单轮原生 MATSim 试跑：
汽车出发 300、到达 300、stuck 0。试跑不是完整人口拥堵实验，
不能据此判断全量场景的拥堵程度或完成率。

在项目根目录可以重新执行只读验证：

```powershell
java --class-path matsim-example-project-0.0.1-SNAPSHOT.jar scripts/ValidateMorningScenario.java scenarios/SiouxFalls-AM/config-1.xml
java --class-path matsim-example-project-0.0.1-SNAPSHOT.jar scripts/ValidateMorningScenario.java scenarios/SiouxFalls-AM/config.xml
```

第二个参数可指定一个全新的输出目录，用于额外运行 300 人试跑。
上述验证脚本不运行完整人口仿真。

**现有 agentic 入口仍要求 home–work–home 双程计划，不能直接用它实现
本场景的全部出行者 LLM 控制。** 需要后续适配单程计划、全体出行目的、
单程决策/反馈/指标与 LLM 调用预算；不要将提取成功等同于接入完成。

## 可复现提取

`scripts/extract_morning_scenario.py` 可重新提取到一个不存在的新目录，
支持 `--peak-start`、`--peak-end` 和 `--buffer-start`。例如使用 Python 3：

```powershell
python scripts/extract_morning_scenario.py --population "scenarios/Souix Falls 2014/Siouxfalls_population_100pct.xml.gz" --config "scenarios/Souix Falls 2014/Siouxfalls_config.xml" --output "scenarios/SiouxFalls-AM-new"
python -m unittest discover -s scripts -p test_extract_morning_scenario.py
```

详细人数、分时出发量、来源文件 SHA256 和实验限制见 `extraction-report.json`。
