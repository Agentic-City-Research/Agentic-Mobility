# 第三方来源与数据署名

## MATSim 代码与模板

本项目继承 [matsim-org/matsim-example-project](https://github.com/matsim-org/matsim-example-project) 的 Maven 构建谱系。保留根目录 `LICENSE`（GPL version 2）以及现有源文件中的版权声明；包含版本 2 或后续版本措辞的文件以其文件头为准。MATSim 通过 Maven 依赖解析，不在源码目录中打包分发。

`mvnw`、`mvnw.cmd`、`.mvn/wrapper/maven-wrapper.properties` 来自 Apache Maven Wrapper，保留其 Apache-2.0 文件头，许可证全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。

2026-10-15 发布整理移除了上游示例入口、equil 场景、相关测试基准及独立早高峰单程场景。它们的原始归属、校验值和使用说明仍保存在 [整理前的历史版本](https://github.com/Agentic-City-Research/Agentic-Mobility/blob/065f6cda9eab4dca7034bcfe07cae0e3a0bacf1c/THIRD_PARTY.md) 中；这些文件不再属于当前源码分发。

## Sioux Falls 输入

公开数据来源：Golan Ben-Dor，**Souix Falls 2014, Car Traffic with Doubled Population (Input for k = 1)**，Mendeley Data，version 3，2020-01-20，DOI [10.17632/2332kg48c8.3](https://data.mendeley.com/datasets/2332kg48c8/3)。来源页明确标示 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。

原场景研究：Chakirov, A. 与 Fourie, P. J.（2014），*Enriched Sioux Falls Scenario with Dynamic and Disaggregate Demand*。场景介绍见 [MATSim Sioux Falls](https://matsim.org/gallery/sioux-falls/)。该数据为合成测试场景，不代表真实城市居民的个体轨迹。

适用文件与本地修改：

- `scenarios/Souix Falls 2014/` 的 car-only 路网、设施和 200pct 人口沿用公开数据谱系。100pct 人口去除逐一验证的 `_new` 重复副本；转换脚本为 `scripts/restore_original_population.py`。
- `scenarios/SiouxFalls-public/config.xml` 适配 MATSim 2026 与本项目实验参数。历史 `.xml.orig` 作为来源配置参考保留；其本机绝对输出路径在此前整理中改为相对路径，不能作为逐字节原始配置。
- 固定收费 JSON 为本项目的实验示例，并非上游数据已经验证的收费政策。

本项目没有在整理过程中重新下载整套场景，以上归属依据现有项目的来源记录与公开来源页。下列 SHA-256 标识实际分发的本地文件，不等于已与 Mendeley 发布文件逐字节比对。分发派生数据时请继续保留作者、来源、许可链接与修改说明；CC BY 4.0 不意味着原作者认可本项目。

## 实际输入校验值

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `scenarios/Souix Falls 2014/Siouxfalls_facilities.xml.gz` | 426022 | `6affe3962df39c1c1def993176298d199b7474dc9ebcf8aa53b64e2fa4319fbd` |
| `scenarios/Souix Falls 2014/Siouxfalls_network_Car.xml.gz` | 8532 | `5cf9741ced77122b0971f7c2294770ede9af8d649d14ba905b9afbcc227083fb` |
| `scenarios/Souix Falls 2014/Siouxfalls_population_100pct.xml.gz` | 4501400 | `d888a3db7e2823f4c02f60b6f69462726470d163efd7bfa42261667bb35c036b` |
| `scenarios/Souix Falls 2014/Siouxfalls_population_200pct.xml.gz` | 5840027 | `f5f2d716560e45606b2a38c1d95c82abce0beff82762d6f3831021f2c619707c` |
