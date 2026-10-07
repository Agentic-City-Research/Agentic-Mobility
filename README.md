# AgenticCity：MATSim 出行者与收费管理实验

本项目在 MATSim 2026.0 上实现 LLM 出行者的路线与出发时间联合选择、个人经历与自写记忆，以及收费管理者实验。默认使用 Sioux Falls 全天活动链；核心和缓冲早高峰单程场景单独保留。

当前运行入口是 `org.matsim.agentic.MorningExperiment`。项目以 Java 源码、场景输入和脚本分发，运行前需要构建 JAR。


## 1. 下载与环境准备

其他使用者可以克隆仓库。下文以仓库名 `AgenticCity` 为例，替换 GitHub 用户名；如果仓库使用其他名字，同时替换 URL 和进入的目录名。

```powershell
git clone https://github.com/YOUR_GITHUB_USERNAME/AgenticCity.git
Set-Location AgenticCity
```

| 环境 | 用途 |
| --- | --- |
| **JDK 25** | 编译及运行；本项目设置 `maven.compiler.release=25` |
| Git | 上传、克隆与更新源码 |
| Maven Wrapper | 项目自带，首次构建联网下载 Maven 和依赖；也可使用已有 Maven |
| PowerShell | 执行 Windows 运行和回放脚本 |
| Python 3 | 场景转换、出行选择分析及相关测试，脚本使用标准库 |
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

### 10% 人口与容量的原生基线

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 30 -Seed 4711 -Output 'output/baseline-pct10'
```

`-SampleRate 0.1` 同时采样人口并缩放 flow/storage capacity。不要与 `-SamplePersons` 同时使用。`-Demand 100` 使用恢复的 84,110 人全天人口；采样后为 8,411 人。`-Demand 200` 使用公开数据谱系中的 168,220 人需求。

完整人口实验可以省略 `-SampleRate` 和 `-SamplePersons`。正式比较需要保持人口、容量、随机种子和其他设置一致。

## 5. 接入真实模型，运行 LLM 出行者

### 设置模型接口

在启动实验的同一个 PowerShell 窗口设置：

```powershell
$env:AGENTIC_API_KEY = "YOUR_MODEL_API_KEY"
$env:AGENTIC_MODEL = "YOUR_SERVED_MODEL_NAME"
$env:AGENTIC_BASE_URL = "http://127.0.0.1:8001/v1"
```

这里的地址是本机兼容服务的示例，要求服务已经启动；远程或云端模型应替换成对应地址、模型名和密钥。客户端会在 Base URL 后追加 `/chat/completions`；如果已填写完整的该接口 URL，则直接使用。

这些变量只在当前 PowerShell 会话生效。完整配置项见 [.env.example](.env.example)，程序不会自动读取 `.env`。实际密钥放在环境变量或本地配置中，不写入源码和模型列表。

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
  -MaxOutputTokens 512 -MemoryMaxOutputTokens 1024 -MaxRequests 200 `
  -Output 'output/llm-first-run'
```

LLM 旅客在每一天联合选择路线与出发时刻，仿真结束后再调用模型更新私人摘要。候选路线最多四条，当前默认出发时间网格为原始计划时刻前 90 分钟至后 30 分钟、每 15 分钟一项。

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

管理者默认前 20 个模拟日不收费，第 21 日首次调用，此后每 30 日更新一次。这个 30 天示例包含一次计划更新机会；若只运行 7 天，即使加了 `-Manager` 也尚未到调用日期。

当前默认管理者为 **Basic**：完整记忆最多 1000 英文词、政策最多 40 条规则、收费时窗按 15 分钟分箱对齐。显式 `-ManagerProfile controlled` 启用旧监测/回退条件，详见 [manager_v5.md](scripts/manager_v5.md)。当前信息边界和默认协议见 [manager_basic_joint.md](scripts/manager_basic_joint.md)。

要同时启用 LLM 旅客与管理者，可在上一节旅客命令中增加 `-Manager`，将天数设置为至少 21，并相应调整调用预算。

### 固定收费，无模型管理者

```powershell
.\scripts\run_public_experiment.ps1 -Mode hybrid -Policy mnl -LlmAgents 0 -Demand 100 -SampleRate 0.1 -Days 30 -Seed 4711 -FixedTollPolicy 'scenarios/SiouxFalls-public/fixed-tolls-75_1-1unit.json' -FixedTollFreeDays 20 -Output 'output/fixed-toll-pct10'
```

该例不调用模型，第 21 个模拟日开始执行固定政策。`-FixedTollPolicy` 与 `-Manager` 互斥。JSON 的金额为场景货币单位，示例政策用于实验对照；格式与规则见 [fixed-tolls.md](scenarios/SiouxFalls-public/fixed-tolls.md)。

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
  --max-output-tokens 512 --memory-max-output-tokens 1024 --max-requests 200 \
  --output output/llm-first-run-java
```

查看完整 Java 参数：

```bash
java -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment --help
```

Slurm 模板为 [run_hpc_qwen.slurm](scripts/run_hpc_qwen.slurm) 和 [agentic_morning_qwen35.slurm](scripts/agentic_morning_qwen35.slurm)。按所在集群设置分区、项目目录、Java/Python 和已启动的模型服务；准备步骤见 [Slurm 说明](scripts/agentic_morning_qwen35.md)。

## 8. 常用参数

| PowerShell 参数 | Java 参数 | 作用 |
| --- | --- | --- |
| `-Mode hybrid` | `--mode hybrid` | 当前主实验入口，保留原生全天背景 |
| `-Policy llm` | `--policy llm` | 旅客使用真实模型；其他可选值为 mnl、shortest、mock |
| `-LlmAgents 3` | `--llm-agents 3` | 合格目标人员中由所选策略接管的人数；支持 0/all |
| `-Demand 100` | `--demand 100` | 选择 100pct 原始人口；200 为双倍需求 |
| `-SampleRate 0.1` | `--sample-rate 0.1` | 人口与道路容量按同一比例采样 |
| `-SamplePersons 100` | `--sample-persons 100` | 程序诊断抽样，不缩放容量 |
| `-Days 30` | `--days 30` | 模拟天数，含 iteration 0 |
| `-Seed 4711` | `--seed 4711` | MATSim 随机种子；模型生成种子是独立参数 |
| `-Manager` | `--manager` | 启用 LLM 收费管理者 |
| `-FixedTollPolicy FILE` | `--fixed-toll-policy FILE` | 从 JSON 加载固定收费政策 |
| `-CandidateFile FILE` | `--candidate-file FILE` | 复用候选路线；所需 OD 缺失会报错 |
| `-ProfileFile FILE` | `--profile-file FILE` | 复用既有固定画像；缺失人员或冲突会报错 |
| `-HistoryWindow 5` | `--history-window 5` | 决策前的近期具体经历窗口 |
| `-Concurrency 3` | `--concurrency 3` | 模型请求并发上限 |
| `-MaxRequests 200` | `--max-requests 200` | 模型 client 调用预算，包含输出修复 |
| `-Output PATH` | `--output PATH` | 指定独立实验目录 |
| `-ValidateOnly` | `--validate-only` | 准备与验证输入，零仿真、零模型调用 |
| `-Heap 8g` | `java -Xmx8g` | Java 最大堆；全背景运行需按机器资源调整 |

本表只列常用项，详细参数及实验信息口径见 [全天场景说明](scenarios/SiouxFalls-public/README.md)。

## 9. 输出在哪里，如何检查完成

示例命令指定了 `-Output`。省略时，程序自动使用 `output/<日期和参数>_seed<种子>`；重名追加 `-2`、`-3`，保留原有实验。PowerShell 完整控制台日志保存在 `output/_logs/public-*.log`，启动时显示路径。

核心记录位于每个实验目录的 `morning/`：

| 文件 | 内容 |
| --- | --- |
| `manifest.json` | 参数、输入 SHA-256、人口/容量、协议版本及模型设置 |
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

### 固定情境回放

先编辑 [replay-models.example.json](scenarios/SiouxFalls-public/replay-models.example.json) 中的服务、模型名和 `apiKeyEnv`；对应密钥仍通过环境变量设置。然后从已有实验的固定上下文比较模型：

```powershell
.\scripts\replay_morning_choices.ps1 -InputFile 'output/llm-first-run/morning/context-samples.jsonl.gz' -ModelsFile 'scenarios/SiouxFalls-public/replay-models.example.json' -Output 'output/replay-first-run' -Orders 'original,reverse,shuffle' -OrderSeed 4711 -Limit 20 -Repetitions 1
```

回放不运行 QSim，也不更新个人记忆；真实回放会调用列表中的模型。只验证回放流程时，在命令末尾增加 `-Mock`。每次使用新的回放输出目录。

## 10. 测试与常见问题

```powershell
.\mvnw.cmd -B test
python -m unittest discover -s scripts -p test_extract_morning_scenario.py
pwsh -File scripts/test_run_public_experiment.ps1
```

普通回归测试使用离线策略或本机测试接口。完整交通背景验证默认跳过，可通过 Maven 参数 `-Dmorning.fullTrafficValidation=true` 显式启用。

2026-10-07，本地 JDK 25 / Maven 3.9.15 验证结果：158 项 Java 测试中 157 项通过、1 项按配置跳过；Python 提取测试、PowerShell 运行脚本测试、打包及 Java `--help` 均通过。此记录不代表已经验证某个真实模型服务或收费政策的研究效果。

| 情况 | 处理 |
| --- | --- |
| `Run mvn package first` | 在项目根目录重新打包，确认根目录运行 JAR 存在 |
| `No compiler is provided` / `javac: command not found` | 使用完整 JDK 25，设置 JAVA_HOME/PATH，并确认 `mvn -version` 显示 Java 25；见 Linux/HPC 环境步骤 |
| `release version 25 not supported` | 将 JAVA_HOME、PATH 和 Maven 所用 Java 调整为 JDK 25 |
| PowerShell 提示脚本执行被禁用 | 根据本机策略，可在当前会话执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned` 后重试 |
| `Set AGENTIC_API_KEY` | 在运行的同一个终端设置密钥；程序不会自动读 `.env` |
| 模型 HTTP 401/404 或输出校验失败 | 核对接口密钥、Base URL、服务中的模型名及支持的生成参数 |
| 请求预算不足 | 按人数、天数和旅客双阶段调用调整规模或显式预算 |
| 管理者没有开始调用 | 核对天数：默认第 21 个模拟日首次调用 |
| Git 提示 `Author identity unknown` | 设置当前仓库的 `user.name` 和 `user.email` |
| 上传时目标仓库已有 README/提交 | 先克隆该仓库，再把项目文件复制进去提交，保持已有历史 |

## 11. 目录与许可

| 路径 | 用途 |
| --- | --- |
| `src/main/java/org/matsim/agentic/` | 出行者、记忆、路线候选、收费策略、管理者与输出 |
| `src/main/java/org/matsim/other/`、`src/main/java/org/matsim/project/` | MATSim 原生入口与上游兼容代码 |
| `src/test/java/`、`test/input/` | 回归测试与必要的公开基准文件 |
| `scripts/` | 运行、回放、场景转换、选择分析及 Slurm 模板 |
| `scenarios/SiouxFalls-public/` | 当前全天实验配置和固定收费示例 |
| `scenarios/Souix Falls 2014/` | car-only 路网、设施、100pct/200pct 全天人口及参考配置 |
| `scenarios/SiouxFalls-AM/` | 核心与缓冲早高峰单程场景 |
| `scenarios/equil/` | 上游小型示例场景 |

保留项目原有 [GPL-2.0 许可证](LICENSE) 与源文件版权声明。Maven Wrapper 使用 [Apache-2.0](LICENSES/Apache-2.0.txt)；Sioux Falls 输入及派生人口遵循来源数据的 CC BY 4.0 署名条件。来源、修改说明及输入校验值见 [THIRD_PARTY.md](THIRD_PARTY.md)。

个人环境配置、运行输出、编译缓存和本地代码快照不随源码分发。
