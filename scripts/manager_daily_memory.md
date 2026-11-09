# 每日 Manager 记忆与周期收费政策

当前入口：`org.matsim.agentic.MorningExperiment`。政策模板 `morning-pricing-v11-full-day-car`，反思模板 `morning-manager-memory-v2-full-day-car`，manifest schemaVersion=23。basic 与 controlled 均使用此记忆流程。

## 每日流程

1. 日开始：固定政策更新日读取截至前一日的公共反馈、旧记忆及当前有效政策，生成完整政策及决策理由。默认第 21、51、81……日；前 20 日免费。
2. 执行交通仿真与全天收费，保存实际接受的政策及版本、每日应用情况、交通结果和收费记录。
3. 日结束：独立反思调用读取当日已完成结果及执行事实，只返回新的管理记忆，不改变收费。
4. 验证通过后由仿真线程整份替换记忆，供下一日使用。政策更新日早晨不重写记忆；当天晚上结合实际结果反思。

第 21 日政策只能读取第 1–20 日结果；第 21 日晚才能反思新政策首日表现。免费观察期同样积累记忆。controlled 的自动保护仍独立执行，可能撤销政策或跳过处于冷却期的政策调用；这些事实进入实际执行记录。

## 输入与记忆边界

每日反思不重复完整路网或所有历史路段时窗。输入包含旧记忆及写入日、当前实际政策/版本、最近接受决策、公共环境参数、当日指标、近 7 日及此前 7 日趋势、当前同价政策连续观察、免费期参考、最近两段同价政策经历与更早观测汇总。

新增观测覆盖上次成功记忆更新后的全部已完成日；即使中间发布了政策也不丢弃缺失日期。道路统计保留前 20 条延误道路、前 10 条失败道路，以及当前收费和近期撤销收费道路；未列出不能解释为畅通。金额和完整政策由结构化输入提供，记忆不维护精确执行台账。

同价政策经历使用实际价格函数指纹划分，相同收费的 hold 或新版本不会重置试验天数。全网指标、完成率、变化率及实际总收费收入由程序计算。计数与分母保留，未报告指标为未知。完成全路段的旅行/延误时间不等于 OD 全程时间；中止、未完成和部分路段另列。

记忆目标 400–600 英文词，硬上限 1000 词，不要求最小词数。建议组织为交通状态、政策观察结果、已有经验、不确定性、下次更新关注点。整份替换，不追加逐日日记；保留近两次政策经验，对早期经验作摘要；新证据冲突时修正旧判断。

提示词要求结合完成率和失败情况解释延误、检查其他道路的拥堵转移，将首日效果视为初步证据。前后比较属于描述性反馈；原生学习、适应和背景波动可能解释变化，不宣称匹配反事实的因果效果。记忆中的未来建议仅供固定政策更新日考虑。

## 输出、失败与预算

- 政策输出：仅 `rules`、`decision`；规则仍为 `link_id/start/end/amount`，start=0、end=QSim endTime。每日记忆不简化收费接口。
- 反思输出：仅 `memory`，拒绝规则、决策、额外键、重复键、尾随 JSON、空字符串、超过 1000 词及截断响应。
- 输出错误允许有限修复；API 错误、预算耗尽或反思超时保留旧记忆并写入失败日志。超时取消 worker；worker 不修改 live memory，仿真线程负责提交，迟到返回不会生效。
- 下一次反思提供上次成功写入以来的汇总，不依赖旧记忆猜测缺失日期。
- `--manager-memory-max-output-tokens` 默认 2048，独立于政策输出默认 8192；两种调用共享模型、服务和采样设置。token 上限与词数上限不同，输出不足会导致修复或保留旧记忆。
- `--max-requests` 同时覆盖 Traveler 选择/反思、Manager 政策/每日反思和输出修复。100 日默认至少 103 次 Manager 调用，建议给重试留余量；这是调用预算，不是服务端 token 预算。
- validate-only 不调用模型；正式 manager 运行从第一天起需要 API key 和可用服务。只启用固定收费文件的实验不新增 Manager 反思。

## 复现档案

`morning/` 下的 JSONL 使用 gzip：

| 文件 | 内容 |
| --- | --- |
| manager-memory-contexts.jsonl.gz | 每日日末实际输入，包含执行政策、事实汇总、旧记忆与缺失日期范围 |
| manager-memory-attempts.jsonl.gz | 系统提示词、输入、schema、请求/上下文哈希、生成设置、响应、token/cache 计数、耗时及校验 |
| manager-memory-updates.jsonl.gz | 新旧记忆、写入日、成功或保留状态、词数、上下文哈希与调用数 |
| manager-memory-fallbacks.jsonl.gz | 反思失败、超时或预算不足及保留旧记忆的记录 |
| manager-decisions.jsonl.gz / policies.jsonl.gz | 接受的完整政策、决策理由与执行版本 |
| policy-applications.jsonl.gz | 每日实际生效的政策 ID、版本、指纹及自动回退事实 |

manifest 保存独立的 `managerMemorySettings`、`managerMemoryLifecycle` 和 `minimumManagerMemoryCalls`；原 `minimumManagerCalls` 仍表示预定政策调用数。API 使用记录另列政策/管理反思调用数及总预算计数。模型记忆无法代替完整执行档案。

## DeepSeek JSON 模式与 HPC 兼容

使用 DeepSeek 的 `--response-format json_object` 时，Manager 政策和每日反思会在原系统提示词后追加 `morning-json-object-v1` 输出约束：明确要求一个完整 JSON 对象，给出本次调用的字段 schema，禁止把 `type`、`response_format` 等包装信息当成额外输出，保留原有枚举和字符/词数限制。这满足服务端要求提示词明确包含 JSON 的前置条件；JSON 模式本身不保证业务字段合法，程序仍严格校验，不自动删改模型回答或强制收费。

追加后的实际 system/user、字符数和 requestHash 都写入 attempts 日志。该适配在请求发送前完成，不在 HTTP 层偷偷改写提示词。

只有实际请求使用 `json_object` 才追加约束。HPC/vLLM 常用的 `json_schema` 和 `omit` 保持原系统提示词及生成参数；不自动切换模型、地址、thinking 模式或 response format。现有 Slurm 脚本无需修改，继续使用该服务支持的原参数。修复后需重新构建并将新 JAR 同步至实际运行主机；仅上传 Java 源码不会更新正在使用的 JAR。

本地无外部 API 回归覆盖 JSON 模式下跨日记忆传递、政策读取旧记忆、全天规则、真实 HTTP 请求与日志一致性，以及 basic/controlled 的 json_schema/omit 请求兼容。真实模型仍可能产生不合格输出，沿用原有限重试和失败审计机制。
