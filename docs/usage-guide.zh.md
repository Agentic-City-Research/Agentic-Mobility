# AgenticMobility 详细操作指南（中文）

此文件保留原 README 的详细操作说明；英文项目入口见 [README](../README.md)。下文中的终端命令均从仓库根目录执行。


本项目在 MATSim 2026.0 上实现 LLM 出行者的路线与出发时间联合选择、个人经历与自写记忆，以及收费管理者实验。当前发行使用 Sioux Falls 全天活动链；旧的独立早高峰单程场景保存在源码历史中。

当前运行入口是 `org.matsim.agentic.MorningExperiment`。项目以 Java 源码、场景输入和脚本分发，运行前需要构建 JAR。


## 1. 下载与环境准备

其他使用者可以克隆仓库。以下命令使用当前仓库地址；私有仓库需要使用有权限的 GitHub 账号认证。

```powershell
git clone https://github.com/Agentic-City-Research/Agentic-Mobility.git
Set-Location Agentic-Mobility
```

| 环境 | 用途 |
| --- | --- |
| **JDK 25** | 编译及运行；本项目设置 `maven.compiler.release=25` |
| Git | 上传、克隆与更新源码 |
| Maven Wrapper | 项目自带，首次构建联网下载 Maven 和依赖；也可使用已有 Maven |
| PowerShell | 执行 Windows 运行和回放脚本 |
| Python 3 | 人口恢复与出行选择分析，脚本使用标准库 |
| 可访问的模型服务 | 仅真实 LLM 出行者或管理者实验需要；使用兼容的 Chat Completions 接口 |

先检查 Java：

```powershell
java -version
javac -version
```

两者应指向 JDK 25。模型可运行在另一台服务器；本项目的 MATSim 进程使用 CPU，远程模型服务的部署由使用者自行配置。

### Linux / HPC：切换到完整 JDK 25

出现 `No compiler is provided in this environment`，同时 `javac: command not found`，说明当前编译环境没有可用的 JDK 编译器。`java -version` 的 `1.8.0_*` 是 Java 8；其中 HotSpot 的 `build 25.*` 是虚拟机构建号，不能按它判断 Java 主版本。本项目需要完整 JDK 25。

若已经在个人目录安装了 JDK 25，先检查安装目录。例如：

```bash
ls -l "$HOME/opt/java25/bin/java" "$HOME/opt/java25/bin/javac"
```

确认两个文件存在后，在当前终端切换 Java；安装在其他位置时替换 `JAVA_HOME`：

```bash
export JAVA_HOME="$HOME/opt/java25"
export PATH="$JAVA_HOME/bin:$PATH"
hash -r

java -version
javac -version
mvn -version
```

三项检查均应显示 Java/JDK 25，且 Maven 的 Java home 指向同一安装目录。Maven 默认使用启动它的 Java 环境，因此只改变某个运行脚本中的 `JAVA_BIN` 不会改变 Maven 的编译环境。配置方式见 [Maven 安装说明](https://maven.apache.org/install.html)。

若集群通过 module 提供软件，可先执行 `module avail java`、`module avail jdk`，加载实际提供的 JDK 25 模块，再执行上述版本检查。

如果没有可用的集群模块，也可以将 [Temurin JDK 25](https://adoptium.net/temurin/releases/?version=25) 安装到自己的目录，无需管理员权限。下面示例适用于 `uname -m` 输出 `x86_64` 的 Linux 节点，安装目录应为空；ARM64 节点将 URL 中的 `x64` 改为 `aarch64`。

```bash
uname -m
mkdir -p "$HOME/opt/temurin-jdk25"
curl -fL --retry 3 \
  'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse' \
  -o "$HOME/opt/temurin-jdk25.tar.gz"
tar -xzf "$HOME/opt/temurin-jdk25.tar.gz" \
  -C "$HOME/opt/temurin-jdk25" --strip-components=1

export JAVA_HOME="$HOME/opt/temurin-jdk25"
export PATH="$JAVA_HOME/bin:$PATH"
hash -r
java -version
javac -version
```

下载地址和解压安装方式分别见 [Adoptium API 文档](https://adoptium.net/installation/ci-scripts) 与 [归档安装说明](https://adoptium.net/installation/archives)。无法从节点联网下载时，可从本机下载相应 Linux JDK 包后上传并解压。

回到包含 `pom.xml` 的项目根目录，确认 `mvn -version` 使用 Java 25 后重新构建：

```bash
mvn -B clean package
# 未安装独立 Maven 时使用项目 Wrapper：
# sh ./mvnw -version
# sh ./mvnw -B clean package
```

这些 export 只作用于当前 shell。提交 Slurm 作业时，也应在作业环境中设置相同的 `JAVA_HOME` 和 `PATH`；本项目 Slurm 模板还可显式设置 `export JAVA_BIN="$JAVA_HOME/bin/java"`，确保运行使用同一版本。

## 3. 构建运行包

所有命令均在项目根目录执行。

Windows：

```powershell
.\mvnw.cmd -B clean package
```

Linux/macOS：

```bash
sh ./mvnw -B clean package
```

如果已经安装 Maven，也可以执行 `mvn -B clean package`。构建成功会显示 `BUILD SUCCESS`，并在项目根目录生成：

```text
matsim-example-project-0.0.1-SNAPSHOT.jar
```

运行脚本使用这个根目录 JAR。第一次构建需要下载 MATSim、contrib 和其他依赖；源代码修改后重新打包，再启动新实验。

## 4. 先运行离线验证

### 100 人、2 天的程序冒烟测试

此命令运行交通仿真，不调用真实模型服务：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents 0 -Demand 100 -SamplePersons 100 -Days 2 -Seed 4711 -Output 'output/quickstart'
```

`-LlmAgents 0` 表示没有由 LLM/离线策略接管的旅客，背景人口采用原生 MATSim 行为。`-SamplePersons 100` 只用于检查流程，不同步缩放道路容量，因此这个结果不代表全量拥堵。

### 10% 人口的原生基线

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 30 -Seed 4711 -Output 'output/baseline-pct10'
```

`-SampleRate 0.1` 同时采样人口并缩放 flow/storage capacity。不要与 `-SamplePersons` 同时使用。`-Demand 100` 使用恢复的 84,110 人全天人口；采样后为 8,411 人。`-Demand 200` 使用公开数据谱系中的 168,220 人需求。

默认场景的 `flowCapacityFactor` 和 `storageCapacityFactor` 均为 `1.0`，使用路网的完整基础容量。设置 `-SampleRate k`（Java `--sample-rate k`）后，人口数量为 `floor(所选 Demand 的总人数 × k)`，流量容量系数为 `k`，存储容量系数为 `k^0.75`，均相对于原始路网容量。

| 采样率 k | 人口比例 | 流量容量系数 | 存储容量系数 |
|---|---|---|---|
| 1.0 | 100% | 1.0 | 1.0 |
| 0.25 | 25% | 0.25 | 约 0.353553 |
| 0.1 | 10% | 0.1 | 约 0.177828 |

完整人口实验可以省略 `-SampleRate` 和 `-SamplePersons`，此时默认场景的两项容量系数均为 `1.0`。正式比较需要保持人口、容量、随机种子和其他设置一致。

### 固定采样人口

使用独立的 `-PopulationSeed`（Java `--population-seed`，也支持 `--sample-seed` 别名）固定人口抽样；`-Seed` 继续控制仿真随机性：

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -LlmAgents 0 -Demand 100 -SampleRate 0.1 -PopulationSeed 20261008 -Seed 4711 -Days 30
```

PopulationSeed 默认固定为 `4711`，与仿真 Seed 独立。相同输入人口、Demand、采样率和 PopulationSeed 会选中同一批 person ID，即使更换仿真 Seed。人口只在启动时抽样一次，整个实验使用同一批人。相同输入人口和 PopulationSeed 下，提高采样率会保留较小样本中的人员。受控人员名单、默认画像和默认信息分组也使用 PopulationSeed；显式 ProfileSeed / InformationSeed 可分别覆盖。实际使用的采样种子写入 `morning/manifest.json` 的 `populationSeed` 和 `populationSampling.seed`。PowerShell 同时支持 `-SampleSeed` 别名。

## 5. 接入真实模型，运行 LLM 出行者

### 设置模型接口

在启动实验的同一个 PowerShell 窗口设置：

```powershell
$env:AGENTIC_API_KEY = "YOUR_MODEL_API_KEY"
$env:AGENTIC_MODEL = "YOUR_SERVED_MODEL_NAME"
$env:AGENTIC_BASE_URL = "http://127.0.0.1:8001/v1"
```

这里的地址是本机兼容服务的示例，要求服务已经启动；远程或云端模型应替换成对应地址、模型名和密钥。客户端会在 Base URL 后追加 `/chat/completions`；如果已填写完整的该接口 URL，则直接使用。

这些变量只在当前 PowerShell 会话生效。完整配置项见 [.env.example](../.env.example)，程序不会自动读取 `.env`。实际密钥放在环境变量或本地配置中，不写入源码和模型列表。

### 先验证输入，不调用模型

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy llm -LlmAgents 3 -Demand 100 -SampleRate 0.1 -Days 7 -Seed 4711 -ValidateOnly -Output 'output/llm-validation'
```

`-ValidateOnly` 准备人口、候选和协议记录，不运行交通仿真，也不发送模型请求。它可以检查场景与参数；模型连通性和输出格式仍需通过真实运行验证。

### 3 名 LLM 出行者、7 天

确认模型配置后执行：

```powershell
.\scripts\run_public_experiment.ps1 `
  -Mode hybrid -Policy llm -LlmAgents 3 `
  -Demand 100 -SampleRate 0.1 -Days 7 -Seed 4711 `
  -Concurrency 3 -Thinking omit `
  -MaxOutputTokens 1024 -ReasonMaxChars 120 -MemoryMaxOutputTokens 1024 -MaxRequests 200 `
  -Output 'output/llm-first-run'
```

LLM 旅客在每一天联合选择路线与出发时刻，按反思周期在仿真结束后调用模型更新私人摘要。候选路线最多四条，当前默认出发时间网格为原始计划时刻前 60 分钟至后 30 分钟、每 15 分钟一项。

`-Thinking omit` 不发送厂商专用 thinking 字段，不等于关闭模型自身的思考。其他格式或生成参数只在服务支持时显式设置。旅客决策与摘要会产生模型请求；`-MaxRequests` 限制 client 调用数量并包含输出修复，HTTP 内部重试可能增加实际发送次数。

该例保留采样后的全部交通背景，只选 3 人由 LLM 控制。确认提示、执行和摘要输出后，再扩大 `-LlmAgents`、天数或使用完整人口；`-LlmAgents all` 控制目标人群中的所有合格人员，并非自动控制全部全天行程。

## 6. 运行收费管理者或固定政策

### LLM 管理者，旅客保持原生行为

先按上一节配置模型环境变量，再执行：

```powershell
.\scripts\run_public_experiment.ps1 `
  -Mode hybrid -Policy mnl -LlmAgents 0 `
  -Manager -ManagerProfile basic -ManagerUpdateInterval 30 `
  -Demand 100 -SampleRate 0.1 -Days 30 -Seed 4711 `
  -Thinking omit -MaxRequests 100 `
  -Output 'output/manager-pct10'
```

管理者每天在仿真结束后调用模型更新记忆。前 20 个模拟日不收费，第 21 日首次政策决策，此后每 30 日更新收费；30 天示例包含一次政策更新机会，7 天实验仍会进行 7 次日末记忆调用。

当前默认管理者为 **Basic**：完整记忆最多 1000 英文词、政策最多 40 个收费 link，每个 link 全天使用一个固定金额。显式 `-ManagerProfile controlled` 启用旧监测/回退条件，收费仍为全天，详见 [manager_v5.md](../scripts/manager_v5.md)。当前信息边界和默认协议见 [manager_basic_joint.md](../scripts/manager_basic_joint.md)。

Manager 每个仿真日结束后更新管理记忆，包括前 20 个免费观察日；仅在固定政策更新日调整收费。政策调用输出 `rules` 和 `decision`，日末反思只输出 `memory`。两种 profile 的记忆均以 400–600 英文词为目标，硬上限 1000 词。失败时保留旧记忆，下次输入包含缺失期间的汇总。完整说明见 [每日 Manager 记忆协议](../scripts/manager_daily_memory.md)。

新增 `--manager-memory-max-output-tokens`（默认 2048；PowerShell 为 `-ManagerMemoryMaxOutputTokens`），与政策调用的 `--manager-max-output-tokens`（默认 8192）独立。默认 100 日 manager 实验至少需要 100 次反思加 3 次政策调用；LLM Traveler 的选择/反思及重试另计。前 20 天 manager 模式也需要可用 API 服务。

Manager 收费规则固定为 `start=0`、`end=QSim endTime`，每个 link 最多出现一次，金额仍按每次实际进入该 link 收取。当前公共场景 `endTime=30:00:00`，对应 `[0,108000)`；跨 24:00 的行程也适用同一金额。模型不能选择仅早高峰收费或分时价格，非法时窗经过有限修复仍不合格时保留旧政策。15 分钟/整小时分箱继续用于交通观测，不限制全天窗口的结束时刻。前 20 天免费及政策更新间隔沿用现有设置；固定收费 JSON 文件仍按文件指定的时窗执行。

要同时启用 LLM 旅客与管理者，可在上一节旅客命令中增加 `-Manager`，将天数设置为至少 21，并相应调整调用预算。

### 固定收费，无模型管理者

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 30 -Seed 4711 -FixedTollPolicy 'scenarios/SiouxFalls-public/fixed-tolls-75_1-1unit.json' -FixedTollFreeDays 20 -Output 'output/fixed-toll-pct10'
```

该例不调用模型，第 21 个模拟日开始执行固定政策。`-FixedTollPolicy` 与 `-Manager` 互斥。JSON 的金额为场景货币单位，示例政策用于实验对照；格式与规则见 [fixed-tolls.md](../scenarios/SiouxFalls-public/fixed-tolls.md)。

## 7. Linux、macOS 或直接调用 Java

PowerShell 脚本封装了参数、完整控制台日志和运行时 JAR 副本。直接运行 Java 时，下面的离线例子与 100 人程序冒烟测试对应：

```bash
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --policy mnl --llm-agents 0 \
  --demand 100 --sample-persons 100 --days 2 --seed 4711 \
  --output output/quickstart-java
```

真实模型运行前，在同一 shell 中设置环境变量：

```bash
export AGENTIC_API_KEY='YOUR_MODEL_API_KEY'
export AGENTIC_MODEL='YOUR_SERVED_MODEL_NAME'
export AGENTIC_BASE_URL='http://127.0.0.1:8001/v1'

java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --mode hybrid --policy llm --llm-agents 3 \
  --demand 100 --sample-rate 0.1 --days 7 --seed 4711 \
  --concurrency 3 --thinking omit \
  --max-output-tokens 1024 --reason-max-chars 120 --memory-max-output-tokens 1024 --max-requests 200 \
  --output output/llm-first-run-java
```

查看完整 Java 参数：

```bash
java -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --help
```

Slurm 模板为 [run_hpc_qwen.slurm](../scripts/run_hpc_qwen.slurm) 和 [agentic_morning_qwen35.slurm](../scripts/agentic_morning_qwen35.slurm)。按所在集群设置分区、项目目录、Java/Python 和已启动的模型服务；准备步骤见 [Slurm 说明](../scripts/agentic_morning_qwen35.md)。

## 8. 常用参数

| PowerShell 参数 | Java 参数 | 作用 |
| --- | --- | --- |
| `-Mode hybrid` | `--mode hybrid` | 当前主实验入口，保留原生全天背景 |
| `-Policy llm` | `--policy llm` | 旅客使用真实模型；其他可选值为 mnl、shortest、mock |
| `-LlmAgents 3` | `--llm-agents 3` | 合格目标人员中由所选策略接管的人数；支持 0/all |
| `-Demand 100` | `--demand 100` | 选择 100pct 原始人口；200 为双倍需求 |
| `-SampleRate 0.1` | `--sample-rate 0.1` | 人口按 k 采样，流量容量 k、存储容量 k^0.75 |
| `-PopulationSeed 20261008` | `--population-seed 20261008` | 固定人口采样种子，默认 4711；独立于仿真 Seed |
| `-SamplePersons 100` | `--sample-persons 100` | 程序诊断抽样，不缩放容量 |
| `-Days 30` | `--days 30` | 模拟天数，含 iteration 0 |
| `-Seed 4711` | `--seed 4711` | MATSim 随机种子；模型生成种子是独立参数 |
| `-Manager` | `--manager` | 启用 LLM 收费管理者 |
| `-FixedTollPolicy FILE` | `--fixed-toll-policy FILE` | 从 JSON 加载固定收费政策 |
| `-CandidateFile FILE` | `--candidate-file FILE` | 复用候选路线；所需 OD 缺失会报错 |
| `-ProfileFile FILE` | `--profile-file FILE` | 复用既有固定画像；缺失人员或冲突会报错 |
| `-HistoryWindow 5` | `--history-window 5` | 决策前近期经历窗口；默认随反思周期（默认 5 天） |
| `-Concurrency 3` | `--concurrency 3` | 模型请求并发上限 |
| `-MaxRequests 200` | `--max-requests 200` | 模型 client 调用预算，包含输出修复 |
| `-Output PATH` | `--output PATH` | 指定独立实验目录 |
| `-ValidateOnly` | `--validate-only` | 准备与验证输入，零仿真、零模型调用 |
| `-Heap 8g` | `java -Xmx8g` | Java 最大堆；全背景运行需按机器资源调整 |

本表只列常用项，详细参数及实验信息口径见 [全天场景说明](../scenarios/SiouxFalls-public/README.md)。

## 9. 输出在哪里，如何检查完成

示例命令指定了 `-Output`。省略时，程序自动使用 `output/<日期和参数>_seed<种子>`；重名追加 `-2`、`-3`，保留原有实验。PowerShell 完整控制台日志保存在 `output/_logs/public-*.log`，启动时显示路径。

核心记录位于每个实验目录的 `morning/`：

| 文件 | 内容 |
| --- | --- |
| `manifest.json` | 参数、输入及运行 JAR 的 SHA-256、人口名单哈希、各类种子、Java 环境、人口/容量及模型设置 |
| `metrics.jsonl.gz` | 每日 cohort 指标、全日汽车耗时、失败/未完成数量、入网前等待等 |
| `status.json` | 正常实验的 `complete`、已完成天数及失败信息；ValidateOnly 使用验证状态字段 |
| `progress.json` | 当前阶段与已完成天数；用于查看进展 |
| `api-usage.json` | 调用数、服务返回的 token 用量等 |
| `cohort.jsonl.gz`、`candidates.jsonl.gz` | 固定目标人群与候选路线 |
| `decisions.jsonl.gz`、`trips.jsonl.gz`、`experiences.jsonl.gz` | 选择、实际执行与个人经历 |
| `memory-updates.jsonl.gz`、`memory-final.jsonl.gz` | 日际私人摘要与最终摘要 |
| `manager-decisions.jsonl.gz`、`policy-applications.jsonl.gz` | 启用管理者时的决策及每日实际政策 |

以 `output/llm-first-run` 为例，检查状态并做选择分析：

```powershell
Get-Content 'output/llm-first-run/morning/status.json'
python scripts/analyze_morning_choices.py --morning 'output/llm-first-run/morning'
```

正常实验应检查 `status.json` 的 `complete=true`，并核对 `daysCompleted`。只看到 `progress.json` 里的天数不代表最终导出成功。日末完整事实与摘要存档也不代表支持交通仿真自动断点续跑。

### 新增评价指标

`metrics.jsonl.gz` 每行对应一个模拟日。`fullDayCarTravel` 覆盖全天所有计划汽车行程；其他下表字段统计固定目标 cohort 中已完成的汽车行程。

| 字段 | 含义 |
|---|---|
| `fullDayCarTravel.carPersonHours` | 全天汽车总出行人时，含进入道路交通前的等待；全部计划汽车行程完成时才给出完整值 |
| `fullDayCarTravel.completedCarPersonHours` | 已完成汽车行程的人时，存在失败时仍保留 |
| `fullDayCarTravel.abortedCarLegs` / `unfinishedCarLegs` / `notStartedCarLegs` | 中止、仿真结束时未完成、未开始的汽车行程数量 |
| `meanPreTrafficWaitSecondsCompleted` | 已完成 cohort 汽车行程的平均入网前等待秒数 |
| `slowestFivePercentMeanCarSeconds` | 已完成 cohort 汽车行程中，最慢 `ceil(数量×5%)` 条的平均耗时 |
| `meanSelectedRouteDistanceMetersCompleted` | 已完成 cohort 行程所选路线的平均距离，沿用现有自由流口径，排除起点 partial link |
| `meanTravelTimeIndexPositiveFreeFlow` | 实际车程 / 所选路线自由流耗时的均值；仅统计自由流耗时大于零的已完成行程 |
| `onTimePlannedArrivalFractionAll` | 实际不晚于原始计划到达时刻的 cohort 比例；分母含有计划到达时刻的全部目标行程，未完成者不算准时 |
| `actualArrivalBeforeCohortCutoffFractionAll` | 实际在 cohort 到达截止时刻之前到达的比例，分母为全部目标行程 |

失败与未完成行程不填入零车程或人为惩罚值。存在这类行程时，完整 `carPersonHours` 为 null，需同时报告完成率和失败数量；`observedCarPersonHoursLowerBound` 只表示观测耗时下界。空样本的均值/比例为 null。`trips.jsonl.gz` 与 `experiences.jsonl.gz` 也增加入网时刻、等待、所选路线距离和原始延误等字段；这些数据用于评价，原生行为和收费管理目标沿用现有协议。

### 复现记录

manifest 的 schemaVersion 为 `23`（v21 记录全天收费，v22 拆分每日管理记忆与周期政策调用并记录独立生成设置），包含：

- `selectionSeeds`：人口、受控名单、画像、信息分组和仿真解析后的种子，含显式覆盖值；导入画像时以输入文件及其哈希为准。
- `simulatedPersonIdsSha256`：采样后 person ID 排序，每个 ID 后追加换行，以 UTF-8 计算 SHA-256；可直接核对两次实验是否使用同一份人口名单。
- `runtime`：Java 版本/厂商及操作系统/架构。
- `codeArtifact`：以 JAR 运行时记录实际运行包的路径、大小和 SHA-256；以未打包 classes 运行时仅记录目录类型和路径。
- `populationSampling.capacityScaling.consistencyRelativeTolerance`：实际用于 MATSim 容量一致性检查的容差，100% 采样时为 0。
- `evaluationProtocol`：完整汽车耗时、失败处理、尾部耗时和路线参考值的统计口径。

保存运行 JAR、输入文件和实验目录，结合已有 `inputHashes`、模型设置及请求/回答记录核查实验。仅更换仿真 Seed、保持人口种子不变时，人口名单哈希应保持一致；模型输出以实际请求/回答日志为准。采样后的配置现在也会在 `--validate-only` 阶段执行一致性检查。

### Manager 输入精简（默认 basic 模式）

`morning-pricing-v11-full-day-car` 自动使用 `morning-manager-compact-v3-full-day-car`，无需新增命令行参数。适用于原生旅客 + manager 与 LLM 旅客 + manager。全天汽车耗时、入网前等待、完成情况及政策约束见 [记忆连续性、Manager 反馈与输出校验](memory-manager-integrity.zh.md)。

| 内容 | 实际发送给 manager |
|---|---|
| 路网 | 保留全部 car link 的 ID、起终点；长度、速度、车道数和原始容量通过共享参数表精确恢复；保留实际 flow/storage 系数、容量周期和有效单元长度 |
| 每日交通 | 当前观测周期、最近最多 7 个观测日、此前最多 7 个观测日及日均变化；保留全网延误、通行时间、进入/完成/失败/部分次数和汽车行程完成数量 |
| 道路统计 | 延误最高 20 条、失败/未完成最多 10 条，再加入全部当前收费及近期撤销收费道路；收费窗口另给周期汇总，峰值时间箱独立保留 |
| 策略反馈 | 最近两个实际执行版本的效果，较早版本的观测日合并为摘要；保留原免费期参照。相同收费函数的新版本不等于新干预 |
| 记忆 | 目标 400–600 英文词，硬上限仍为 1000 词；证据少时可以更短，不要求填满。每日反思沿用此目标 |

“近期撤销”以最近两个连续且收费函数不同的政策期及当前政策确定；长期 hold 不会仅因版本编号变化而把最近撤销的收费道路挤掉。历史收费窗口保留首次收费前参照（如有）及最近两个执行阶段。道路筛选只决定统计展示，全部合法 car link 仍可收费。无记录的道路/时间箱明确标识，不据此推断畅通。

`network` 的 `lengthType` 对应 `lengthsMeters` 下标，`roadType` 对应 `roadTypes` 行号，均从 0 开始，仅用于共享参数编码。`roadCorridors.networkRowChains` 是有序的 network 行号列表。`trafficSummary`、`policyFeedback` 的 `totals` 及空间统计数组依照 `trafficMetricColumns` 解释；日均值 = totals / observedDays，每次完整通行的平均延误 = 总延误 / 完成完整路段次数。不会把每日比率直接平均，也不对非整数统计数值取整。

复现文件位于实验输出的 `morning/`：

- `manager-contexts.jsonl.gz`：实际发送的精简上下文。
- `manager-contexts-full.jsonl.gz`：精简前的公开输入，含全部逐日汇总、道路总量、政策版本及历史窗口；原始逐时间箱统计仍在 `metrics.jsonl.gz`。
- `manager-input-sizes.jsonl.gz`：精简前后字符数、上下文 SHA-256 和投影版本；字符数不等于 token 数。
- `manager-attempts.jsonl.gz`：真实 system/user、请求哈希、回答及服务报告的 prompt/completion/cache token 用量。
- `manifest.json`：输入版本、筛选口径、日志路径和记忆目标。

`--manager-profile controlled` 保留政策决策输入与监测/回退机制；每日记忆同样使用 400–600 英文词目标、1000 词硬上限。更换代码后须重新构建或替换服务器 JAR。上下文上限依旧需要覆盖输入、服务聊天模板/结构化输出开销及 `--manager-max-output-tokens`；精简不等于任何模型长度都必然足够。


### 提示词精简与前缀缓存验证

旅客决策模板为 `morning-information-v12-configurable-history`，记忆更新模板为 `morning-reflection-v10-period-window`。共同的事实边界、单位、缺失值和缴费含义放在共享 system 前部；固定旅客属性、原计划、日程、候选 ID 和路线固定属性放在每日内容之前。只保留一份合法选项列表，候选展示顺序、采样与种子、近期窗口与未总结经历共同进入决策，输出校验沿用原有逻辑。

表格使用“columns + 每行一个 JSON 数组”。候选属性保留距离和已提供的基础费用，选择表完整列出每个合法出发时间与路线的组合、驾驶时间、预计到达、收费和总费用。相同的可见收费规则按路线合并展示，保留对应出发时间；历史费用保留自身缴费依据和缺失道路，不把历史估计当成当前报价。联合模式原出发时间的一份估计已在选择表中时，不再重复；旧保存上下文若包含不同的原计划参考估计，仍明确保留它。

历史行保留 day、routeId、选择/实际出发、驾驶/门到门时间、到达、迟到、未完成、stuck、实际支付总额及逐次道路进入缴费。表格时间采用精确数值秒，不通过四舍五入删减数据；`null` 表示未知或未提供，不能当作零耗时或免费。非知情旅客仍看不到最新策略和长期精确缴费表。默认每五天反思并使用对应的五天经历，240 字符目标与 500 字符硬上限不变。

默认 basic manager 先发送完整路网、共享参数表和走廊拓扑，再发送 day、当前策略、反馈和 memory。`publicEnvironment.travelerResponse` 包含当日评估及可能变化的原生策略开关，仍随每日数据发送，不能冻结为静态信息。此顺序只为更长的相同 token 前缀创造条件；是否启用缓存、模型支持、缓存淘汰和实例间复用取决于模型服务。

旅客 `attempts.jsonl.gz`、`memory-attempts.jsonl.gz` 和 `manager-attempts.jsonl.gz` 都记录 `elapsedMillis`、`usage` 及 `promptCacheUsageReported`。服务不返回缓存计数时，后者为 false，usage 中缓存字段为 null；明确报告零命中时为 true 且计数为 0。兼容 `prompt_tokens_details.cached_tokens` 与 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`；仅报告命中数且提供总输入数时，未命中数由二者相减得到。

验证时固定一组包含不同旅客、不同日期、不同信息组的保存上下文，分别比较当前/精简模板与服务端缓存关闭/开启的四种组合。模板对照应保存各自 JAR 或使用各自的实际请求日志；当前回放程序会用当前版本重新渲染保存事实。保持模型、聊天模板、生成参数与请求负载一致，冷缓存和预热测量分开，避免仅重复同一条完整请求而高估真实命中。

已有 `MorningReplay` 可做顺序的固定情境回放和单请求对照，不模拟生产并发吞吐。生产负载另需按真实旅客/记忆请求顺序与并发测量。比较输入 token、服务端缓存命中、首 token 延迟、端到端耗时中位数/P95、输出长度和非法 JSON/修复率，并检查决策及实验指标是否变化。首 token 延迟和 prefill/decode 分项需查模型服务 metrics；Java 的 elapsedMillis 是完整调用耗时。前缀缓存仅减少共享输入的处理计算，不缩小上下文占用或直接提高输出生成速度。

### 固定情境回放

先编辑 [replay-models.example.json](../scenarios/SiouxFalls-public/replay-models.example.json) 中的服务、模型名和 `apiKeyEnv`；对应密钥仍通过环境变量设置。然后从已有实验的固定上下文比较模型：

```powershell
.\scripts\replay_morning_choices.ps1 -InputFile 'output/llm-first-run/morning/context-samples.jsonl.gz' -ModelsFile 'scenarios/SiouxFalls-public/replay-models.example.json' -Output 'output/replay-first-run' -Orders 'original,reverse,shuffle' -OrderSeed 4711 -Limit 20 -Repetitions 1
```

回放不运行 QSim，也不更新个人记忆；真实回放会调用列表中的模型。只验证回放流程时，在命令末尾增加 `-Mock`。每次使用新的回放输出目录。

## 10. 测试与常见问题

```powershell
.\mvnw.cmd -B test
pwsh -File scripts/test_run_public_experiment.ps1
```

普通回归测试使用离线策略或本机测试接口。完整交通背景验证默认跳过，可通过 Maven 参数 `-Dmorning.fullTrafficValidation=true` 显式启用。

历史测试记录对应当时的源码版本。2026-10-15 发布整理移除了旧原型、上游演示及其专用测试；本次仅做静态检查，尚未重新运行构建与回归测试。旧测试数量和结果不代表当前源码已经通过验证。

| 情况 | 处理 |
| --- | --- |
| `Run mvn package first` | 在项目根目录重新打包，确认根目录运行 JAR 存在 |
| `No compiler is provided` / `javac: command not found` | 使用完整 JDK 25，设置 JAVA_HOME/PATH，并确认 `mvn -version` 显示 Java 25；见 Linux/HPC 环境步骤 |
| `release version 25 not supported` | 将 JAVA_HOME、PATH 和 Maven 所用 Java 调整为 JDK 25 |
| PowerShell 提示脚本执行被禁用 | 根据本机策略，可在当前会话执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned` 后重试 |
| `Set AGENTIC_API_KEY` | 在运行的同一个终端设置密钥；程序不会自动读 `.env` |
| 模型 HTTP 401/404 或输出校验失败 | 核对接口密钥、Base URL、服务中的模型名及支持的生成参数 |
| 请求预算不足 | 按人数、天数和旅客双阶段调用调整规模或显式预算 |
| 没有收费政策更新 | 默认第 21 个模拟日首次政策决策；日末记忆从第 1 天起更新 |
| Git 提示 `Author identity unknown` | 设置当前仓库的 `user.name` 和 `user.email` |
| 上传时目标仓库已有 README/提交 | 先克隆该仓库，再把项目文件复制进去提交，保持已有历史 |

## 11. 目录与许可

| 路径 | 用途 |
| --- | --- |
| `src/main/java/org/matsim/agentic/` | 出行者、记忆、路线候选、收费策略、管理者与输出 |
| `src/test/java/org/matsim/agentic/` | 当前功能的回归测试与测试辅助类 |
| `scripts/` | 运行、回放、场景转换、选择分析及 Slurm 模板 |
| `scenarios/SiouxFalls-public/` | 当前全天实验配置和固定收费示例 |
| `scenarios/Souix Falls 2014/` | car-only 路网、设施、100pct/200pct 全天人口及参考配置 |

保留项目原有 [GPL-2.0 许可证](../LICENSE) 与源文件版权声明。Maven Wrapper 使用 [Apache-2.0](../LICENSES/Apache-2.0.txt)；Sioux Falls 输入及派生人口遵循来源数据的 CC BY 4.0 署名条件。来源、修改说明及输入校验值见 [THIRD_PARTY.md](../THIRD_PARTY.md)。

个人环境配置、运行输出、编译缓存和本地代码快照不随源码分发。
