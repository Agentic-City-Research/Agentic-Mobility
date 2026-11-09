# 收费观察审计账本

`morning/toll-observations.jsonl.gz` 是程序内部的逐次实际汽车路段进入观察账本，不是发送给旅客模型的公告。适用于存在收费处理器的运行，包括受控 LLM/MNL 无 Manager 免费期，以及启用 Manager 或固定收费的运行。只记录**属于 `information-scope` 的人**：即个人收费认知实际会更新的人，既包含知情者也包含不知情者。该范围之外的人的正收费仍记录在 `toll-payments.jsonl.gz`，不会出现在个人观察账本。背景人员是否在范围内由 `--information-scope` 决定；目标人员的非目标行程也被包含。

每次经过去重的实际 car 路段进入产生一行，包括金额为 0 的观察：

- `day` 和当日递增的 `observationSequence` 构成运行内关联键；还有 `personId`、`vehicleId`、`linkId`、`entrySeconds`、`amount`、`timeBinSeconds`、`timeBin`、`informationGroup`。
- `policyId`、`policyVersion` 仅用于研究者对账；不能将该账本作为模型 prompt 或让未获公告者读取最新政策。程序原有旅客可见信息边界不变。
- 已分组人员的正收费记录在 `toll-payments.jsonl.gz` 中包含同一天、相同 `observationSequence`，可与观察账本和 `PersonMoneyEvent` 对账。未分组人员的正收费不会有该序号。零观察不是付款，不产生 PersonMoneyEvent、不进入收费总额。
- 同一车辆、路段和时刻的重复入路通知只记录一次；离开路段后的再次进入作为新的观察和可能的新付款。跨日序号从 1 重新开始。
- 每日 `metrics.pricing` 中的 `personalObservationEntries`、`zeroPersonalObservationEntries` 可与观察账本计数对账；数据为流式压缩，但 100 日大样本会显著增加事件记录和存储开销，应实测。

`payment-memory-final.jsonl.gz` 只保存每个人/路段/时间箱的最终最近观察，而逐日 `decision-contexts` 保存决策实际可见的事实；这两类输出与该逐事件账本用途不同。没有收费处理器时不生成观察账本；不能据文件缺失断言所有旅客曾观察到零收费。与 MATSim 原始 events 联合核查时，还应检查 events 是否按该运行的写出间隔保存了所需日期。
