# 第三方来源与数据署名

## MATSim 代码与模板

本项目继承 [matsim-org/matsim-example-project](https://github.com/matsim-org/matsim-example-project) 的 Maven 构建、原生运行示例与部分测试。保留根目录 `LICENSE`（GPL version 2）以及源文件中的原有版权声明；包含版本 2 或后续版本措辞的文件以其文件头为准。MATSim 及 contrib 库通过 Maven 依赖解析，不在源码目录中打包分发。

`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties` 来自 Apache Maven Wrapper，保留其 Apache-2.0 文件头，许可证全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。

`scenarios/equil/` 以及 `test/input/org/matsim/project/RunMatsimWithoutApplicationTest/test/` 的两份基准文件来自 MATSim example project 的公开示例/测试谱系。上游 README 将场景输入、输出和分析数据标为 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。两份缺失的测试基准在本次整理中从现有本地 MATSim 项目复制恢复，文件字节未修改；未声称它们与上游最新分支逐字节相同。

## Sioux Falls 输入

公开数据来源：Golan Ben-Dor，**Souix Falls 2014, Car Traffic with Doubled Population (Input for k = 1)**，Mendeley Data，version 3，2020-01-20，DOI [10.17632/2332kg48c8.3](https://data.mendeley.com/datasets/2332kg48c8/3)。来源页明确标示 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。

原场景研究：Chakirov, A. 与 Fourie, P. J.（2014），*Enriched Sioux Falls Scenario with Dynamic and Disaggregate Demand*。场景介绍见 [MATSim Sioux Falls](https://matsim.org/gallery/sioux-falls/)。该数据为合成测试场景，不代表真实城市居民的个体轨迹。

适用文件与本地修改：

- `scenarios/Souix Falls 2014/` 的 car-only 路网、设施和 200pct 人口沿用公开数据谱系。100pct 人口去除逐一验证的 `_new` 重复副本；转换脚本为 `scripts/restore_original_population.py`。
- `scenarios/SiouxFalls-AM/` 从 100pct 人口提取核心及缓冲早高峰单程计划；提取脚本为 `scripts/extract_morning_scenario.py`，统计与源数据摘要见 `extraction-report.json`。
- 配置文件适配 MATSim 2026 与本项目实验参数。历史 `.xml.orig` 作为配置参考保留；其本机绝对输出路径在本次整理中改为相对路径，不能作为逐字节原始配置。
- 固定收费 JSON 为本项目的实验示例，并非上游数据已经验证的收费政策。

本项目没有在整理过程中重新下载整套场景，以上归属依据现有项目的来源记录与公开来源页。下列 SHA-256 标识实际分发的本地文件，不等于已与 Mendeley 发布文件逐字节比对。分发派生数据时请继续保留作者、来源、许可链接与修改说明；CC BY 4.0 不意味着原作者认可本项目。

## 实际输入校验值

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `scenarios/SiouxFalls-AM/facilities.xml.gz` | 426022 | `6affe3962df39c1c1def993176298d199b7474dc9ebcf8aa53b64e2fa4319fbd` |
| `scenarios/SiouxFalls-AM/network.xml.gz` | 8532 | `5cf9741ced77122b0971f7c2294770ede9af8d649d14ba905b9afbcc227083fb` |
| `scenarios/SiouxFalls-AM/population-buffered.xml.gz` | 3615015 | `6cfd4ebdcd2e8308b4d8ceefddcd389c3fd47a0ac0f9053a53557626ba4b9c16` |
| `scenarios/SiouxFalls-AM/population-core.xml.gz` | 3534228 | `995e273df456d94250c49b66c530240ed4cc61584d9f05c3084f02a2b83982d5` |
| `scenarios/Souix Falls 2014/Siouxfalls_facilities.xml.gz` | 426022 | `6affe3962df39c1c1def993176298d199b7474dc9ebcf8aa53b64e2fa4319fbd` |
| `scenarios/Souix Falls 2014/Siouxfalls_network_Car.xml.gz` | 8532 | `5cf9741ced77122b0971f7c2294770ede9af8d649d14ba905b9afbcc227083fb` |
| `scenarios/Souix Falls 2014/Siouxfalls_population_100pct.xml.gz` | 4501400 | `d888a3db7e2823f4c02f60b6f69462726470d163efd7bfa42261667bb35c036b` |
| `scenarios/Souix Falls 2014/Siouxfalls_population_200pct.xml.gz` | 5840027 | `f5f2d716560e45606b2a38c1d95c82abce0beff82762d6f3831021f2c619707c` |
| `test/input/org/matsim/project/RunMatsimWithoutApplicationTest/test/output_events.xml.zst` | 15165 | `4ab3f6968a6569e20511f6c3677ce217b9f7b62ece41d14d3d9430fffe04193b` |
| `test/input/org/matsim/project/RunMatsimWithoutApplicationTest/test/output_plans.xml.zst` | 3116 | `fbd432ee260bc5cbfd9fa95d9b4a425ae9e0818ceec2be97c548186b27ea3add` |
