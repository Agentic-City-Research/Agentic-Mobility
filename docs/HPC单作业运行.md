# 同一 Slurm 作业启动 LLM 和 MATSim

使用 [`run_single_job_llm_matsim.slurm`](../scripts/run_single_job_llm_matsim.slurm)，一个作业在同一计算节点上启动 vLLM 服务，验证服务就绪后运行 MATSim；MATSim 结束、模型进程退出或作业接收终止信号时，脚本清理本作业的进程。脚本面向当前提供的 Q10 环境：4 张 L20、16 CPU、64 GB 主存、最长 72 小时、`gcc14.2` 和 `qwen38-cu130` 环境。资源是否足够取决于实际模型、上下文长度和实验规模。

## 1. 准备

在 HPC 上上传整个项目，至少包含重新构建的项目根目录 JAR、`scenarios/`、`config/` 和该脚本，保持相对目录不变。当前默认模型目录为 `$HOME/models/Qwen3.5-9B`，Python 环境初始化脚本为 `$HOME/miniforge3/etc/profile.d/conda.sh`，本地模型服务密钥文件为 `$HOME/.vllm_api_key`。Java 需为 JDK 25；`java -version`、`command -v vllm` 与 `vllm serve --help` 可用于核对环境。首次提交前在登录节点创建日志目录，Slurm 在脚本启动前就会打开它：

```bash
cd AgenticMobility-v2
export PROJECT_DIR="$PWD"
export JAVA_HOME="$HOME/opt/java25"
export JAVA_BIN="$JAVA_HOME/bin/java"
# 可选：如果已经准备好并上传候选缓存，设置 CANDIDATE_FILE
# export CANDIDATE_FILE="$PROJECT_DIR/cache/candidates_100.jsonl.gz"
export AGENTIC_REASON_MAX_CHARS=300
```

`/你的HPC项目目录/AgenticMobility-v2` 是占位路径，替换为上传后的实际路径。脚本使用 `VLLM_API_KEY` 环境变量并通过本机 `127.0.0.1` 访问服务；[vLLM 环境变量文档](https://docs.vllm.ai/en/stable/configuration/env_vars/)说明该环境变量用于 API 服务密钥。不要在命令或脚本里写密钥。`$HOME/.vllm_api_key` 需由用户在集群上自行安全准备。

## 2. 先运行小样本

```bash
sbatch scripts/run_single_job_llm_matsim.slurm
squeue -u "$USER"
```

默认运行 2 天、最多 3 个受控目标人，`SAMPLE_PERSONS=20` 是**不缩放容量的调试抽样**，不代表正式 20% 样本。模型服务默认使用四个数据并行副本（`DATA_PARALLEL_SIZE=4`、`TENSOR_PARALLEL_SIZE=1`），与申请的 4 张 L20 对应。Traveler 默认每 5 天反思，2 天试运行不会触发旅客反思；要测试反思流程请运行至少 5 天。此小样本默认不开启 Manager。

服务的 `/v1/models` 就绪检查只能确认模型服务已启动；首次小样本中的真实选择调用才能继续检验模型的聊天模板和输出。如果要同时检验旅客反思、Manager 每日反思和首次政策生成，可以在前一个作业结束后运行：

```bash
sbatch --export=ALL,DAYS=21,ENABLE_MANAGER=1 scripts/run_single_job_llm_matsim.slurm
```

Manager 默认第 21 个仿真日首次生成政策；这次小样本仍是诊断运行，不是正式实验。

Slurm 标准日志默认写入提交目录的 `agentic-single-<jobid>.log`，vLLM 日志为 `$PROJECT_DIR/output/hpc/vllm-<jobid>.log`，实验输出为 `$PROJECT_DIR/output/hpc/single-job-<jobid>/`。以作业日志中打印的实际路径为准：

```bash
JOB_ID=12345  # 替换为 sbatch 返回的作业编号
tail -f "agentic-single-${JOB_ID}.log"
tail -f "$PROJECT_DIR/output/hpc/vllm-${JOB_ID}.log"
python3 scripts/verify_run_integrity.py "$PROJECT_DIR/output/hpc/single-job-${JOB_ID}/morning"
```

作业仅在 `status.json` 显示预期天数、`complete=true` 且 `integrityVerified=true` 后返回成功。`VALIDATE_ONLY=1` 仍会启动并检测模型服务，但 Java 只验证输入，不运行 QSim 或发起旅客模型调用；这不能替代完整仿真检查。

## 3. 正式实验示例

下例启用全部目标 LLM 旅客和同一 Qwen 服务上的 Manager，人口样本比例 20%，执行 100 天：

```bash
sbatch --export=ALL,RESPONSE_FORMAT=json_schema,DAYS=100,LLM_AGENTS=all,SAMPLE_RATE=0.2,SEED=4711,POPULATION_SEED=6021,INFORMATION_SEED=6021,ENABLE_MANAGER=1,CONCURRENCY=196,MAX_REQUESTS=5000000 scripts/run_single_job_llm_matsim.slurm
```

`SAMPLE_RATE` 非空时使用比例抽样和容量缩放，不再使用 `SAMPLE_PERSONS`。参数 `MAX_REQUESTS` 只是共享调用上限，并不保证已经足够：正式运行前应结合目标行程数量、100 天的旅客决策和每五天反思、Manager 每日反思与定期政策调用、重试余量计算预算。Manager 即使在前 20 个免费观察日也每天请求模型。

默认 Manager 与旅客调用同一个本机模型服务。启用 Manager 时默认限制政策输出 2048 tokens、管理记忆输出 1024 tokens，可通过 `MANAGER_MAX_OUTPUT_TOKENS` 和 `MANAGER_MEMORY_MAX_OUTPUT_TOKENS` 调整。**模型 8192 token 上下文仍可能容不下路网、反馈及请求输出**；更低的输出上限也可能不足以生成完整政策。应先用真实 Manager 输入做小规模测试，再按实际模型和显存调整 `MAX_MODEL_LEN`、输出限额和预算，不能假设 8192 一定足够。

如果 Manager 要使用远端独立服务，明确设置 `MANAGER_SERVICE=remote`、`AGENTIC_MANAGER_BASE_URL`、`AGENTIC_MANAGER_MODEL` 和 `MANAGER_KEY_FILE`；后者指向只包含密钥的文件。具体终端、模型名和授权由用户配置；不要把密钥本身写进 `sbatch --export` 或脚本。`MANAGER_SERVICE=local` 会清除继承的 Manager 专用服务覆盖项，避免意外请求远端。

## 4. 常用覆盖项

| 环境变量 | 默认值或用途 |
| --- | --- |
| `PROJECT_DIR`、`JAR`、`CONFIG` | 项目根目录、构建好的 JAR、场景配置路径。 |
| `MODEL_PATH`、`MODEL_NAME`、`KEY_FILE` | 默认 `$HOME/models/Qwen3.5-9B`、`qwen3.5-9b`、`$HOME/.vllm_api_key`。 |
| `CONDA_ENV`、`GCC_MODULE`、`JAVA_BIN`、`VLLM_BIN` | 默认 `qwen38-cu130`、`gcc14.2`、`java`、`vllm`。 |
| `PORT`、`READY_TIMEOUT` | 本地端口默认 8002、等待模型就绪默认 1200 秒；占用端口会失败。 |
| `MAX_MODEL_LEN`、`DATA_PARALLEL_SIZE`、`TENSOR_PARALLEL_SIZE` | 默认 8192、4、1；修改并行数量需同时调整 GPU 申请。 |
| `DAYS`、`LLM_AGENTS`、`SAMPLE_RATE`、`SAMPLE_PERSONS` | 天数、目标人数量、正式比例抽样或诊断人数抽样。 |
| `TRAVELER_REFLECTION_INTERVAL`、`HISTORY_WINDOW` | 前者默认 5，后者未指定时跟随前者；支持明确设置 1–5 天历史。 |
| `CANDIDATE_FILE` | 可选预生成候选文件的 HPC 路径；缺少 OD 时程序报错。 |
| `ENABLE_MANAGER`、`MANAGER_SERVICE`、`MANAGER_PROFILE` | 默认 0、`local`、`basic`。`ENABLE_MANAGER=1` 启用 Manager。 |
| `OUTPUT_DIR`、`SERVICE_LOG` | 输出目录、vLLM 日志完整路径；避免复用已存在的输出目录。 |

模型及集群环境会随本地安装变化。`bash -n scripts/run_single_job_llm_matsim.slurm` 只检查 shell 语法；只有实际提交并检查日志，才能确认模块名、模型加载、GPU 显存、服务响应和计算节点网络在该 HPC 可用。
