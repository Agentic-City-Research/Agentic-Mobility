<div align="center">

<h1>Agentic Mobility</h1>

<p><strong>面向 LLM 出行者与交通管理者交互的开放研究平台</strong></p>

<p>
  <a href="#quickstart"><img src="https://img.shields.io/badge/Java-25-blue?style=flat" alt="需要 Java 25"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--2.0-blue?style=flat" alt="许可证：GPL-2.0"></a>
</p>

[English](README.md) | 中文

AgenticMobility 是 [AgenticCity](https://github.com/Agentic-City-Research) 的交通研究项目，以交通为起点，探索智能体与城市系统的持续交互。

🚀 [快速开始](#quickstart) · 🗺️ [文档](#documentation) · 🎯 [研究问题](#research-questions) · 🤝 [参与社区](#community)

</div>

<a id="overview"></a>

## 🧩 项目概览

![AgenticMobility 框架：出行者选择路线与出发时刻，管理者设置拥堵收费；个人行程结果和公共交通统计分别进入两类主体的记忆。](docs/assets/mobility-loop-homepage.svg)

AgenticMobility 将 LLM 出行者、LLM 交通管理者与共享的 MATSim 交通环境连接起来，用于研究出行选择、记忆更新和政策响应。当前实现基于增强的 Sioux Falls 场景，支持路线与出发时刻联合选择、每日反思和周期性拥堵收费，并保留背景交通及全日活动链。

<a id="how-it-works"></a>

## 🔄 运行机制

每个模拟日依次完成决策、交通执行和反思；管理者在预定日期调整收费政策。

```mermaid
flowchart LR
    A["🧩 一次性准备<br/>固定人口、分组与候选动作"]
    B["🧭 决策<br/>选择路线与出发时刻<br/>按计划更新收费政策"]
    C["🚗 执行<br/>运行全日交通仿真"]
    D["🪞 反思<br/>各角色更新自身记忆"]

    A --> B --> C --> D
    D -->|下一个模拟日| B

    style A fill:#f1f5f9,stroke:#94a3b8,color:#2563eb
    style B fill:#eff6ff,stroke:#60a5fa,color:#2563eb
    style C fill:#f0fdfa,stroke:#2dd4bf,color:#2563eb
    style D fill:#faf5ff,stroke:#c084fc,color:#2563eb
```

LLM 控制选定的目标行程，其他行程按 MATSim 原生行为机制运行。出行者和管理者分别依据个人经历与公共反馈更新各自的记忆；适应过程通过模型推理和记忆更新实现，模型权重保持不变。

<a id="research-questions"></a>

## 🎯 研究问题

| 研究方向 | 可采用的比较设置 |
| :--- | :--- |
| 📊 **出行者如何适应？** | 原生交通基线 vs. LLM 控制目标行程 |
| 🔎 **信息权限如何影响选择？** | 知情 vs. 不知情受控出行者 |
| 🚦 **不同收费政策如何影响交通？** | 无收费 vs. 固定收费 vs. LLM 动态调价 |
| 🔄 **出行者与管理者如何相互响应？** | 仅 LLM 出行者 vs. LLM 出行者与管理者联合实验 |

这些设置用于组织研究比较，具体实验需匹配人口、场景和背景交通等条件。实验模式与命令见[实验配置指南](docs/experiments-and-evaluation.md)。

<a id="quickstart"></a>

## 📦 快速开始

需要 **JDK 25**（包含 `javac`）和 Git。首次构建时，Maven Wrapper 会下载 Maven 与所需依赖。以下命令均从仓库根目录执行。

**获取源码并构建**

```bash
git clone https://github.com/Agentic-City-Research/Agentic-Mobility.git
cd Agentic-Mobility
sh ./mvnw -B clean package
```

Windows PowerShell 中使用 `.\mvnw.cmd -B clean package` 构建。

**运行不调用模型 API 的入门示例**

下面的命令运行一个包含 100 名原生出行者、持续两个模拟日的诊断基线。

```bash
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment \
  --config scenarios/SiouxFalls-public/config.xml \
  --mode hybrid --policy mnl --llm-agents 0 \
  --demand 100 --sample-persons 100 --days 2 \
  --population-seed 4711 --seed 4711 \
  --output output/quickstart
```

<details>
<summary>Windows PowerShell 运行命令</summary>

```powershell
java -Xmx4g -cp matsim-example-project-0.0.1-SNAPSHOT.jar org.matsim.agentic.MorningExperiment `
  --config scenarios/SiouxFalls-public/config.xml `
  --mode hybrid --policy mnl --llm-agents 0 `
  --demand 100 --sample-persons 100 --days 2 `
  --population-seed 4711 --seed 4711 `
  --output output/quickstart
```

</details>

运行成功后，程序实际输出目录中的 `morning/status.json` 应包含 `complete=true` 和 `daysCompleted=2`。输出目录重名时，程序会创建带编号的新目录，请以控制台报告的路径为准。

该 100 人示例用于检查安装与运行流程；`--sample-persons` 不会同步缩放路网容量。正式研究配置请参照[采样与容量设置](docs/scenarios-and-data.md#sampling-and-capacity)。

**接入模型 API**

运行 LLM 出行者、LLM 管理者或联合实验前，请按照 [API 接入指南](docs/getting-started.md#5-connect-an-existing-model-service) 配置 `AGENTIC_BASE_URL`、`AGENTIC_MODEL` 和 `AGENTIC_API_KEY`。配置完成后，平台会在决策和反思等阶段自动调用兼容 Chat Completions 的服务。MATSim 在 CPU 上运行，模型服务可部署在其他机器。

安装、故障排查和运行说明也可参考[中文操作指南](docs/usage-guide.zh.md)。

<a id="documentation"></a>

## 📖 文档

| 指南 | 主要内容 |
| :--- | :--- |
| [快速上手](docs/getting-started.md) | 环境安装、首次运行、API 配置与故障排查 |
| [方法与协议](docs/methods-and-protocols.md) | 主体角色、动作范围、信息权限、记忆与收费机制 |
| [实验与评估](docs/experiments-and-evaluation.md) | 当前提供实验模式、配置与运行记录；具体结果和评估后续补充 |
| [场景与数据](docs/scenarios-and-data.md) | 场景输入、数据来源、人口采样与扩展要求 |

以上四篇专题指南目前为英文。完整入口见[文档索引](docs/README.md)，中文说明见[操作指南](docs/usage-guide.zh.md)。

<a id="project-status"></a>

## 🧪 项目进展

| 方向 | 当前进展 |
| :--- | :--- |
| 功能实现 | 已实现出行决策、私人记忆、管理者收费政策与执行过程记录。 |
| 实验研究 | 探索性运行正在分析，多随机种子比较正在准备。 |
| 研究论文 | 正在撰写。 |

经核实的实验配置和结果将随代码版本、模型设置、随机种子及评估方法一并发布。

<a id="community"></a>

## 🤝 社区与贡献

欢迎关注智能体、交通、城市仿真与政策研究的研究者和开发者参与：

- 报告安装、运行或复现问题，完善文档与示例。
- 分享记录完整的实验配置、基线方案或方法比较。
- 开发分析工具、可视化和新的交通场景。

可通过本项目的 [GitHub Issues](https://github.com/Agentic-City-Research/Agentic-Mobility/issues) 提问、反馈或讨论贡献计划。[贡献指南](CONTRIBUTING.md) 介绍了当前参与入口、开发检查和实验报告要求。

<a id="citation-and-license"></a>

## 📚 引用与许可证

用于研究时，请注明 [Agentic-City-Research/Agentic-Mobility](https://github.com/Agentic-City-Research/Agentic-Mobility)，并记录具体提交版本、场景、模型和实验设置。论文公开后将补充正式引用信息。

代码采用 [GPL-2.0](LICENSE) 许可证。第三方代码与数据保留各自的使用条款，来源和说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。本项目基于 MATSim 及其示例工程构建。
