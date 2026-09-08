# AgenticMobility v0.2 详细使用指南

适用版本：v0.2 研究预览版。本文从新机器安装开始，介绍 Sioux Falls 出行者实验、无收费基线、候选路段筛选、单 Manager 拥堵收费、结果检查和合作者复现。示例主要使用 Windows PowerShell，并在末尾给出 Linux/macOS 差异。

本文中的命令供你按需执行，不代表项目已经完成正式政策实验。金额、时间窗和算法阈值都需要研究者校准。

## 阅读顺序

1. 初次运行：第 1–4 节。
2. 准备收费实验：第 5–8 节。
3. 接入真实 LLM：第 9 节。
4. 解释结果、复现和排错：第 10–14 节。

相关文档：[Manager 技术说明](manager.md)、[工程验证记录](manager-validation.md)、[场景输入要求](../scenarios/sioux-falls/README.md)、[架构](architecture.md)。

## 1. 先理解项目的边界

系统包含三层：MATSim 负责寻路、交通仿真和评分；出行者 Agent 决定交通方式和出发时刻调整；可选 Manager 在研究者授权范围内制定收费表。

| 对象 | 本版可以做什么 | 本版不能做什么 |
| --- | --- | --- |
| 出行者 | 在允许的交通方式和出发时间偏移内决策，利用历史体验和行动统计 | 自由改写活动、目的地和路网 |
| Manager | 在既定候选有向路段、时间格、价格档位中选择；0 档即不收费 | 自选新路段、扩大收费时间权限、改容量、区域封锁或自动实现最优收费 |
| MATSim | 模拟交通，按实际经过产生扣款，计算评分，按含收费成本寻路 | 自动证明政策有效、公平或达到社会最优 |

第一版只有一个 Manager，没有满意度、意见征集、投票、出行者—Manager 对话或多管理者协商。但 Manager 会读取客观仿真结果并周期性调价；不是完全不读取仿真结果的一次性静态决策器。

三个容易误解的地方：

- `mock` 是不调用远程模型的工程测试 provider，不是真实 LLM，也不是“关闭所有出行者 Agent”。
- 只关闭 Manager 得到的是“相同出行者系统下的无收费对照”，不是自动得到纯传统 MATSim 对照。目前应用不支持用 `LLM_AGENT_COUNT=0` 关闭全部受控出行者。
- 一轮迭代是执行—评分—调整周期，不自动等同于现实中的一个自然日。

## 2. 安装与获取项目

### 2.1 软件条件

- Git：下载项目和同步代码。
- JDK 25：需要完整 JDK，不只是旧版本 Java 运行时。
- Maven Wrapper 已在仓库中，不要求另外安装 Maven。
- Python 3.12 是本文分析工具的验证环境；Python 不参与 Java 主程序运行。
- Sioux Falls 的本地输入数据，见下一节。

检查命令：

```powershell
git --version
java -version
javac -version
python --version
```

`java` 与 `javac` 应均指向 JDK 25。如果版本不一致，先检查 `Get-Command java`、`Get-Command javac` 和 `JAVA_HOME`。内存需求随场景和输出设置变化；先用小规模受控 Agent 数量做检查，不要把示例 JVM 堆大小视为最低硬件标准。

### 2.2 克隆或进入现有文件夹

新机器：

```powershell
git clone https://github.com/Jiaheng-Xiao/AgenticMobility.git
Set-Location AgenticMobility
git status
```

若仓库为私有，合作者需要先获得访问权限并完成 GitHub 身份验证。不要把令牌嵌进 clone URL 或发送给合作者。

在本次开发机器上，可直接进入已有副本：

```powershell
Set-Location 'D:\Matsim\github_agenticMobility_v0.2'
```

后文除特别说明外，都从仓库根目录执行。不要对有未保存修改的工作副本直接覆盖文件；同步前先 `git status`，工作区干净时使用 `git pull --ff-only`。

### 2.3 Python 分析环境（可选，但筛选候选时需要）

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r analysis/requirements.txt
.\.venv\Scripts\python.exe -m unittest discover -s analysis/tests -p test_rank_toll_candidates.py
```

本文直接使用虚拟环境中的 Python，不要求运行环境激活脚本。依赖包含 pandas、matplotlib 和 zstandard；MATSim 当前常见输出为 `.xml.zst`，读取这种文件需要 zstandard。不要将 `.venv` 提交到 Git。

## 3. 准备 Sioux Falls 场景

GitHub 仓库不包含场景原始数据。代码可编译不等于已具备完整运行输入。请从有权使用的来源准备与配置匹配的 enriched Sioux Falls 数据，再核对以下文件：

```text
scenarios/sioux-falls/input/
  Siouxfalls_network_PT.xml
  Siouxfalls_population.xml.gz
  Siouxfalls_facilities.xml.gz
  Siouxfalls_transitSchedule.xml
  Siouxfalls_vehicles.xml
```

从你的本地数据副本初始化：

```powershell
.\scenarios\sioux-falls\setup-input.ps1 `
  -SourceDirectory 'D:\你的数据位置\siouxfalls-2014'
```

该脚本会检查并复制必需文件，且会覆盖目标 input 中的同名文件。因此已有定制输入时，不要重复执行；先备份或改用独立副本。不要因为另一份场景也叫 Sioux Falls，就假定其中的路段 ID、需求、公交和本项目兼容。

数据目录被 Git 忽略。与合作者共享时需要另外提供合规的数据获取方式和版本说明，不要仅发代码链接后声称可以直接复现。

## 4. 编译并完成第一次安全检查

### 4.1 编译与测试

```powershell
.\mvnw.cmd package
if ($LASTEXITCODE -ne 0) { throw '构建失败，请先处理 Maven 错误。' }
Test-Path target/agentic-matsim-0.2.0-SNAPSHOT.jar
```

第一次构建需要联网下载 Maven 和依赖。`package` 会运行 Java 测试并生成可执行 JAR；已有依赖缓存时可使用 `-o` 离线模式。测试不要求原始 Sioux Falls 数据或真实模型密钥。修改 Java、提示词或 knowledge 资源后，需重新 package 才会进入 JAR。

### 4.2 明确关闭 Manager、使用 mock 出行者

```powershell
Remove-Item Env:MANAGER_CONFIG -ErrorAction SilentlyContinue
$env:AGENTIC_PROVIDER = 'mock'
$env:AGENTIC_CITY_ADAPTER = 'sioux-falls'
$env:LLM_AGENT_COUNT = '2'
$env:LLM_SAMPLE_SEED = '20260805'
$env:LLM_EXPLORATION_SEED = '20260806'
$env:GIT_COMMIT = git rev-parse HEAD

$runOutput = 'output/smoke-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
if (Test-Path -LiteralPath $runOutput) { throw '输出目录已存在，请换一个名称。' }
java -Xmx4g -jar target/agentic-matsim-0.2.0-SNAPSHOT.jar `
  --config scenarios/sioux-falls/config.xml `
  --iterations 0 --output $runOutput
if ($LASTEXITCODE -ne 0) { throw 'MATSim 运行失败，请检查日志。' }
```

`--iterations 0` 表示执行编号为 0 的一轮，而不是不运行。`LLM_AGENT_COUNT=2` 只减少由本模块控制的出行者数，其他人口仍参加 MATSim，因此这不是把整个场景抽样为 2 人。

重要安全提示：当前应用会删除并重建指定输出目录。每次都使用新的输出路径，并保留上面的存在性检查。不要将已有实验目录、仓库根目录或其他重要文件夹传给 `--output`。

### 4.3 验收第一次运行

输出目录应包含：

- `run-manifest.json`：版本、provider、样本数、随机种子，`manager_mode` 应为 `off`。
- `llm-agent-selection.csv`：被选中的受控出行者。
- `llm-decisions.csv` 和 `llm-experiences.csv`：决策与实际结果。
- `llm-action-memory.csv`：行动统计。只有一轮且处于记忆预热期时，统计可能为空，这是正常的。
- MATSim 标准日志及结果文件；Manager 关闭时不应出现本次运行生成的收费文件。

没有远程模型调用不意味着所有输入下载也不需要网络；构建与场景准备属于独立步骤。

## 5. 建立用于候选筛选的无收费基线

### 5.1 先确定对照设计

候选集合应来自独立的无收费基线，不从收费实验的表现倒推选择。研究对照中应固定需求、网络、评分、受控样本、出行者 provider、种子和行动权限，只改变 Manager 方法或是否收费。

下面演示使用 mock、1% 受控样本进行无收费运行。若研究目标是真实 LLM 出行者，需在完成第 9 节配置后，用相应出行者设置重新建立匹配基线；不要把 mock 基线与 LLM 收费组之间的全部差异解释为收费作用。

```powershell
Remove-Item Env:MANAGER_CONFIG -ErrorAction SilentlyContinue
Remove-Item Env:LLM_AGENT_COUNT -ErrorAction SilentlyContinue
$env:AGENTIC_PROVIDER = 'mock'
$env:LLM_AGENT_SHARE = '0.01'
$env:LLM_SAMPLE_SEED = '20260805'
$env:LLM_EXPLORATION_SEED = '20260806'
$env:GIT_COMMIT = git rev-parse HEAD

$baselineOutput = 'output/baseline-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
if (Test-Path -LiteralPath $baselineOutput) { throw '输出目录已存在。' }
java -Xmx4g -jar target/agentic-matsim-0.2.0-SNAPSHOT.jar `
  --config scenarios/sioux-falls/config.xml `
  --iterations 100 --output $baselineOutput
if ($LASTEXITCODE -ne 0) { throw '基线运行失败。' }
```

`LLM_AGENT_COUNT` 优先于 `LLM_AGENT_SHARE`，所以从固定人数切换到比例时必须清除 COUNT。编号 0–100 共 101 轮；101 轮只是已有配置，不保证达到稳定状态。请检查评分、旅行时间、方式份额和未完成出行等趋势。

### 5.2 选择基线观测窗口

默认配置 `writeEventsInterval=100`，不是每轮都写事件。如果希望比较最后若干轮，应在运行前设置更密的事件输出，并评估磁盘开销。可在同一场景目录另存 `baseline.local.xml`，只修改需要的输出设置；同目录可保留 `input/...` 相对路径。该本地文件已列入忽略规则。

查看真实存在的事件文件，不要凭示例猜扩展名：

```powershell
Get-ChildItem -Path "$baselineOutput/ITERS" -Recurse -File -Filter '*.events.xml*' |
  Select-Object FullName
```

默认可能是 `.xml.zst`，也支持 `.xml.gz` 或未压缩 `.xml`。不要把同一迭代的不同压缩副本同时传给排序程序；程序只能直接检查重复路径，无法识别所有内容重复。

## 6. 生成候选排序并人工确认

假设你仍在上述 PowerShell 会话，`$baselineOutput` 指向刚完成的基线：

```powershell
$rankingOutput = 'output/candidate-review-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '/ranking.csv'
.\.venv\Scripts\python.exe analysis/rank_toll_candidates.py `
  --network scenarios/sioux-falls/input/Siouxfalls_network_PT.xml `
  --events "$baselineOutput/ITERS/it.100/100.events.xml.zst" `
  --start 21600 --end 36000 --bin-seconds 1800 `
  --output $rankingOutput
if ($LASTEXITCODE -ne 0) { throw '候选排序失败。' }
Import-Csv $rankingOutput | Select-Object -First 20
```

若使用其他事件文件，请替换 `--events`；它可接多个文件，以空格分隔。上例为 06:00–10:00，每格 30 分钟，共 8 格。

| 字段 | 含义 |
| --- | --- |
| `link_id` | 当前网络的有向路段 ID，后续配置必须原样使用 |
| `bin` | 从 0 开始的时间格索引 |
| `start_seconds/end_seconds` | 对应格的 `[开始,结束)` |
| `traversals` | 该格完整 car 路段通行次数，多文件时跨文件累计 |
| `total_delay_seconds` | 完整通行的正延误之和；默认按此降序 |
| `mean_delay_seconds` | 完整通行平均延误，不是边际外部成本 |

并非排名第一就一定适合收费。至少核查通行量、平均延误、上游排队、绕行条件、道路方向和活动起终点分布。低样本、高平均延误的路段应谨慎解释；高总延误可能主要源自流量大。

排序脚本不自动填写候选配置。将你的最终选择和理由保存成候选选择记录，至少包括基线 commit、事件来源、观测时窗、筛选口径、最终 ID、排除理由、确认日期。保留自动生成的 `.provenance.json`。数据来源或网络版本变更后，应重新核对 ID；不要沿用其他 Sioux Falls 版本的编号。

指标只覆盖完整 `LinkEnter → LinkLeave` 通行，不包含全部未完成出行和端点损失。完整口径与限制见 [Manager 技术说明](manager.md)。

## 7. 创建正式 Manager 配置

### 7.1 复制模板，不要直接覆盖公共示例

```powershell
$managerConfig = 'scenarios/sioux-falls/manager.local.json'
if (Test-Path -LiteralPath $managerConfig) { throw '本地 Manager 配置已存在，请先检查它。' }
Copy-Item scenarios/sioux-falls/manager.example.json $managerConfig
notepad $managerConfig
```

将 `candidateLinks: []` 填成你已确认的路段 ID 字符串数组。模板故意不提供正式候选；启用空候选配置会报错。不要把工程烟雾测试使用的 `10_2` 直接当成正式推荐。

### 7.2 参数说明

| 参数 | 模板值 | 解释与约束 |
| --- | --- | --- |
| `mode` | `off` | `off`、`shadow`、`active`，启动时读取 |
| `provider` | `rule` | `fixed`、`rule`、`llm` |
| `candidateLinks` | `[]` | 非空、不重复、当前网络允许 car 的路段；启用前手动确认 |
| `startTimeSeconds` | 21600 | 可收费时间起点：06:00 |
| `endTimeSeconds` | 36000 | 终点：10:00，结束时刻不包含在内 |
| `timeBinSeconds` | 1800 | 格长 30 分钟，时间范围需整除格长 |
| `priceLevels` | `[0,1.25,2.5,5]` | 非负、有限、严格递增，第一档必须为零；场景货币单位 |
| `warmupIterations` | 5 | 迭代 0–4 保持零收费并观测 |
| `updateInterval` | 5 | 此后每 5 轮形成下一周期政策 |
| `freezeLastIterations` | 5 | 最后 5 个执行轮次不再改价 |
| `minimumObservations` | 10 | 每路段/格在累计观测窗口的最低完整通行样本数 |
| `raiseDelaySeconds` | 120 | rule 平均延误严格高于此值时升档 |
| `lowerDelaySeconds` | 30 | rule 平均延误严格低于此值时降档；须小于升档阈值 |
| `fixedLevel` | 1 | fixed 使用的价格索引，示例中对应 1.25，不是直接金额 |
| `memoryCycles` | 10 | Manager 提示保留最近 10 个决策周期，完整审计不截断 |

JSON 不支持注释和尾随逗号；可以用以下命令检查语法，但网络 ID 和完整权限仍由 Java 启动校验：

```powershell
Get-Content -Raw $managerConfig | ConvertFrom-Json | Format-List
```

模板的价格/阈值是演示值，不是 Sioux Falls 已校准值，也不是美元收费建议。本版许可组合为候选路段与时间格的笛卡尔积；尚不支持逐路段不同的许可时间集合。

### 7.3 理解政策生效时点

预热 5、更新间隔 5 时，迭代 4 结束后发布策略，迭代 5 起执行；迭代 9 结束再决策，迭代 10 起执行。出行者在下一轮决策前获得新政策。

`--iterations 20` 共执行 21 轮；默认最后 5 轮即编号 16–20 冻结。预热、更新间隔与冻结期共同决定是否有调价机会。如果只跑 `--iterations 0`，不会产生需要在下一轮执行的收费政策。

`warmupIterations=0` 也不意味着启动前立即收费：第一份政策仍需等待首个 updateInterval 观测窗口。当前不支持从非零 firstIteration 恢复；Manager 的短预热也不等于加载已收敛的独立基线状态。

## 8. 按顺序运行 shadow、fixed、rule

### 8.1 先检查 shadow

在本地 JSON 中设 `mode: shadow`、`provider: rule`，保留已经确认的候选。然后运行：

```powershell
$env:MANAGER_CONFIG = 'scenarios/sioux-falls/manager.local.json'
$env:AGENTIC_PROVIDER = 'mock'
$env:GIT_COMMIT = git rev-parse HEAD
$runOutput = 'output/manager-shadow-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
if (Test-Path -LiteralPath $runOutput) { throw '输出目录已存在。' }
java -Xmx4g -jar target/agentic-matsim-0.2.0-SNAPSHOT.jar `
  --config scenarios/sioux-falls/config.xml `
  --iterations 20 --output $runOutput
if ($LASTEXITCODE -ne 0) { throw 'Manager 运行失败。' }
```

继续使用与基线相同的样本/种子设置；新 PowerShell 会话需要重新设置，不会自动继承以前会话的变量。

shadow 的预期现象：有 Manager 观测和建议；实际政策仍是零收费；`toll-payments.csv` 只有表头；出行者不接收未执行的价格建议。没有升档建议也可能正常：例如观测不足或平均延误低于阈值。

### 8.2 固定收费检查

将 JSON 改为 `mode: active`、`provider: fixed`。换一个全新输出目录，使用同样的运行命令。

fixed 将所有候选路段、所有配置时间格设为 `fixedLevel`，在第一次允许发布政策时生效。它用于检查执行链路和固定政策对照，不会根据拥堵自动挑选子集，也不受 rule/LLM 的最低观测和单档变化约束。

### 8.3 规则收费

改为 `mode: active`、`provider: rule`，再次使用新输出目录：数据足够且平均延误超过高阈值时升一档；低于低阈值时降一档；中间区间、零样本或样本不足时保持原档。

rule 与 llm 每格最多变化一个档位，但档位间的金额差不必相等。参数不同会改变行为，不能把示例阈值当成成熟优化算法。

### 8.4 关闭 Manager

```powershell
Remove-Item Env:MANAGER_CONFIG -ErrorAction SilentlyContinue
```

也可设 JSON 的 `mode=off`。改变环境变量或 JSON 不会热更新正在运行的任务；下次启动才生效。

## 9. 接入真实 LLM

### 9.1 两个 provider 相互独立

| 出行者设置 | Manager 设置 | 哪一部分会调用远程模型 |
| --- | --- | --- |
| `AGENTIC_PROVIDER=mock` | off / fixed / rule | 都不会 |
| `AGENTIC_PROVIDER=mock` | `provider=llm` | 仅 Manager |
| `AGENTIC_PROVIDER=openai-compatible` | off / fixed / rule | 仅出行者 |
| `AGENTIC_PROVIDER=openai-compatible` | `provider=llm` | 两者都会 |

shadow + llm 也会调用 API，只是不执行收费。不要因为“不收费”就认为“不调用远程模型”。本指南不提供累计 token 或成本估计。

### 9.2 只启用 LLM Manager

先在 JSON 中设置 `provider=llm`，建议先 shadow 验证返回格式。以下通过隐藏输入读取密钥，避免把真实密钥作为命令文字保存：

```powershell
$env:AGENTIC_PROVIDER = 'mock'
$env:MANAGER_CONFIG = 'scenarios/sioux-falls/manager.local.json'
$managerSecret = Read-Host '输入 Manager API key' -AsSecureString
$env:MANAGER_API_KEY = [System.Net.NetworkCredential]::new('', $managerSecret).Password
Remove-Variable managerSecret
$env:MANAGER_BASE_URL = 'https://api.deepseek.com'
$env:MANAGER_MODEL = 'deepseek-chat'
```

上述 URL 和模型名是代码中的默认示例，不是可用性或价格保证。用你的供应商当前提供的兼容端点和模型名；程序会在 base URL 后追加 `/chat/completions`，不要重复填写这个后缀。有些供应商要求 base URL 带 `/v1`，应按其接口设置。

之后执行第 8 节的 Java 命令并使用新的输出目录。只有把 `mode` 改成 active，经过校验的建议才会执行。Manager 连接变量未设置时会回退到对应的 `AGENTIC_API_KEY/BASE_URL/MODEL`；建议显式设置以免误用出行者模型账户。

当前 Manager 使用一次 HTTP 请求、固定 60 秒请求超时、JSON 对象响应格式；不保证兼容所有自称 OpenAI-compatible 的接口。API 错误、非法结构、越界或证据不足的改价会 KEEP，详见决策日志。

### 9.3 启用 LLM 出行者

```powershell
$env:AGENTIC_PROVIDER = 'openai-compatible'
$travelerSecret = Read-Host '输入出行者 API key' -AsSecureString
$env:AGENTIC_API_KEY = [System.Net.NetworkCredential]::new('', $travelerSecret).Password
Remove-Variable travelerSecret
$env:AGENTIC_BASE_URL = 'https://api.deepseek.com'
$env:AGENTIC_MODEL = 'deepseek-chat'
$env:LLM_AGENT_COUNT = '2'
$env:LLM_MAX_CONCURRENCY = '2'
```

先验证少量受控出行者和短轮次。正式增加比例前，清除 `LLM_AGENT_COUNT` 并设置 `LLM_AGENT_SHARE`。不要直接使用默认 1% 与 101 轮就假定服务配额、运行时间和行为均已验证。

`.env.example` 只是变量清单；程序不自动读取 `.env` 文件。密钥最终在当前进程环境中供 HTTP 认证使用，因此不要导出完整环境变量、共享带密钥的终端记录或将密钥写入配置。

## 10. 怎么检查结果

### 10.1 先核对实验身份

检查 `run-manifest.json` 与 `manager-manifest.json`：provider、模型、样本、种子、模式和生效配置是否符合计划。输出目录里保存的是实际使用的 Manager 设置，不要只看后来已被你修改过的本地 JSON。

### 10.2 主要文件

| 文件 | 主要用途 |
| --- | --- |
| `llm-decisions.csv` | 区分提出的行动、最终行动、约束/回退与执行情况 |
| `llm-experiences.csv` | 实际交通方式、时间、距离和分数；检查请求方式与执行方式是否一致 |
| `llm-action-memory.csv` | 当前政策制度下的行动统计，不等于所有历史原始体验 |
| `llm-action-memory-policy-N.csv` | 收费制度变化前的行动统计存档 |
| `manager-scene.json` | 程序实际从本次路网提取的候选静态信息 |
| `manager-observations.jsonl` | 每轮的累计窗口观测与实际执行政策 |
| `manager-decisions.jsonl` | 旧政策对应的观测、建议、校验、KEEP 与最终政策 |
| `toll-policies.jsonl` | 价格索引表及生效迭代，仅记录初始和实际变化 |
| `toll-payments.csv` | 实际缴费，每行金额为正的支出记录 |
| `manager-memory.json` | 近期决策周期，不代替完整 JSONL 审计 |

JSONL 是“一行一个 JSON 对象”。可这样快速检查，不需要 Excel：

```powershell
Get-Content "$runOutput/manager-decisions.jsonl" |
  ForEach-Object { $_ | ConvertFrom-Json } |
  Select-Object target_iteration, validated, fallback, error_type

Import-Csv "$runOutput/toll-payments.csv" |
  Group-Object iteration |
  Select-Object Name, Count

Import-Csv "$runOutput/toll-payments.csv" |
  Measure-Object -Property amount -Sum
```

空缴费文件只含表头时，汇总可能显示空值。先检查是 shadow、尚未生效、政策全零、无人经过，还是错误配置；不要直接判断模块失效。

### 10.3 如何解释收费与记忆

实际收费通过负的 `PersonMoneyEvent` 进入 MATSim 分数；car 路径成本也包含收费。只有符合条件的 car 驾驶者收费，公交车队排除。起点上路不收起点路段费；实际 LinkEnter 到终点路段仍可能收费；同一辆车再次经过收费路段可再次计费，没有每日封顶。

收费政策改变后，出行者旧行动统计存档并重置分数比较，近期历史保留政策标记；这样不会直接把不同收费制度的得分当成同一环境下的收益。普通 MATSim 出行者仍使用原有计划学习机制，需留足适应期。

`manager-observations.jsonl` 中的相邻记录可能覆盖重叠的累计观测窗口，不能逐行求和。完成 car leg 的总时长不包含未完成行程的全部损失；必须同时检查未完成量。收入不是新增社会福利，旅行时间下降也不独立证明公平或最优。

### 10.4 出行者过程图（可选）

```powershell
.\.venv\Scripts\python.exe analysis/plot_decision_process.py `
  --output-dir $runOutput --figure-dir "$runOutput/figures-travelers"
```

这个脚本面向出行者过程，不是完整 Manager 政策评价面板。仓库原有 baseline 对比脚本有旧实验目录默认值，使用前先查看 `--help` 并显式指定目录；不能把旧图表脚本视为已完成收费福利评估。

## 11. 常用出行者配置速查

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `AGENTIC_PROVIDER` | mock（全新默认环境） | 工程 mock 或远程 openai-compatible；建议每次显式设置 |
| `LLM_AGENT_COUNT` | 未设置 | 指定受控人数，至少 1，优先于 share |
| `LLM_AGENT_SHARE` | 0.01 | COUNT 未设置时使用，须大于 0 且不超过 1 |
| `LLM_SAMPLE_SEED` | 20260805 | 分层抽样种子 |
| `LLM_MAX_CONCURRENCY` | 5 | 出行者并发调用数量，不是 Manager 数量 |
| `LLM_ALLOWED_DEPARTURE_SHIFTS` | -30,-15,0,15,30 | 相对该人基准出发时刻的分钟偏移，不是每天继续累加 |
| `LLM_EXPLORATION_RATE_START/END` | 0.20 / 0.02 | 出行者探索率端点 |
| `LLM_EXPLORATION_SEED` | 20260806 | 出行者探索随机种子 |
| `LLM_MEMORY_WARMUP_ITERATIONS` | 3 | 出行者长期统计预热，与 Manager 预热独立 |
| `LLM_ROLLBACK_AFTER_DECLINES` | 3 | 触发回退的连续退步次数门槛 |
| `LLM_ROLLBACK_SCORE_TOLERANCE` | 0.05 | 评分退步容差 |
| `LLM_ROLLBACK_COOLDOWN_ITERATIONS` | 5 | 回退冷却轮数 |
| `LLM_MODE_FAILURE_THRESHOLD` | 2 | 连续执行方式不匹配时的屏蔽门槛 |
| `DEEPSEEK_TIMEOUT_SECONDS` | 45 | 历史命名的出行者 HTTP 超时变量，不控制 Manager 超时 |
| `GIT_COMMIT` | unknown | 写入运行 manifest 的代码提交号，运行前显式设置 |

项目保留若干 `DEEPSEEK_*` 兼容变量；旧终端环境可能影响默认 provider 或连接设置。遇到意外行为时，优先新建终端并显式设置本指南中的 `AGENTIC_*` 字段，不要打印全部环境变量排查。

## 12. 合作者复现与实验归档

GitHub 代码上传不等于论文结果已经可复现。每个正式实验至少保存：

- Git commit，以及未提交修改说明；最好从干净提交开始。
- 本次网络、人口、公交、设施、评分/QSim 配置的版本和校验值。
- 出行者设置、样本选择文件、所有种子和实际使用模型。
- 无收费基线来源、候选排序、人工确认记录及固定候选集合。
- Manager 实际配置、版本化 prompt/knowledge 和完整审计输出。
- 预热/评价窗口、重复试验种子、未完成出行处理规则。

可给输入文件生成校验值，但不要把密钥加入任何归档：

```powershell
Get-FileHash scenarios/sioux-falls/input/Siouxfalls_network_PT.xml -Algorithm SHA256
git rev-parse HEAD
git status --short
```

比较 no-toll/fixed/rule/LLM 时，保持同一候选集、价格权限、需求、样本和种子设计。评价时排除或单独报告预热、保留足够政策适应时间，并考虑多随机种子。当前普通出行者策略和最终冻结长度是否足够收敛，需要你用结果检验。

仓库默认忽略 `input/`、`output/`、`target/`、`.venv*/`、`.env*`（示例除外）、`manager.local.json` 和场景 `*.local.xml`。给合作者分享正式无密钥实验配置时，使用经过审查的独立配置文件并明确提交；不要以为本地被忽略文件会随 `git push` 上传。

保留仓库现有许可证和引用信息；外部场景和需求数据另有条款。本文不表示其再分发许可已确认。仓库若为私有，需由拥有者授予合作者访问权限，分享链接本身不会自动授权。

## 13. 常见问题

| 现象 | 检查与处理 |
| --- | --- |
| Java 不支持 release 25 / class version 不匹配 | 核对 java、javac 和 JAVA_HOME 都指向 JDK 25，重开终端后再构建 |
| Maven 首次下载失败 | 检查网络、代理和依赖仓库；没有完整缓存时不要加 `-o` |
| 找不到场景文件 | 核对第 3 节五个精确文件名、配置相对路径；不要把另一版本数据简单改名替代 |
| 找不到 v0.2 JAR | 从根目录运行 package 并确认成功；旧 v0.1 JAR 不含 Manager |
| 启用后提示设置 candidateLinks | 模板候选为空，必须先人工确认和填写，不要为消除报错随意填一个路段 |
| Candidate is not a car link | ID 不存在、方向不对、网络版本不一致，或该路段不允许 car |
| 有建议但没有收费 | 检查 shadow、实际政策是否零、预热/冻结/剩余轮次、通行时段和是否真的有 car 经过 |
| rule 一直保持 | 检查完整通行数、平均延误和阈值；null/样本不足必须保持 |
| LLM 日志出现 KEEP | 检查 error_type、已有 proposal 和校验权限；API 失败时可能没有 proposal，不要直接放宽权限绕过校验 |
| 改了提示词没有变化 | 重新 package，确认运行新 JAR |
| 改了 JSON 运行中没变化 | 仅下次启动读取，当前不支持热更新 |
| 设 share 后人数没变化 | 清除优先级更高的 LLM_AGENT_COUNT |
| .zst 解析失败 | 在实际使用的 Python 环境安装 zstandard；检查文件是否完整 |
| baseline 排序拒绝收费事件 | 传入了含本模块非零收费的事件；换真正无收费基线，不删除收费事件伪装基线 |
| 输出目录里的旧结果消失 | 当前应用会重建同名输出目录；立即停止重复使用旧路径，依赖事前备份恢复 |
| 运行未结束或未完成行程很多 | 先查 MATSim 日志、QSim 时间上限和拥堵状态；不要只靠减少统计分母得出效果提升 |

如果远程调用失败，保留错误类别和经过脱敏的最小复现；不要贴 API key、Authorization header 或完整环境变量。

## 14. Linux/macOS 对应操作

Java、Python 依赖和 JSON 配置相同，主要区别是路径和环境变量语法：

```bash
bash ./mvnw package
python3 -m venv .venv
.venv/bin/python -m pip install -r analysis/requirements.txt
export AGENTIC_PROVIDER=mock
export LLM_AGENT_COUNT=2
unset MANAGER_CONFIG
export GIT_COMMIT="$(git rev-parse HEAD)"
run_output="output/smoke-$(date +%Y%m%d-%H%M%S)"
if [ -e "$run_output" ]; then echo 'Output exists; choose another path.'; else
  java -Xmx4g -jar target/agentic-matsim-0.2.0-SNAPSHOT.jar \
    --config scenarios/sioux-falls/config.xml --iterations 0 --output "$run_output"
fi
```

`setup-input.ps1` 是 Windows 初始化脚本；Linux/macOS 可自行将合法的数据副本放到第 3 节指定的 input 目录。路径区分大小写，文件名必须一致。Java 测试在本地 Windows 上通过；远端 CI 的实际状态请在仓库 Actions 中确认，不把配置了跨平台 CI 等同于所有平台已经通过。

## 15. 正式实验前的最小检查清单

- [ ] 本地 Java/Python 测试通过，首次 mock 运行正常。
- [ ] 已获得正确场景输入并记录版本，不将未经确认的数据提交到 Git。
- [ ] 无收费基线与政策组使用匹配的出行者设置，且基线观测窗口有充分适应依据。
- [ ] 候选路段已人工确认并固定，有选择说明，不使用临时烟雾测试候选。
- [ ] 时间格、金额单位、阈值、更新周期和冻结期已审查。
- [ ] shadow/fixed 验证后再运行真实 LLM；已确认哪部分会发起远程请求。
- [ ] 输出目录是新目录，密钥只在安全的本地环境中。
- [ ] 记录了实际生效政策、缴费、未完成出行及评价窗口，不将工程跑通等同于政策有效。
