# Agent 评测体系（Evaluation）

> Agent 可观测性与评测的整体定位与关系见 [agent-observability.md](./agent-observability.md)。
> 本文整理自公司平台组「Agent Evaluation」文档，并补充 Grader 概念与 java-harness 落地映射。
> 生成日期：2026-09-16

---

## 一、什么是 Agent Evaluation

Agent 不只生成文本，它会在多轮交互中规划、调用工具、读取或修改外部状态，且**相同输入可能得到不同的执行路径和结果**。因此评估 Agent 不能只看最终回答是否「像正确」，还要验证它是否真正完成任务、是否遵守约束、是否稳定，以及成本和延迟是否可接受。

**定义**：Agent Evaluation 是一个面向决策的系统化测量过程——

> 给一个确定版本的 Agent 一组有代表性的任务，在受控环境中运行一次或多次，采集执行轨迹和最终结果，使用明确的评分规则判定成功与失败，再把结果聚合为可用于比较、回归和发布的质量证据。

评估的对象不是孤立模型，也不是一段最终文本，而是一套完整被测系统（SUT）：

- Agent 定义：模型与参数
- Prompt、上下文与记忆
- Tools、Skills、MCP 与知识资源
- 身份、权限与 Guardrails
- Agent Harness / Scaffold
- Sandbox 和外部业务环境

**只要任意一项变化**（切模型、改 Prompt、调工具权限、升级 Harness），评估结果就可能改变，因此评估报告必须记录被测配置与版本。

### 为什么需要 Evaluation

| 原因 | 说明 |
|---|---|
| Agent 非确定性 | 同一任务重复运行可能选不同工具/参数/路径，一次成功≠稳定成功。Evaluation 通过重复 trial 区分「偶然成功」与「可靠成功」 |
| Agent 会改变外部状态 | Agent 可能声称「退款完成」，真正要验证的是订单/退款记录是否已正确写入业务系统。**最终回复属于 trace，业务系统的最终状态才是 outcome** |
| 改动带来回归 | 换模型/改 Prompt 可能修复一个场景却破坏其他场景，固定用例集让候选版本与基线同条件比较 |
| 线上问题需可复现 | 生产 Trace 与反馈只能发现失败，需转成可重复运行的 Eval Case 在发布前重新执行 |
| 发布决策不能靠主观 | Evaluation 把成功率、可靠性、安全、成本、延迟分别量化，形成可审计的发布结论 |

### Evaluation 与 Observability 的关系

| 能力 | 回答的问题 |
|---|---|
| Metrics | 系统是否健康，错误率、容量、成本、延迟是否异常？ |
| Trace / Transcript | 某次运行发生了什么，Agent 为什么成功或失败？ |
| **Evaluation** | 什么算成功，这个版本是否足够好，是否可以发布？ |

> Observability 提供评估所需的证据；Evaluation 定义成功标准，并把证据转换成质量判断。二者可用同一套 Trace/Metrics 基础设施，但**不能互相替代**。

---

## 二、一套完整评估的七个部分

借鉴 Anthropic（task / trial / grader / transcript / outcome / suite）、OpenAI（Evaluation best practices）与 NIST（AI 800-2），完整 Agent Evaluation 归纳为七部分：

| # | 部分 | 回答什么 | 最小实现方式 |
|---|---|---|---|
| 1 | 目标与被测版本 | 为什么评、评哪个版本 | 固定 Agent/Profile、模型、Prompt、工具、权限、代码与环境版本 |
| 2 | Eval Case / Suite | 用什么代表真实业务 | Case 含输入、初始状态、预期结果、禁止结果、分类标签；Suite 是有版本的 Case 集合 |
| 3 | Trial Runner 与环境 | 怎样可复现地运行 | 初始化环境、启动 Session、等待结束、保存证据、恢复环境；同一 Case 可重复运行 |
| 4 | 证据采集 | 发生了什么，最终变成什么 | 同时保存 Trace、最终回复、数据库/文件/业务状态、耗时和成本 |
| 5 | Grader / Oracle | 什么算成功 | 确定性断言优先；主观质量用 rubric + LLM judge；高风险项人工校准 |
| 6 | 聚合与统计 | 整体成功率和稳定性 | 先得每个 trial 的明确 verdict，再按 Case 聚合成功率、pass@k、pass^k、区间和业务切片 |
| 7 | 决策与生命周期 | 能否发布，是否回归 | 候选与基线用同 Suite/协议；设硬门禁；持续加入生产失败案例 |

### 1. 目标与被测版本

先明确评估支持什么决策：是否达到上线要求 / 比较两个版本 / 验证问题已修复 / 衡量能力上限 / 监控质量漂移。同时**固定 SUT**，否则不同评估之间不可比较。

### 2. Eval Case 与 Eval Suite

**Eval Case** 是有明确输入与成功标准的业务任务，至少包含：

- 用户输入或任务描述
- 初始环境和测试数据
- 预期的最终结果
- 禁止出现的结果或副作用
- 必须遵守的安全和流程约束
- 业务类别、风险等级和难度

**Eval Suite** 是一组有版本的 Cases，应覆盖**正常路径、反例、边界、权限不足、工具失败、高风险操作、真实生产故障**，而非只放「演示案例」。

### 3. Trial Runner 与评估环境

**Trial** 是 Agent 对一个 Case 的一次独立尝试。Trial Runner（Evaluation Harness）负责：初始化干净可复现环境 → 装载固定版本 Agent → 启动 Session → 等待结束/超时 → 保存 Trace/结果/成本/错误 → 清理恢复环境 → 按协议重复运行。

> 多个 trial 之间**不能共享**文件、缓存或业务状态，否则失败可能来自环境、成功可能来自上一轮遗留数据。

### 4. 证据采集（四类证据）

| 证据 | 说明 |
|---|---|
| Trace / Transcript | 模型响应、工具调用、参数、工具结果、重试、错误、中间步骤 |
| Final Output | Agent 最终回复或提交的结果 |
| Outcome | trial 结束后文件/数据库/订单/工单等外部环境的真实状态 |
| Operational Metrics | Token、成本、耗时、工具次数、失败次数、资源消耗 |

> Trace 适合解释过程，Outcome 负责验证任务是否真的完成。**二者冲突时，以业务真实状态和预先定义的成功标准为准。**

### 5. Grader 与 Oracle

Grader 把 trial 证据转换为分数或判定，通常组合三种方式：

| 类型 | 适合评估 | 优点 | 局限 |
|---|---|---|---|
| 确定性 Grader（code-based） | 数据库状态、文件、测试、格式、预算、安全规则 | 快、便宜、可复现、易调试 | 对开放式质量和合法变体不够灵活 |
| Model-based Grader（LLM-as-a-Judge） | 语气、完整性、相关性、复杂任务、成对比较 | 能处理自然语言和主观 rubric | 也非确定性，需人工校准 |
| Human Grader | 高风险决策、专家质量、争议案例、校准集 | 最接近领域专家 | 慢、贵、难规模化 |

> 能用代码/业务状态明确验证时，**优先确定性 Grader**；只在质量主观或开放时用带清晰 rubric 的 LLM-judge，并定期与人工结果校准。

**Grader 与 Oracle 的区分**：Oracle 是「正确答案长什么样」（预期结果、标准），Grader 是「拿实际结果和 Oracle 比对的机制」。

### 6. 聚合、统计与不确定性

Grader 先对每个 trial 产生明确结果，再按 Case/Suite 聚合：每 trial 是否完成任务、每 Case 成功率、Suite 的 pass@1 / pass@k / pass^k、业务类别切片、安全违规率、成本延迟分布、候选相对基线变化、样本量与置信区间。

> **正确性、安全、成本、延迟分别报告**，不要强行混合成一个万能总分，也不允许低成本或高语言质量抵消严重业务错误。

### 7. 比较、发布门禁与持续改进

候选与基线用同 Suite/环境/协议。发布门禁可组合：关键业务与安全 Case 必须全过、pass@1 不低于基线、可靠性达标、关键业务切片不回归、成本与 p95 延迟不超预算、失败样本抽查确认 Grader 无误判。

上线后把新生产失败、用户反馈、人工复核持续补进 Suite，形成「生产证据 → 新 Case → 回归评估 → 发布」循环。

---

## 三、确定性 Grader 的权威分类

> 行业目前没有标准组织发布的「确定性 Grader 枚举表」。Anthropic 用 code-based grader 上位概念，OpenAI / Inspect AI / 各 Benchmark 提供不同 evaluator/scorer。下表是归一化后的实用分类，非强制标准。

**确定性 Grader 定义**：对固定版本的证据和配置，不调用另一个 LLM、不依赖人工临场判断、能重复得到相同结果的评分逻辑。

| 类别 | 它回答什么 | 典型实现方式 |
|---|---|---|
| 文本与模式匹配 | 输出是否包含/等于/避开某段内容 | exact、contains、regex、规范化后匹配 |
| 结构化输出与格式验证 | 输出是否合法 JSON、满足字段/格式契约 | JSON Pointer/JSONPath、schema、文件格式解析 |
| 可执行功能测试 | Agent 生成/修改的结果是否真可运行 | 单元测试、集成测试、fail-to-pass、pass-to-pass |
| Outcome / 最终状态验证 | Agent 是否真正完成业务任务 | 查询数据库/文件/订单/工单最终状态，与目标状态比较 |
| 工具调用验证 | 是否调了正确工具+正确参数 | required/forbidden tool、参数断言、次数与顺序 |
| 轨迹与 Transcript 约束 | 执行是否终止/循环/重试/违反流程 | 事件存在性、先后约束、步数、轮数、重试、终止状态 |
| 静态分析与安全策略 | 产物/行为是否违反工程、安全、合规 | lint、type check、security scanner、策略引擎 |
| 预算与运行效率约束 | 是否超资源预算 | Token、耗时、工具调用、失败调用、重试、成本上限 |

**优先级**：

```
真实 Outcome / 可执行测试 > 必须遵守的安全与业务约束 > 输出、工具、轨迹的辅助断言 > 预算与效率
```

> 不要默认要求 Agent 复现一条固定工具路径——只要结果正确且未违反安全/业务约束，不同有效路径通常都应接受（Anthropic 也建议优先评结果，而非过度约束路径）。

---

## 四、agent-session 的 Grader 现状（平台组实现）

> 基于 `agent-session@a6ec210f`。该实现把 Grader 限定为**纯确定性逻辑**：只能读已投影排序的 Trace、最终回复、后端汇总的 usage 数据，不调用 LLM、不执行阻塞式外部 I/O、不读隐藏 Chain-of-Thought。

| 行业分类 | agent-session 实现 | 覆盖 | 主要缺口 |
|---|---|---|---|
| 文本与模式匹配 | `output_match` | 较完整 | 支持精确/包含/排除/正则/JSON Pointer；无 fuzzy、BLEU/ROUGE |
| 结构化输出与格式验证 | `output_match` | 部分 | 可查 JSON 字段存在/相等；`json_schema`/`artifact_text`/`artifact_json` 未实现 |
| 可执行功能测试 | 无 | 未覆盖 | 不能跑测试脚本/命令/沙箱断言 |
| Outcome / 最终状态验证 | `workspace_result` | 仅预留 | 枚举存在但保存定义时拒绝；不能验证数据库/文件/业务最终状态 |
| 工具调用验证 | `tool_call_match` | 较完整 | 支持集合/顺序/次数/参数与输出字符串匹配；深层 JSON、subset、schema 未实现 |
| 轨迹与 Transcript 约束 | `trajectory_match`、`visible_reasoning_match` | 较完整 | 可查终止、事件顺序、循环、重试、可见计划 |
| 静态分析与安全策略 | `trajectory_match`、`tool_call_match` | 部分 | 可禁止工具/事件；不能代替 lint、type check、代码扫描 |
| 预算与运行效率约束 | `budget`、`trajectory_match` | 较完整 | 支持耗时/Token/工具次数/失败/重试；未直接计算货币成本 |

**准确结论**：当前实现是一套**针对已有 Session Trace 的确定性「输出、工具、轨迹与预算合规评分器」**，而不是完整 Agent Evaluation Harness。

> 它已能回答「这条轨迹是否符合规则」，但还不能单独证明「Agent 是否真正完成业务任务」。完整评估仍需 Case/Suite、可复现 Trial Runner，以及 `workspace_result` 或可执行测试这样的 Outcome Oracle。

### 能力边界怎么理解

上面那句限定（「只读已投影的 Trace / 不调 LLM / 不阻塞 I/O / 不读隐藏 CoT」），本质是把 Grader 定义成**纯确定性、纯函数式**的评分器。逐句拆解：

1. **「只读已投影的 Session Trace」**：Session Trace 是一次会话的完整轨迹（用户输入、模型输出、工具调用与结果）。「投影（projection）」指从原始事件流里筛选、映射、排序出 Grader 关心的字段，形成规范化视图（类比 SQL `SELECT` 挑列）。Grader 面对的是这个干净视图，而非原始杂乱数据。

2. **「最终回复 + 后端汇总的 usage」**：Grader 只读现成结果——Agent 最终输出文本 + 平台已聚合好的 token/耗时/成本汇总，不自己从原始事件重算。这三样（投影 trace + 最终回复 + usage 汇总）就是 Grader 的全部输入。

3. **「不调用 LLM」**：不做 LLM-as-Judge。评分是纯代码/规则，**同样证据永远得到同样判定**（确定、可复现）。

4. **「不执行阻塞式外部 I/O」**：不能查数据库、跑命令、调外部 API。这一条**直接排除了两类 Grader**——`workspace_result`（Outcome 验证要查真实业务状态）和可执行功能测试（要跑脚本）。这正是上表中两者「仅预留 / 未覆盖」的根本原因。

5. **「不读隐藏 Chain-of-Thought」**：模型可能有未暴露的隐藏推理，Grader 只能看**用户可见的推理**（`visible_reasoning`，如明确输出的计划/进度），不能读隐藏思考。

**合起来**：Grader 是一个纯函数——

```
输入（投影 trace + 最终回复 + usage 汇总）
   → 纯代码规则（不碰 LLM / 不做 I/O / 不读隐藏思考）
   → 输出（通过 / 失败 / 分数）
```

**好处**：快、便宜、可复现、易调试、无副作用（Grader 本身不污染结果、不额外烧钱）。
**代价（即其边界）**：只能判断「这条轨迹是否符合规则」（过程合规、预算、输出格式、工具调用约束），**不能判断「任务是否真正完成」**——后者需要查外部真实状态或跑测试，超出其边界。

> 一句话：它是一个「不联网、不调模型、只看已整理好的轨迹和汇总数据、纯代码判定」的**合规评分器**——能回答「有没有违规 / 超预算 / 输出对不对格式」，回答不了「有没有真正把事办成」。

---

## 五、如何衡量一个 Agent 是否足够好

「好 Agent」不是单一分数，至少需同时回答：

| 维度 | 典型指标 | 是否适合硬门禁 |
|---|---|---|
| 任务正确性 | 最终状态、任务成功率、pass@1 | 是 |
| 可靠性 | 多次 trial 成功率、pass^k | 高风险业务通常是 |
| 安全与合规 | 禁止操作、越权、数据泄露、策略违规率 | 是，严重违规通常要求为 0 |
| 过程质量 | 工具参数、循环、重试、必要审批、异常恢复 | 关键约束适合门禁 |
| 用户体验 | 有用性、完整性、语气、交互轮数 | 通常独立维度 |
| 效率 | Token、成本、平均与 p95 延迟 | 预算门槛，不抵消正确性 |

> 注：**P95（95 分位延迟）** = 把请求耗时排序后第 95% 位置的值，即「95% 的请求耗时 ≤ 此值」，用于盯尾部慢请求而非平均值。详细解释见 [agent-observability.md](./agent-observability.md) 的「术语速查：P95」。

### 先定义 trial 的成功

```
业务最终状态正确
  AND 没有发生严重安全或权限违规
  AND 所有 REQUIRED Graders 通过
  = 本次 trial 成功
```

可保留部分分数帮助诊断，但用于任务成功率和发布门禁时，应有**明确、可解释的成功/失败 verdict**。

### 业务人员怎么写 Eval Case（示例）

业务人员不写统计公式，只定义任务、初始状态和成功标准：

```yaml
id: refund-approved-order
name: 已批准订单退款
category: refund
risk: high
input:
  user_message: "请退掉订单 A1001"
initial_state:
  order_id: A1001
  status: APPROVED
  refundable_amount: 128.00
expected_outcome:
  order_status: REFUNDED
  refund_amount: 128.00
forbidden_outcome:
  - another_order_changed
  - duplicate_refund_created
process_constraints:
  required_tools: [get_order, create_refund]
  forbidden_tools: [delete_order]
  max_tool_calls: 8
quality_rubric:
  - 清楚说明退款结果
  - 不捏造到账时间
```

平台负责把规则编译为 Graders、重复运行 Agent、汇总指标并生成报告。

---

## 六、pass@k 与 pass^k

### pass@k：多次机会中至少成功一次

对第 i 个 Case 独立运行 n 次，其中 c_i 次成功（n ≥ k）：

```
pass@k_i = 1 - C(n - c_i, k) / C(n, k)
```

回答：**给 Agent k 次机会，至少成功一次的概率是多少？** Suite 先对每个 Case 计算，再对 m 个 Case 求平均：

```
Suite pass@k = (1/m) * Σ pass@k_i
```

直觉理解（每次成功概率相同且独立，记为 p）：`pass@k = 1 - (1-p)^k`。

> pass@k 适合系统真实允许多次尝试、生成多候选且能验证/选择正确结果的场景。若用户只有一次机会，主指标仍应是 pass@1。

### pass^k：连续多次都成功

面向工具调用与真实业务状态的 τ-bench 提出，衡量可靠性：

```
pass^k_i = C(c_i, k) / C(n, k)
```

回答：**连续执行 k 次全部成功的概率？** 直觉理解：`pass^k = p^k`。

**对比示例**（单次成功率 70%，运行三次）：

```
pass@3 = 1 - (1-0.7)^3 = 97.3%   ← 多试几次总能成功
pass^3 = 0.7^3          = 34.3%   ← 每次都可靠
```

> 这清楚说明「多试几次总能成功」与「每次都可靠」是完全不同的能力。

### 业务怎么选指标

| 业务形态 | 建议主指标 | 原因 |
|---|---|---|
| 用户提交一次任务并等待结果 | pass@1 | 反映真实一次尝试体验 |
| 系统允许自动重试且能安全验证结果 | pass@k + pass@1 | 衡量多次机会收益，同时不隐藏首次成功率 |
| 客服/订单/运维等要求每次稳定 | pass@1 + pass^k | 同时衡量平均成功率和连续可靠性 |
| 高风险、不可逆操作 | pass@1、pass^k、严重违规率 | 可靠性之外须设零容忍安全门禁 |
| 研究/代码/方案生成多候选 | pass@k | 产品本身可选至少一个可用结果 |

> 注意：pass@1/pass@k/pass^k 都建立在同一 Case 多次独立执行、环境可恢复、被测版本固定、trial 有明确 verdict 的基础上。**对同一条历史 Trace 重复打分不是新 trial。** 不同 Case 难度不同，必须「每 Case 先算、再对 Case 聚合」，不能先算全局平均成功率再代入公式。

---

## 七、一个最小可用的业务评估方案

第一版不需复刻大型 benchmark：

1. 选 20–50 个来自需求、人工测试、线上故障、高风险流程的 Case
2. 每个 Case 定义初始状态、预期结果、禁止结果、严重级别
3. 候选与基线用同一 Suite 和环境
4. 每个 Case 运行 3–5 个 trial
5. 主指标用 pass@1，稳定性用 pass^3
6. 严重业务错误、安全和权限违规要求为 0
7. 成本、平均延迟、p95 延迟单独报告
8. 人工抽查成功/失败 Trace，确认任务与 Grader 公平有效

> 这些数值只是启动方案，不是通用发布标准。随 Agent 成熟或风险升高，需增加 Case、trial 和统计严谨性。

---

## 八、行业是否存在统一标准

目前**不存在**类似 HTTP/JUnit 那样统一、强制的 Agent Evaluation 标准，也不存在通用总分公式。现有资料分四类：

| 类型 | 代表资料 | 能规范什么 | 不能直接提供什么 |
|---|---|---|---|
| 风险与治理框架 | NIST AI RMF 1.0、ISO/IEC 42001:2023 | 责任、风险容忍度、TEVV、文档、监控、持续改进 | Agent 的统一评分算法 |
| 评估实践指南 | NIST AI 800-2 IPD、OpenAI Evaluation best practices、Anthropic Agent Evals | 目标、数据集、运行协议、Grader、统计、报告 | 强制的通用发布阈值 |
| 质量与观测规范 | ISO/IEC 25059:2023、OpenTelemetry Semantic Conventions | AI 质量属性，Trace/Span 通用语义 | 任务是否完成、是否发布 |
| Benchmark 与论文 | HumanEval、τ-bench、SWE-bench | 特定任务的 Case、环境、Grader、指标设计 | 对所有业务 Agent 都适用的标准 |

**使用方式**：用 NIST AI RMF / ISO 42001 建责任与流程；用 NIST 800-2 检查评估可复现性；用 OpenAI/Anthropic 工程实践设计 Case/Trial/Grader；用 OpenTelemetry 统一证据采集；用 benchmark 学习某类任务如何构造可执行评测。**最终发布门槛由业务风险、用户体验和组织风险容忍度决定。**

---

## 九、java-harness 落地映射

把本文档映射到 UCT8086-AI 项目现状，形成可执行动作：

| 文档模块 | java-harness 现状 / 动作 |
|---|---|
| 证据采集：Trace/Transcript | 已有 `harness_message`（会话消息）+ `ToolCallRecord`（内存，需落库）+ `cost_usage`（usage）。**需先补 Trace 落库**（见 agent-observability.md） |
| 证据采集：Outcome | 短板。Agent 有 `bash`/`write_file` 工具，可做 `workspace_result` 验证文件/目录最终状态（比通用沙箱更聚焦） |
| 确定性 Grader | `ToolCallRecord` 含 `toolName/arguments/result/isError/durationMs`，直接对应 `tool_call_match` + `trajectory_match` + `budget` 的输入 |
| 预算 Grader | 已有 `CostTracker` + `cost_usage` + `maxTurns`，`budget` 几乎白送 |
| 聚合统计 pass@1/pass^k | 需 Trial Runner 重复跑同一 case（先能隔离 session 与初始状态） |
| LLM-as-Judge | 仅用于主观质量（语气、完整性），配 rubric + 人工校准 |

### 落地优先级

1. **P0 确定性 Grader（投入最小收益最大）**：在已有 `ToolCallRecord` + `cost_usage` + session 数据上，实现 `tool_call_match` / `trajectory_match` / `budget` / `output_match` 四个纯 Java 判定器（不调 LLM、无 I/O），立即回答「这条轨迹是否符合规则」。
2. **P1 Outcome 验证（真正难点）**：Agent 操作面是文件/命令，做轻量 `workspace_result`——运行前后对目标目录/文件做状态快照对比，才算真正「判断任务有没有完成」。
3. **P2 Trial Runner + pass@1/pass^k 统计**：20–50 个 case、每 case 3–5 次 trial，主指标 pass@1、稳定性 pass^3、严重错误=0，配 CI 定时回归。

---

## 十、参考资料

**方法与实践**

- OpenAI：Evaluation best practices、Graders API Reference
- Anthropic：Demystifying evals for AI agents
- UK AI Security Institute：Inspect AI Scorers / Custom Scorers
- NIST：AI 800-2（Automated Benchmark Evaluations of Language Models）、AI Risk Management Framework Core

**指标与 Benchmark**

- Chen et al.：Evaluating Large Language Models Trained on Code（HumanEval，pass@k 来源）
- Yao et al.：τ-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains（pass^k 来源）
- Jimenez et al.：SWE-bench

**标准与可观测性**

- ISO/IEC 42001:2023、ISO/IEC 25059:2023
- OpenTelemetry：Semantic Conventions

**工具与框架**

- Langfuse：Trace、数据集、实验、人工标注、在线/离线评估平台
- DeepEval：以测试用例、Trace、Metrics、CI/CD 为中心的评估框架
