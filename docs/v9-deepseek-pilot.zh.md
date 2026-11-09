# 本地 Java v9：三个先导实验

本文描述的记忆消融是历史专项设计：源轨迹显式使用每日反思和 3 天决策窗口，以检查 Day 4 的旧事实是否不再直接可见。它不代表主实验的默认设置；主实验当前默认五天反思和五天决策窗口。使用不同协议的旧结果不能直接混合比较。

实验入口 `org.matsim.agentic.V9ReasonablenessExperiments` 使用本项目的 Java `MorningPromptRenderer`、`MorningLlmPolicy`、`MorningMemoryUpdater` 和实际 MATSim。无 Python Agent 接口。默认 DeepSeek Chat Completions，模型 `deepseek-flash`，`thinking=disabled`、请求温度 0、JSON object、输出上限 1024；服务端仍可能非确定。模型别名及服务随时间变化，不能仅凭名称保证权重固定；与 Qwen 是不同实验条件。

## 实验内容

1. **B1：12 个合成场景**。通过 Java v9 原版渲染和输出契约生成选择，记录合法输出及事先指定的支配判断。未知收费、未完成行程、预测与事实等对自然语言理由的理解必须人工核查；合法不代表理解正确。这一阶段没有在 MATSim 执行所选动作，不报告实际执行一致率。
2. **次日四分支**。使用真实 MATSim 5 日源轨迹：在第 0 天对 36261_1/0 预先强制 c0、27574 秒，并设置 74_1 低容量和一名背景旅客的出发时间；这不是模型自主决定。核对出发、执行路线、完成与实际驾车时间超出原有自由流估计至少 600 秒。核验成立后，从同一个 Day 1 上下文对事实 H 和摘要 M 做 H+M+、H+M−、H−M+、H−M− 四次独立决策，使用原 Java 渲染器重新渲染。源情景的道路容量改变持续 5 天，不表示一次性的暴雨或者真实城市事件。
3. **第 5 个模拟日（day_index=4）**。使用同一条源轨迹的 Day 4 决策前上下文，保持近三日经历、行动池及其余条件，改变摘要是否可见。程序检查 day0 不在最近 3 天窗口，以及摘要由原源轨迹真实日终更新递推得到。保留不等于记住首日延误，须人工审阅。

Day 1、Day 4 分支均是离线决策重放，未把这些分支动作再次交给 MATSim 执行。程序禁止收费或已付费用旁路并核验同一日期的其他输入未变；这是受控分支，不代表人的实际选择或对政策的预测。因为是 Java 原生实现，Python SQLite 和 Python 记忆状态不参与；Java 上下文中的摘要文本和摘要日期在分支时一起保留或清空。

## 首次离线验证

需要 Java 25、项目依赖及 Maven wrapper。在 PowerShell：

```powershell
cd <项目根目录>
.\scripts\run_v9_pilot.ps1 -Mode mock -Experiment all -Output tmp/v9-mock-mycheck
```

选择一个不存在的输出目录。mock 是确定性测试模型，不访问 DeepSeek，其结果不可用作行为证据；但其中延误和执行检查来自实际 MATSim。离线仿真是 40 人诊断抽样、无容量缩放，不是城市规模试验。

## 使用 DeepSeek

密钥仅设置在当前 PowerShell 进程；不要写入代码或输出目录：

```powershell
$key = Read-Host 'DeepSeek API key' -AsSecureString
$env:AGENTIC_API_KEY = [System.Net.NetworkCredential]::new('', $key).Password
```

先单独运行 12 个场景：

```powershell
.\scripts\run_v9_pilot.ps1 -Mode api -Experiment b1 -Output experiments/v9-deepseek-b1-01
```

观察其日志；确认协议失败得到处理后再运行真实源经历及 6 个分支：

```powershell
.\scripts\run_v9_pilot.ps1 -Mode api -Experiment memory -Output experiments/v9-deepseek-memory-01
```

也可一次运行全部：

```powershell
.\scripts\run_v9_pilot.ps1 -Mode api -Experiment all -Output experiments/v9-deepseek-all-01
```

每次使用新输出目录；有错误时查看 `status.json`、`stages.jsonl`、MATSim 源目录 `morning/status.json` 及尝试记录。源 MATSim 的第 0 天强制动作不访问模型，后续正常使用 DeepSeek 决策及每天摘要更新。`MaxRequests` 默认 200，B1 与离线分支共享该上限；源 MATSim 有一个独立的同值上限。修复会增加调用，底层传输重试不一定计入此 client 调用上限。调用收费，实际用量查看各目录 `api-usage.json`，不能用请求上限当作费用上限。

要在同模型与参数下复用已完成的 API 源轨迹、只重新运行分支：

```powershell
.\scripts\run_v9_pilot.ps1 -Mode api -Experiment memory `
  -SourceRun experiments/v9-deepseek-memory-01/source-matsim `
  -Output experiments/v9-deepseek-memory-replay-01
```

不允许把 mock 源轨迹当成 API 源轨迹；脚本核对来源标识和模型参数。多次分支调用属于同一源经历的技术重复，不是新增独立旅客。

## 查阅结果

| 文件 | 解释 |
|---|---|
| `status.json` | 程序是否完成；`complete=true` 不等于模型行为合理 |
| `b1/cases.jsonl` | 12 个案例、输出与可直接检查的预期路线 |
| `b1/summary.json` | 最终合法率、首次合法率、案例重试率；执行一致率标为不可用 |
| `b1/attempts.jsonl` | 每次模型输出、提示、无效结果与修复 |
| `b1/fault-injection/attempts.jsonl` | 本地构造的非法 JSON、截断与重试耗尽工程检查，不是 API 的失败率 |
| `memory/source-verification.json` | 实际行程、强制动作、执行核验及延误阈值 |
| `source-matsim/morning/` | 真实仿真每日行程、决策上下文、反思、实际执行与源调用用量 |
| `memory/branches/branches.jsonl` | 六个分支的完整上下文、Java 渲染提示及选择 |
| `memory/branches/attempts.jsonl` | 六个分支的模型调用、错误及重试 |
| `memory/branches/comparisons.jsonl` | 三个预定摘要对照的物理路线变化和出发时刻差 |
| `memory/branches/audit-instructions.json` | 源事实及首日/Day 3 摘要，供人工核对事实漂移和首日事件保留 |

完整提示与模型输出会保存为研究日志，可能包含人口属性；请按本地研究数据管理。程序不会自动判断摘要事实错误率；只有对明确可核验的事实陈述人工核验后，才可报告。对同一来源的一次分支选择未变化不能证明没有使用记忆；相反也不能排除模型输出波动，建议相同条件重复并报告失败。DeepSeek 请求温度值会记录，但服务端行为以[官方接口说明](https://api-docs.deepseek.com/api/create-chat-completion/)为准。
