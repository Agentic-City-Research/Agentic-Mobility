# 在 Slurm 上调用当前 MorningExperiment

`agentic_morning_qwen35.slurm` 根据你提供的脚本修改，默认继续连接已经启动的 **Qwen3.5-9B** 服务。它提交 MATSim 的 CPU 作业；Qwen GPU 服务需要先启动。

## 上传与准备

把以下本地文件上传到服务器，保持 `scenarios` 目录结构完整：

- `scripts/agentic_morning_qwen35.slurm` → `$HOME/agentic_morning_qwen35.slurm`。
- 当前项目根目录的 `matsim-example-project-0.0.1-SNAPSHOT.jar` → `$HOME/matsim-example-morning/`。
- 当前项目的 `scenarios/` → `$HOME/matsim-example-morning/scenarios/`。

上传的是 `.slurm` 文件本身，其中没有外层 `cat ... <<'EOF'`。文件使用 LF 换行。

服务器上先执行：

```bash
bash -n "$HOME/agentic_morning_qwen35.slurm"
```

默认从 PATH 查找 Java 25 与 Python 3，日志写到提交作业的目录；用 `sbatch --partition=<partition>` 指定所在集群的分区。如果服务器路径不同，修改脚本或通过环境变量覆盖 `PROJECT_DIR`、`JAVA_BIN`、`PYTHON_BIN`、`API_KEY_FILE`。API key 通过环境变量传给 Java，不打印到日志。

## 关键修改

当前 JAR 默认启用原生 `TimeAllocationMutator`（配置权重 0.1），保留背景/返程的时刻调整，受控目标出行仍由 LLM 联合选择时刻与路线。
脚本新增 `NATIVE_TIME_MUTATION=1`；设为 `0` 会传入 `--no-native-time-mutation`，恢复原生固定时刻条件。
需同时上传更新后的 JAR 和脚本。是否启用及变异范围记录在 manifest；自动运行名包含 `tm0.1`。

1. 配置改为 `scenarios/SiouxFalls-public/config.xml`，加载原始人口。
2. 删除 `--sample-persons 8411`，使用 `--sample-rate "$SAMPLE_RATE"` 同时调整人口、QSim 流量容量和存储容量。两种抽样参数不能同时使用。
3. 增加 `ENABLE_MANAGER=0/1`，控制是否传入 `--manager`，默认保持你原脚本的无 manager 设置。
4. 显式传入当前的信息分组参数：`--informed-share 0.5 --information-scope controlled`，对应程序默认设置。
5. 输出目录按实际模型、采样率和 Slurm 作业号区分；为每个作业复制独立 JAR，避免上传新 JAR 时影响正在运行的作业。

默认配置的两项基础容量系数均为 **1.0**；`SAMPLE_RATE=k` 使用 k 比例的人口，并设置流量容量系数为 **k**、存储容量系数为 **k^0.75**。例如 `k=0.1` 时，流量系数为 **0.1**、存储系数约为 **0.177828**。请保留原始人口文件；不要把 `CONFIG` 指向已经抽样的人口配置，否则会二次抽样。

`POPULATION_SEED` 独立固定人口抽样，默认固定为 `4711`；也接受 `SAMPLE_SEED` 环境变量别名。比较不同仿真种子时保持 `POPULATION_SEED` 不变，可在相同输入人口、需求和采样率下使用同一批人口；它通过 `--population-seed` 传给 Java，并记录在 manifest 中。默认画像和信息分组也随该种子固定。新增参数需要重新构建 JAR。

| 参数 | 默认值 | 含义 |
|---|---|---|
| `SAMPLE_RATE` | `0.1` | 人口比例 k；流量容量系数 k，存储容量系数 k^0.75；`1` 表示完整人口和容量 |
| `POPULATION_SEED` | `4711` | 人口抽样种子；独立于仿真种子 SEED |
| `DEMAND` | `100` | 原始需求，加载 84,110 人；`200` 加载 168,220 人 |
| `LLM_AGENTS` | `200` | 抽样完成后，选取 200 名早高峰出行者由 LLM 决策；`0` 为原生出行者，`all` 为所有早高峰出行者 |
| `ENABLE_MANAGER` | `0` | `1` 启用 manager：先运行 20 个免费日，再每 30 日更新一次收费策略 |
| `MANAGER_UPDATE_INTERVAL` | `30` | manager 更新间隔（仿真日），传给 `--manager-update-interval`，保存在 manifest 中 |
| `DAYS` | `100` | 模拟天数，包含第 0 次迭代 |
| `INFORMATION_SCOPE` | `controlled` | 在 LLM 控制的人群中分组；`all` 将背景人口也纳入信息分组 |
| `INFORMED_SHARE` | `0.5` | 上述分组范围内的知情比例 |
| `CONCURRENCY` | `60` | 大模型请求并发上限 |
| `CANDIDATE_POOL_SIZE` | `16` | BFS-LE 先收集的不同路径数，再筛选最多四条 |
| `CANDIDATE_SEARCH_LIMIT` | `10000` | 每个 OD 的子网络搜索上限 |
| `CANDIDATE_MAX_TIME_RATIO` | `2.5` | 自由流时间/c0 比值；`Infinity` 关闭该限制 |
| `CANDIDATE_TIME_SLACK_SECONDS` | `300` | 时间比超限时允许的绝对增加；`0` 使用严格比值 |
| `CANDIDATE_MAX_DISTANCE_RATIO` | `2.5` | 距离/c0 比值；`Infinity` 关闭该限制 |
| `CANDIDATE_DISTANCE_SLACK_METERS` | `2000` | 距离比超限时允许的绝对增加；`0` 使用严格比值 |
| `CANDIDATE_MAX_OVERLAP` | `0.85` | 任意两条保留路线的长度重叠上限；`1` 关闭 |

因此默认设置是 **总人口 8,411 人，其中 200 名早高峰出行者由 LLM 控制**，其中 100 人知情、100 人不知情。`LLM_AGENTS` 不随采样率自动缩放。

候选始终保留最短时间 c0，其余按自由流时间、距离、完整 link 序列依次筛选。
时间与距离分别通过“比值限制或绝对增加余量”，两项都需满足；筛选后允许只有 1–4 条。
以上阈值是可调整的实验起点，并非已校准参数。manifest 和 candidate-quality 保存设置及搜索/筛选数量。
不同实验组应复用同一冻结候选文件，例如提交时设置
`CANDIDATE_FILE="$HOME/matsim-example-morning/你的预检查目录/morning/candidates.jsonl.gz"`。
导入保留原编号与顺序，并检查当前质量限制；不会自动修剪不合格文件。
复用旧无限制文件作对照时，同时设置 `CANDIDATE_MAX_TIME_RATIO=Infinity CANDIDATE_MAX_DISTANCE_RATIO=Infinity CANDIDATE_MAX_OVERLAP=1`。

## 提交实验

先做 3 天、3 名 LLM 出行者的小规模服务验证：

```bash
DAYS=3 LLM_AGENTS=3 CONCURRENCY=3 MAX_REQUESTS=100 \
  SAMPLE_RATE=0.1 ENABLE_MANAGER=0 \
  sbatch "$HOME/agentic_morning_qwen35.slurm"
```

3 天尚未到 manager 的首个更新日。如需验证 manager 的真实生成调用，应运行至少 21 天。

原脚本规模：100 天、200 名 LLM 出行者。保持相同采样率与种子比较是否启用 manager：

```bash
# 无 manager
SAMPLE_RATE=0.1 ENABLE_MANAGER=0 SEED=9261 \
  sbatch "$HOME/agentic_morning_qwen35.slurm"

# 有 manager
SAMPLE_RATE=0.1 ENABLE_MANAGER=1 SEED=9261 \
  sbatch "$HOME/agentic_morning_qwen35.slurm"
```

若要复现“原生出行者 baseline 有/无 manager”的比较，在这两条命令中都增加 `LLM_AGENTS=0`。有 manager 时仍需要 Qwen 服务。

修改采样率只需设置一个参数，例如：

```bash
SAMPLE_RATE=0.2 ENABLE_MANAGER=1 \
  sbatch "$HOME/agentic_morning_qwen35.slurm"
```

`DEMAND=100` 时，这会抽取 16,822 人，同时设置两项容量系数为 0.2。

只验证输入、抽样和候选路线，避免启动交通模拟及 LLM 生成调用：

```bash
VALIDATE_ONLY=1 SAMPLE_RATE=0.1 ENABLE_MANAGER=1 \
  sbatch "$HOME/agentic_morning_qwen35.slurm"
```

该模式仍会检查已经运行的 Qwen 服务的 `/v1/models`。

## 指向实际的大模型服务

默认设置沿用你提供的值：

```bash
QWEN_JOB_NAME="qwen35-9b"
QWEN_PORT="8002"
MODEL_NAME="qwen3.5-9b"
```

`MODEL_NAME` 必须匹配服务 `/v1/models` 返回的 `id`，即 vLLM 对外提供的模型名。它不一定等于模型文件夹名。

如果同名服务有多个运行中的作业，明确指定 Qwen GPU 作业号：

```bash
QWEN_JOB_ID=你的GPU作业号 SAMPLE_RATE=0.1 ENABLE_MANAGER=1 \
  sbatch "$HOME/agentic_morning_qwen35.slurm"
```

切换到另一项服务时，可同时覆盖 `QWEN_JOB_NAME`（或 `QWEN_JOB_ID`）、`QWEN_PORT` 和 `MODEL_NAME`。`QWEN_GENERATION_PARAMS` 默认是 `{"chat_template_kwargs":{"enable_thinking":false}}`，用于当前 Qwen 服务；换模型时也需要按该服务支持的参数调整它。

当前程序的出行决策、个人记忆和 manager **共享同一模型/API 配置**。开启 manager 后也调用这个 Qwen3.5-9B 服务。若需要出行者使用 Qwen、manager 使用另一模型，需要另外修改 Java 程序；当前命令行没有分离模型参数。

结果默认保存在：

```text
$HOME/matsim-example-morning/output/hpc/qwen3.5-9b/sample0.1/job-<作业号>/<日期>_day100_llm200_<mgr或noMgr>_seed9261/
```

脚本会在运行前拒绝旧版 JAR、无效采样率、模型 ID 不匹配或重复输出目录。已在本地通过 Bash 语法检查、模拟 Slurm/API 的成功与失败分支检查，以及当前真实 JAR 的 `--validate-only` 验证；尚未在服务器上提交作业或验证真实 vLLM 生成接口。


## 更新后的容量校验与评价记录

修改源码后重新构建并上传运行 JAR。新包支持 MATSim 2026 下 `flow=k`、`storage=k^0.75` 的一致性校验，schema 18 manifest 记录有效容差、各类种子、人口名单哈希、Java 环境及实际执行 JAR 的 SHA-256。作业输出的 Runtime JAR SHA256 应与 manifest 的 `codeArtifact.sha256` 一致。

每日日志增加 `fullDayCarTravel`、入网前等待、最慢 5% cohort 车程、路线距离、旅行时间指数和准时率，口径见 [英文操作指南的评价指标](../docs/usage-guide.md#evaluation-metrics)。本次新增记录不改变原生时间调整方式、策略权重和 manager 协议。
