# 固定收费实验

`-FixedTollPolicy` 从 JSON 加载固定收费规则，与 `-Manager` 互斥。两者都不指定时为零收费基线。原生 ReRoute、ChangeExpBeta 继续运行，实际收费进入路由成本与执行后的评分。

项目提供 `56_8`、`75_1`、`48_1`、`51_1` 的单路段政策示例；金额为场景货币单位，并非人民币。这些文件是实验候选，不能视为已经验证的最优政策。`75_1`、`48_1`、`51_1` 各有 1 与 0.5 单位示例，一次实验加载一个文件。

在项目根目录打包后运行：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents 0 -Days 200 -Demand 100 -SampleRate 0.1 -InnovationStopDay 2900 -Seed 9261 -FixedTollPolicy '.\scenarios\SiouxFalls-public\fixed-tolls-75_1-1unit.json' -FixedTollFreeDays 20
```

默认前 20 个模拟日不收费，从第 21 天起重复相同政策。`-FixedTollFreeDays 0` 从第一天开始；`-ValidateOnly` 只校验并导出准备文件。零 LLM 旅客且无 manager 时不调用模型接口。

JSON 顶层为 `rules` 数组。每条规则含 `link_id`、`start`、`end`、`amount`；时间为秒，区间左闭右开。路段必须允许 car，时间须落在 QSim 时段内，金额为非负有限数，同一路段的时窗不得重叠。多个收费路段按入口累加，起点 partial link 的实际进入也会计费。

政策在仿真开始前读取并校验，运行期间修改文件不改变已生效政策。`morning/fixed-toll-policy.json` 保存规范化规则；manifest 保存输入路径、SHA-256、预热期、首次生效 iteration 和费用函数 fingerprint。

比较时复用人口、容量、采样率、随机种子及其他参数，每次只改变收费位置或金额，并保留对应的无收费对照。个人实验筛选报告与旧代码快照不在开源目录中。
