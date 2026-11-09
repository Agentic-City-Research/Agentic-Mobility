# Java v9 结构化账本：使用与限制

主实验入口 `MorningExperiment` 增加 `--traveler-memory-variant`：

| 值 | 反思提示 | 决策提示 |
|---|---|---|
| `original`（默认） | 旧摘要和近期经历 | 原版近期经历和摘要 |
| `dedup-prompt` | 原版反思增加按日去重提示 | 原版近期经历和摘要 |
| `ledger` | 近期经历、摘要和截止当日账本 | 近期经历、摘要和截止前一日账本 |

账本由该目标行程 `MorningMemory` 的实际经历生成；不包含长期收费信息。空到达且未明确 stuck 归为未知，迟到缺失不算准时。账本的累计值已经包含近期行程，不能再次相加。

构建当前代码：在项目根目录运行 `./mvnw.cmd -DskipTests package`。可先参考 `docs/getting-started.md` 中现有 Java 旅客命令，在其中添加 `--traveler-memory-variant ledger`，使用独立输出目录；同样的命令将参数替换为 `original` 或 `dedup-prompt` 可运行其他条件。API 密钥仍由 `AGENTIC_API_KEY` 环境变量提供，不要写入命令或文档。建议先用 `--validate-only` 检查配置。此模式要求 Java LLM traveler，当前禁止 Python 后端及 `--traveler-semantic-review`。

输出 `morning/manifest.json` 记录变体；`morning/ledger-views.jsonl.gz` 逐次记录旅客、行程、日期、决策或反思阶段、账本视图及其哈希。实际提示保存在既有的调用日志中，原始经历保留在既有执行记录中。`asOfDay=-1` 表示首日决策之前没有历史。

注意：主入口运行的三组闭环轨迹可能自行分化，不是相同事实序列的反事实比较。既有 `scripts/run_v9_pilot.ps1` 和 `V9ReasonablenessExperiments` 的命令行尚未提供新变体、固定源摘要重放或 H/M/L 重复分支调度；不要在该脚本添加上述主入口参数并假定生效。内部已支持账本可见性分支，但要完成预定的 30 次固定源摘要链及 72 次平衡分支实验，还需专用回放入口。结构化账本本身无法保证模型正确理解或正确引用其中事实。
