# 记忆连续性、Manager 反馈与输出校验

## 旅客决策和收费经验

LLM 旅客决策同时看到配置的近期经历，以及**上一次成功提交的摘要日之后、决策日前的全部未总结经历**。默认五日反思对应决策前五天的历史；若设置为每日反思且未显式指定历史窗口，默认窗口为一天。显式历史窗口可取 1–5 天。近期经历可能与摘要重叠，输入会注明日期。Java 旅客反思采用批量提交，反思批量失败会中断运行，不会只提交部分旅客的摘要。MNL 也使用该有效历史窗口估计旅行时间；收费认知仍使用自己的路段和时间箱最新观察。

知情组读取允许公开的当前收费，不知情组只读取个人经历的最近观察；未知路段/时间箱仍按零预期，实际经过仍按实际政策收费。`toll-observations.jsonl.gz` 包含零收费观察，其范围、关联键与隐私边界见 [收费观察审计账本](toll-observation-audit.md)。它是研究者内部账本，不会被整体提供给不知情旅客。

## Manager 反馈和约束

每日反思以及后续政策决策输入增加 `fullDayCarTravel` 汇总，涵盖全体计划汽车行程（含背景交通）的计划、出发、完成、中止、未完成和未出发数量。耗时从汽车出发到到达，包含进入路网前等待；`completedPreTrafficWaitPersonHours` 只统计完成且观测到入网事件的行程，不能当作全体等待总量。全部计划行程完成时 `carPersonHours` 才是完整总人时；否则为 `null`，`observedCarPersonHoursLowerBound` 是截至仿真结束已观测时间的下界，不包含未出发损失或失败惩罚。计划数与实际出发数无法匹配时，完成率为 `null`，并报告 `accountingExceedsPlanned`。

`basic` 的政策必须为有效有限非负金额、覆盖完整仿真时段、每路段最多一条规则且最多 40 条；**没有额外价格上限、涨价上限或自动撤回**。`controlled` 额外启用可配置的等效行程分钟收费上限、涨幅、走廊数、不可避免比例及自动审查/撤回机制；默认值以 `--help` 和运行时 `pricingControls` 为准。这些约束是实验设定，并非已校准的最优收费。两种配置都通过 `pricingControls` 明确报告启用情况。

## 完整性

写出最终成功状态之前，程序关闭所有自身产生的 JSONL 文件，读取整个 gzip 并检查 CRC/结束标记、末尾换行、实际行数和写入计数，同时对已完成天数、经历、受控决策与执行检查、收费观察等数量进行对账；生成 `morning/integrity.json`，记录存储文件的 SHA-256、字节数及行数。全部通过后才写出 `status.json` 中的 `complete: true` 和 `integrityVerified: true`。失败时记录失败状态；复用目录时先将状态标为运行中，避免保留前一次成功状态。验证全部日志会产生额外的磁盘和解压耗时，期间 `progress.json` 的 phase 为 `integrity_verification`。

复制或归档后可再次核验：

```powershell
python scripts/verify_run_integrity.py "output/pilot/manager-seed-1/morning"
```

返回码为 0 表示当前文件与保存的清单一致，非 0 表示不完整或不一致。检查范围是程序通过 OutputWriter 创建的 JSONL 流，不涵盖 MATSim 原始 events、单文档、语义正确性、身份唯一性或篡改者同时修改文件及清单的情形。若需要研究级对账，应进一步按 `day` 和 `observationSequence` 核对观察和实际付款，并核验实验设计及原始事件。
