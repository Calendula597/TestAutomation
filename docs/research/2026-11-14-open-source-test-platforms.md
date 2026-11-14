# 开源一体化测试平台调研

> 历史说明：本文形成后的技术栈调整已结束。经完整 `grill-me` 访谈，最终采用 Java 后端、Vue 3 + TypeScript 前端及按执行器划分的专业引擎；当前产品范围以 [PRD #1](https://github.com/Calendula597/TestAutomation/issues/1) 和票据 #2–#26 为准。开源项目事实与来源快照仍予保留。

调研日期：2026 年 11 月 14 日（Asia/Shanghai）。

## 结论

在本次核对的六个项目中，**未找到可以确认在开源、自部署版本中原生覆盖全部目标能力的单一产品**。这不等于市场上不存在，也不等于现有产品不能通过集成满足需求。

- **Testsigma 是功能范围最接近的候选**：官方仓库介绍涵盖 Web、Android、iOS、API 和自然语言自动化。但当前 README 混合了商业产品的 AI 宣传，不能据此认定 Copilot、Atto、Agent 等能力全部包含在公开源码中；GitHub 最新正式 Release 仍为 2023 年的 v3.0.1，需要进行实际构建与开源版本能力验证。
- **MeterSphere 是 Java + Vue 方向最值得对照的成熟候选**：测试管理、组织和项目、API 自动化较完整，当前介绍包含 AI 用例生成。但需要核对社区版和企业版边界，未确认其社区版原生覆盖原生移动端自动化及通用知识库。当前许可证含附加限制，不能简单写成标准 GPLv3。
- **组合现有工具也能实现目标**：测试管理平台 + Playwright + Appium + API 执行器 + MaxKB；代价是统一登录、项目权限、任务调度、报告、知识权限和用例格式需要自行打通。
- **建议本项目实现统一管理层，复用成熟执行引擎**。用户已选定 Java 后端 + Vue 前端；知识库采用全局共享知识和项目私有知识，并参与生成测试用例。先实现可运行的核心闭环，再扩展性能、安全、兼容性等测试类型。

## 需求口径

| 需求 | 本次判断标准 |
| --- | --- |
| 登录与权限 | 自部署环境中的用户认证和项目访问控制，而非仅厂商云账号 |
| 按项目区分 | 用例、环境、执行记录和项目知识按权限隔离 |
| 用例生成 | 从需求/知识生成测试设计；与“自然语言执行已有用例”分开判断 |
| Web E2E | 浏览器实际交互、断言、执行结果 |
| 移动端 | Android/iOS 原生应用自动化；移动浏览器模拟不算原生移动端 |
| API 自动化 | HTTP 请求、断言、场景执行和报告 |
| 通用知识库 | 文档知识管理、检索、共享/私有范围，并能用于生成用例 |
| 所有测试能力 | 不能视作有限的已完成清单；还包括性能、安全、可访问性、视觉、兼容性等，需要定义扩展边界 |

## 功能对照

“具备”表示官方材料明确描述该能力，不代表已在本地完整验收；“待核”表示开源版本归属、完整性或具体能力尚未确认；“需集成”表示不能由当前查阅的官方材料确认原生提供。

| 产品 | 登录/项目 | 用例生成 | Web E2E | 原生移动端 | API | 通用知识库 | 适合作用 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MeterSphere V3 | 具备组织/项目体系 | 官方介绍支持 AI；版本范围待核 | 具体版本/插件范围待核 | 未确认 | 具备 | 需集成 | 测试管理、API 中心 |
| Testsigma | 官方介绍具备团队权限；开源细节待核 | 商业宣传明确；公开源码范围待核 | 具备 | 官方介绍支持 Android/iOS | 具备 | 未确认 | 最接近跨端统一自动化，需 POC |
| Sonic | 移动设备与项目管理方向 | 未确认 | 非本次确认重点 | 具备 | 不作为主要依据 | 未确认 | 参考移动设备管理设计 |
| Testkube | OSS Agent 与商业控制面需区分 | 当前 AI 能力属于商业控制面介绍 | 可编排测试框架 | 可通过执行器集成，非内置设备平台 | 可编排 | 需集成 | Kubernetes 执行编排 |
| Hercules | 不是本次已确认的完整管理门户 | AI 驱动执行；不能等同需求到用例管理全链路 | 具备 | README 称 Appium 早期支持 | 具备 | 未确认通用知识库 | AI 执行 Agent/引擎参考 |
| MaxKB | 具体权限取决于产品版本 | 可以搭建知识驱动工作流；非专用测试平台 | 需集成 | 需集成 | 需集成测试执行器 | 具备 RAG、文档、工作流 | 独立知识服务 |

## 候选分析

### 1. MeterSphere

官方 README 将测试管理、API、AI 用例生成和组织/项目协作列为核心能力；技术栈包括 Spring Boot、Vue、MySQL 等，与本项目偏好相符。[1]

当前 V3 分社区版和企业版，V1/V2 已停止维护。不能把旧版本功能说明、插件能力或企业版功能直接当作 V3 社区版能力；选型前需对照官方版本对比，并实际启动社区版验证。[1][2]

许可证在 README 中称为 FIT2CLOUD Open Source License，描述为 GPLv3 加附加条件，包括 Logo/版权信息以及衍生作品义务。GitHub API 返回 `NOASSERTION`，因此本报告不把它归类为“无额外限制的标准 GPLv3”。具体复用以仓库 LICENSE 为依据。[1][3]

判断：如果主要需求是测试管理和 API，优先评估；若必须同时具备原生移动端和知识驱动生成，仍需开发集成。

### 2. Testsigma

官方仓库介绍包含 Web、移动 Web、Android、iOS、REST API、自然语言用例和扩展插件，是本次功能覆盖最接近的候选。[4]

当前 README 同时宣传 Copilot、Atto 和多个 AI Agent，并引导使用云服务。本次没有完整构建其公开源码，也没有证明这些新 AI 能力包含于 Apache-2.0 源码发行版。应把“产品具备”与“开源发行版可用”分开。[4][5]

GitHub API 的 latest Release 为 **v3.0.1（2023-08-24）**；仓库仍未归档，最近 push 为 2026-08-05。旧 Release 不能证明项目已经停止维护，但意味着不能用近期 README 推断旧安装包的能力。[5]

判断：值得做实际 POC，暂不能认定为已经覆盖全部要求的即装即用开源答案。

### 3. Sonic

Sonic 面向云真机和移动端测试，可作为设备管理、远程调试与移动自动化设计参考。[6]

`sonic-server` 官方仓库页面明确显示：**2025-03-25 被所有者归档，目前只读**。最新正式 Release 为 v2.7.2（2024-11-04），许可证为 AGPL-3.0。[6][7]

判断：不建议在本次新项目中未经维护风险评估就把它作为唯一核心底座；没有证据表明它同时解决通用知识库及全部跨端测试需求。

### 4. Testkube

当前 README 清楚区分开源 Agent 与商业控制面：开源 Agent 可独立运行于 Kubernetes；跨集群控制、集中界面和当前描述的 AI 工作流等由商业控制面介绍承载。[8]

README 将 Agent 描述为 MIT 授权，但仓库 API 的整体许可证自动识别为 `NOASSERTION`。应按实际复用目录的 LICENSE 核对，不能简单把整个商业平台理解为 MIT 开源。[8][9]

判断：适合已有 Kubernetes 的团队复用执行编排，不是本需求的现成完整门户；知识库和设备管理仍需集成。

### 5. TestZeus Hercules

Hercules 是 AI 测试 Agent，官方介绍包含浏览器、API、辅助工具、报告及部分非功能测试集成；README 对 Appium 原生自动化明确使用“early support”的表述。[10]

它适合作为 AI 驱动执行方向的参考，但本次没有确认完整登录、项目管理、通用知识库等管理平台能力。许可证为 AGPL-3.0。[10][11]

判断：可以研究执行能力，不能直接当作本需求的完整替代。

### 6. MaxKB

MaxKB 的核心是知识与智能体：文档导入、切分、向量化、RAG、模型接入和工作流；官方仓库声明 GPLv3。[12]

判断：适合做知识子系统候选，但本身不是 Web/移动/API 自动化执行平台。接入测试平台时，还需把项目知识权限、检索范围、生成来源和测试用例结构关联起来。

## 仓库状态快照

以下来自 2026-11-14 查询的 GitHub REST API。Release 为 GitHub `/releases/latest` 返回的正式版本，不等于所有分支、标签、预发布或商业产品的最新版本。`pushed_at` 只说明仓库发生推送，不能等同代码质量或稳定性。

| 仓库 | 正式 Release | 发布时间（UTC 日期） | 归档 | 最近 push（UTC 日期） | 许可证信号 |
| --- | --- | --- | --- | --- | --- |
| metersphere/metersphere | v3.6.10-lts | 2026-10-23 | 否 | 2026-11-09 | 自定义条款，API: NOASSERTION |
| testsigmahq/testsigma | v3.0.1 | 2023-08-24 | 否 | 2026-08-05 | Apache-2.0 |
| SonicCloudOrg/sonic-server | v2.7.2 | 2024-11-04 | 是 | 2025-03-24 | AGPL-3.0 |
| kubeshop/testkube | 2.15.2 | 2026-11-12 | 否 | 2026-11-13 | Agent README: MIT；API: NOASSERTION |
| 1Panel-dev/MaxKB | v2.10.8-lts | 2026-10-22 | 否 | 2026-11-13 | GPL-3.0 |
| test-zeus-ai/testzeus-hercules | 1.0.2 | 2026-08-03 | 否 | 2026-10-29 | AGPL-3.0 |

机器可读元数据见 [2026-11-14-repository-evidence.json](./2026-11-14-repository-evidence.json)。

## 推荐实施路线

用户已确认：**Java 后端 + Vue 前端；全局共享知识 + 项目私有知识；知识参与测试用例生成。**

1. 管理层：Spring Boot + Vue，用户登录、管理员、项目成员权限、环境配置、用例和执行记录。
2. 知识层：全局与项目文档、授权检索、生成来源引用。先提供可验证的文本检索与规则生成，再接入配置好的模型服务；不把规则模板称为 AI。
3. 执行层：HTTP 执行 API 测试；Playwright 执行浏览器 E2E；通过 Appium 执行 Android/iOS 原生测试。执行结果必须来自实际引擎，缺少设备/驱动时明确报错。
4. 闭环：需求输入 → 检索知识 → 用例草稿 → 人工核对并补齐环境/定位器/断言 → 执行 → 报告。
5. 扩展层：性能、安全、可访问性、视觉回归、跨浏览器矩阵、CI/CD、定时计划、分布式 Worker 与设备池。各项按实际实现及验收状态标记，不能宣称首版包含所有能力。

自建的理由是统一体验、权限和知识到测试闭环可控；代价是需要承担维护、升级和运行基础设施。若后续 POC 证明 Testsigma 或 MeterSphere 社区版更合适，可保留统一接口并改为集成对应平台。

## 调研方法与限制

- 主要来源为官方 GitHub README、LICENSE、Release/仓库 REST API，以及官方版本对比页面。
- 本次是资料和仓库状态调研，**未在本机部署并验收上述六个产品**。
- 功能表中的“未确认”表示本次未获得足够证据，不是断言产品一定不支持。
- 不使用商业产品网页的概括宣传证明公开源码具备同等能力。
- 不以星标数量或最近 push 时间代替功能、维护质量与安全评估。
- 许可证列是源码和官方声明的记录，不构成对具体商业使用方式的法律结论。
- 范围不覆盖市场全部项目；如选择采用某产品，应追加实际构建、用例执行、权限隔离和知识生成 POC。

## 官方来源

1. MeterSphere README：<https://github.com/metersphere/metersphere/tree/v3.x>；原文 <https://raw.githubusercontent.com/metersphere/metersphere/v3.x/README.md>
2. MeterSphere 版本对比：<https://metersphere.io/pricing.html>
3. MeterSphere LICENSE：<https://github.com/metersphere/metersphere/blob/v3.x/LICENSE>；Release API：<https://api.github.com/repos/metersphere/metersphere/releases/latest>
4. Testsigma 官方仓库：<https://github.com/testsigmahq/testsigma>；README：<https://raw.githubusercontent.com/testsigmahq/testsigma/dev/README.md>
5. Testsigma LICENSE：<https://github.com/testsigmahq/testsigma/blob/dev/LICENSE>；Release API：<https://api.github.com/repos/testsigmahq/testsigma/releases/latest>
6. Sonic 官方仓库及归档提示：<https://github.com/SonicCloudOrg/sonic-server>
7. Sonic Release API：<https://api.github.com/repos/SonicCloudOrg/sonic-server/releases/latest>
8. Testkube 官方仓库：<https://github.com/kubeshop/testkube>；README：<https://raw.githubusercontent.com/kubeshop/testkube/main/README.md>
9. Testkube 仓库元数据：<https://api.github.com/repos/kubeshop/testkube>；许可证：<https://github.com/kubeshop/testkube/blob/main/LICENSE>
10. Hercules 官方仓库：<https://github.com/test-zeus-ai/testzeus-hercules>；README：<https://raw.githubusercontent.com/test-zeus-ai/testzeus-hercules/main/README.md>
11. Hercules 仓库元数据：<https://api.github.com/repos/test-zeus-ai/testzeus-hercules>
12. MaxKB 官方仓库：<https://github.com/1Panel-dev/MaxKB>；README：<https://raw.githubusercontent.com/1Panel-dev/MaxKB/v2/README.md>

以上链接与仓库状态的访问日期均为 2026-11-14。
