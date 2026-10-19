# HPC：Qwen Traveler + DeepSeek Manager

2026-10-19。入口仍为 `MorningExperiment`。本次只增加按角色选择模型服务的能力，保持全天收费、信息边界、出发时间候选、私人记忆与原生交通协议。

## 调用关系

| 阶段 | 服务 |
|---|---|
| Traveler 决策、私人记忆、可选语义复核 | 原来的 Qwen/vLLM 配置 |
| Manager 政策决策、每天的 Manager 记忆 | Manager 专用配置 |

不提供 `AGENTIC_MANAGER_*` 或 `--manager-*` 模型参数时，Manager 完整继承原模型地址、模型名、密钥与生成参数，原有纯 Qwen 脚本继续工作。

如果 Manager 换到不同 endpoint，必须明确指定 Manager 模型，密钥不会从 Qwen 复制。Qwen 专用生成参数、temperature、top_p、生成 seed 和 token 字段也不会跨服务复制；可用 Manager 专用参数重新设置。超时默认继承全局值，可单独覆盖。DeepSeek 官方 endpoint 的默认格式为 `json_object`、自动思考配置为关闭；其他独立服务默认省略输出格式，由用户显式选择。

## 上传文件

本地构建完成后，将新的 `matsim-example-project-0.0.1-SNAPSHOT.jar` 上传到 HPC 项目目录，并上传：

- `scripts/agentic_morning_qwen35_deepseek_manager.slurm`：新增的双服务 CPU 作业入口。
- `scripts/agentic_morning_qwen35.slurm`：原 Qwen 运行脚本，新入口委托它寻找 Qwen GPU 作业、检查服务及运行 MATSim。
- `scenarios/`：若服务器已有完全相同的输入，无须重复上传。

`PROJECT_DIR` 默认 `$HOME/matsim-example-morning`。它必须指向服务器实际项目目录。Qwen GPU 服务仍使用原来的启动方式，MATSim 作业不会启动或取消 GPU 作业。

## 密钥

Qwen 仍读取 `API_KEY_FILE`，默认 `$HOME/.vllm_api_key`。DeepSeek 使用独立密钥，读取优先级为：

1. `AGENTIC_MANAGER_API_KEY`。
2. `DEEPSEEK_API_KEY`，由新增 Slurm 入口映射到 Manager；Java 本身只读取 `AGENTIC_MANAGER_API_KEY`。
3. `MANAGER_API_KEY_FILE`，默认 `$HOME/.deepseek_api_key`。

可以在服务器交互终端中创建文件，避免密钥出现在命令历史或 sbatch 命令中：

```bash
umask 077
read -rsp 'DeepSeek API key: ' manager_key
printf '\n'
printf '%s\n' "$manager_key" > "$HOME/.deepseek_api_key"
unset manager_key
chmod 600 "$HOME/.deepseek_api_key"
```

不要启用 `set -x`，不要将密钥写入提交脚本或 Git。程序的模型 manifest 不包含密钥。

## 提交

先确保原 Qwen/vLLM GPU 作业处于 RUNNING，并记录作业号。以下 `<...>` 需要替换为集群实际值：

```bash
export PROJECT_DIR="$HOME/matsim-example-morning"
export QWEN_JOB_ID=<正在运行的Qwen作业号>
export QWEN_PORT=8002
export MODEL_NAME=qwen3.5-9b

# 首次联调：完整的20%背景人口，只控制200名Traveler。
export DAYS=25
export LLM_AGENTS=200
export SAMPLE_RATE=0.2
export POPULATION_SEED=6021
export SEED=9261

sbatch --partition=<CPU分区> \
  "$PROJECT_DIR/scripts/agentic_morning_qwen35_deepseek_manager.slurm"
```

新入口默认 `DAYS=100`、`LLM_AGENTS=all`、`DEMAND=100`、`SAMPLE_RATE=0.2`、`POPULATION_SEED=6021`、`SEED=9261`、`MAX_REQUESTS=40000000`、Manager Basic、30天更新、政策8192 tokens、Manager记忆2048 tokens。要从联调切回正式实验：

默认结果根目录为 `$PROJECT_DIR/output/hpc/qwen35-deepseek-manager/sample0.2`，其下按 Slurm 作业号和实验运行名分目录；可通过 `OUTPUT_ROOT` 或 `OUTPUT_DIR` 覆盖。

```bash
export DAYS=100
export LLM_AGENTS=all
sbatch --partition=<CPU分区> --time=<集群允许的足够时长> \
  "$PROJECT_DIR/scripts/agentic_morning_qwen35_deepseek_manager.slurm"
```

脚本默认 walltime 为24小时，不保证足够完成100天。应根据实测速率设置允许的时长，并保证 Qwen GPU 作业也覆盖整个实验；当前流程不会在时限结束后自动续跑。100天、11,793名目标Traveler，每天一次决策和反思约需2,358,600次基础Traveler调用，另有100次Manager反思和3次政策调用，重试另计。

提交脚本默认执行 Manager 的认证 `GET /models` 预检，不产生模型生成调用，随后分别检查 Qwen 服务。预检发生在实际计算节点；计算节点必须能通过集群允许的网络访问 DeepSeek。`MANAGER_PREFLIGHT=0` 只跳过 `/models` 预检，不解决 Java 的网络连通性问题。若使用代理，curl 连通不能代替 Java API 连通验证，需要管理员提供适用的 Java/HTTPS 出口配置。

`VALIDATE_ONLY=1` 跳过 DeepSeek 密钥要求和远程预检，不产生生成调用；原Qwen脚本仍检查现有Qwen作业及 `/models`。若要完全离线检查配置，直接使用下文 Java 的 `--validate-only`。

## 底层配置接口

| CLI | 环境变量 |
|---|---|
| `--manager-model` | `AGENTIC_MANAGER_MODEL` |
| `--manager-base-url` | `AGENTIC_MANAGER_BASE_URL` |
| 无密钥 CLI 参数 | `AGENTIC_MANAGER_API_KEY` |
| `--manager-thinking` | `AGENTIC_MANAGER_THINKING` |
| `--manager-response-format` | `AGENTIC_MANAGER_RESPONSE_FORMAT` |
| `--manager-generation-params` | `AGENTIC_MANAGER_GENERATION_PARAMS` |
| `--manager-token-limit-field` | `AGENTIC_MANAGER_TOKEN_LIMIT_FIELD` |
| `--manager-timeout-seconds` | `AGENTIC_MANAGER_TIMEOUT_SECONDS` |
| `--manager-temperature` | `AGENTIC_MANAGER_TEMPERATURE` |
| `--manager-top-p` | `AGENTIC_MANAGER_TOP_P` |
| `--manager-generation-seed` | `AGENTIC_MANAGER_GENERATION_SEED` |

CLI 覆盖对应环境变量。`--manager-max-output-tokens` 和 `--manager-memory-max-output-tokens` 继续分别控制政策与日末记忆；两者使用同一 Manager 服务配置。

新入口默认设置：

```bash
export AGENTIC_MANAGER_BASE_URL=https://api.deepseek.com
export AGENTIC_MANAGER_MODEL=deepseek-flash
export AGENTIC_MANAGER_THINKING=disabled
export AGENTIC_MANAGER_RESPONSE_FORMAT=json_object
export AGENTIC_MANAGER_GENERATION_PARAMS='{}'
export AGENTIC_MANAGER_TOKEN_LIMIT_FIELD=max_tokens
export AGENTIC_MANAGER_TIMEOUT_SECONDS=300
```

这些变量也可直接用于原 Slurm 脚本；原脚本不会自动读取 DeepSeek 密钥文件，此时需要自行导出 `AGENTIC_MANAGER_API_KEY`。

直接使用 Java 时，两套参数可以同时存在，例如下面的离线检查：

```bash
java -Xmx8g -cp matsim-example-project-0.0.1-SNAPSHOT.jar \
  org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --policy llm --llm-agents all --manager \
  --demand 100 --sample-rate 0.2 --population-seed 6021 --seed 9261 --days 100 \
  --model qwen3.5-9b --base-url http://127.0.0.1:8002/v1 \
  --thinking omit --response-format json_schema \
  --generation-params '{"chat_template_kwargs":{"enable_thinking":false}}' \
  --manager-model deepseek-flash --manager-base-url https://api.deepseek.com \
  --manager-thinking disabled --manager-response-format json_object \
  --manager-generation-params '{}' --max-requests 40000000 \
  --validate-only --output output/joint-service-preflight
```

`127.0.0.1` 只有在 Qwen 与 MATSim 同节点时才可用于实际生成；新增 Slurm 入口会使用 Qwen 作业所在节点地址。离线检查不连接这些模型地址。

PowerShell 公共运行器也提供 `-ManagerModel`、`-ManagerBaseUrl`、`-ManagerThinking`、`-ManagerResponseFormat`、`-ManagerGenerationParams` 等对应参数，密钥仍通过环境变量传递。

## 完成后的核查

- `morning/manifest.json`：`llmSettings`、`memoryModelSettings` 应是Qwen；`managerSettings`、`managerMemorySettings` 应是DeepSeek；`managerServiceRouting` 记录独立服务配置；`pricing.chargingMode=full_day`。
- `manager-attempts.jsonl.gz` 与 `manager-memory-attempts.jsonl.gz`：实际模型、地址和生成参数应属于Manager。
- `decisions.jsonl.gz`、`memory-updates.jsonl.gz`：Traveler继续由Qwen完成，并保留自己的记忆。
- `status.json`：区分完成与中断。仍使用共享请求预算，分流不会自动增加预算。
- 收费是否发生以 `policy-applications`、`pricing.totalPaid` 和实际收据为准；`hold`空政策可能是合法决策，不能仅以API成功判断政策收益。

收费仍覆盖完整 QSim 日（当前场景 `[0,108000)`），Day0–19免费，之后每30天有政策决策机会。知情分组只涉及Manager政策公告；不额外提供实时路况、其他人的经历、不可见的当前收费或私人记忆。

本地回归通过模拟HTTP服务检查两个endpoint、两套密钥、四类请求、全天收费及旧共享配置，不需要真实DeepSeek密钥。
