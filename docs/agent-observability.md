# Agent 可观测性与监测（Observability）

> 本文聚焦「怎么发现 Agent 有问题、怎么判断好不好用」的可观测性/监测部分；「什么算成功、如何打分」的评测体系见 [agent-evaluation.md](./agent-evaluation.md)。
> 生成日期：2026-09-16

---

## 一、定位：可观测性与评测是两条腿

| 能力 | 回答的问题 | 本项目对应物 |
|---|---|---|
| **Metrics** | 系统是否健康，错误率/容量/成本/延迟是否异常 | `ChatMetrics` + Prometheus |
| **Trace / Transcript** | 某次运行发生了什么，为什么成功/失败 | `harness_message` + `ToolCallRecord`（需补落库） |
| **Evaluation** | 什么算成功，这个版本是否足够好、可否发布 | 缺失，见 agent-evaluation.md |

LLM Agent 是**非确定性**系统，所以光靠「报错日志」远远不够——很多时候它**没有异常却跑错了**（幻觉、误用工具、答非所问）。因此 Agent 观测 = 传统链路追踪 + **质量追踪**。

---

## 二、观测维度

传统后端三大支柱 `Metrics/Logs/Traces` 依然适用，但对 Agent 需扩展：

| 维度 | 传统后端 | Agent 额外要看的 |
|---|---|---|
| Trace（调用链） | 服务间 RPC | 每一步推理链、ReAct 循环、工具调用与返回值、多 Agent 消息传递 |
| Metrics（指标） | QPS/延迟/错误率 | 每步延迟、Token 消耗/成本、工具调用成功率、空回复率、重试率 |
| Logs（日志） | 文本日志 | 带语义的 prompt/上下文记录、中间思考、模型版本 |
| Eval（评测） | 单测/回归 | 端到端任务成功率、离线评测集跑分、线上抽样 LLM-as-Judge 打分 |

---

## 二点五、术语速查：P95（95 分位延迟）

**P95（也叫 95 分位 / 第 95 百分位）**：把所有请求耗时**从小到大排序**，排在第 95% 位置的那个值。

```
比如 100 次请求耗时排序后，第 95 个（第 95% 分界处）耗时 = 800ms
→ 记作「P95 延迟 = 800ms」，即 95% 的请求耗时 ≤ 800ms
```

**为什么不用平均值**：平均值会被极端值带偏。

```
10 次请求：100 × 9 次 + 5000 × 1 次
平均值 = 590ms   ← 被那 1 次 5 秒拉高，看起来还行
P95    = 100ms   ← 更真实反映「大多数用户体验」
```

若只报平均值，会看不到尾部那 5% 的慢请求——而这些慢请求恰恰最影响体验。P95 就是专门盯住这个「尾部」。

**常见分位**：

| 指标 | 含义 |
|---|---|
| P50（中位数） | 一半请求比这快、一半比这慢 |
| P95 | 95% 请求 ≤ 此值，盯「多数人的最差体验」 |
| P99 | 99% 请求 ≤ 此值，盯「极少数最慢的请求」 |

**在 Agent 场景的用法**：文档中「P95 延迟不超过预算」作为发布门禁，意思是**不是看平均多快，而是看 95% 的会话/首 token 响应都要在可接受范围内**——保证绝大多数用户不卡，而不是被几个快请求把平均数刷好看。

- **首 token 延迟的 P95**：95% 的请求里，从发起到出现第一个字，耗时都 ≤ 某个阈值。
- **单任务耗时 P95**：95% 的任务在预算内完成。

> 一句话：P95 = 排序后第 95% 位置的值，用来衡量「大多数情况下的最坏体验」，避免被平均值掩盖尾部慢请求。

---

## 三、如何发现 Agent 有问题（四类信号）

按「是否要人工介入」排序：

1. **硬故障（必报警）**：模型调用超时/限流、工具抛异常、死循环（ReAct 步骤超阈值）、Token 超限、成本单次飙升。
2. **性能劣化（趋势告警）**：P95 延迟上升、Token 异常增长、某工具失败率升高、缓存命中率下降。
3. **质量劣化（最难发现）**：某类任务成功率下降、用户负反馈上升、LLM-as-Judge 抽样打分下降、prompt/数据分布漂移。
4. **安全合规**：PII 泄露、注入攻击、敏感操作（资金、删除）触发审计。

**推荐落地方式**：全链路埋点（Trace + 每步 Token + 成本）→ 关键任务在线回归评测 → LLM-as-Judge 线上抽样评分配阈值告警 → 所有质量告警都能下钻到单条 Trace 复现现场。

---

## 四、如何判断一个 Agent 好不好用（分层量化）

1. **任务层**：端到端任务成功率、目标完成度。
2. **质量层**：事实性（幻觉率）、相关性、忠实度、有用性、无害性；LLM-as-Judge + 少量人工标注黄金集校准。
3. **工具/过程层**：工具选择正确率、多余/漏调、步骤效率（平均步数）。
4. **体验/成本层**：首 token 延迟、总时长、单任务成本、Token 效率。
5. **稳定性层**：同输入多次运行的一致性/方差（非确定性系统的特有指标）。

评测分**离线（Benchmark 集 + 回归）**和**在线（真实流量抽样 + 用户反馈）**两条，缺一不可。详见 [agent-evaluation.md](./agent-evaluation.md)。

---

## 五、java-harness 现状盘点

| 能力 | 现状 | 代码位置 |
|---|---|---|
| 指标 Metrics | ✅ 6 个 Prometheus 指标（请求/错误/耗时/输入输出 token/工具调用/turn） | `metrics/ChatMetrics.java` |
| 成本 Cost | ✅ 明细落库 MySQL `cost_usage`，按 user/session 聚合 + 熔断 + 告警 | `core/cost/CostTracker.java` |
| 会话/消息 | ✅ `harness_session`/`harness_message` 持久化 | `core/session/SessionManager.java` |
| 结构化日志 | ✅ PROMPT-BUILD / SKILL-INJECT / MEMORY-INJECT / RAG-SEARCH 全链路打点 | `core/engine/AgentEngine.java` |
| Trace 雏形 | ⚠️ `ToolCallRecord` 只存内存、用完即弃，`result`/`durationMs` 未填 | `core/engine/HarnessToolCallingAdvisor.java` |

### 三个缺口

**缺口 1：Trace 没落库 → 无法事后下钻「这次会话为什么跑错」**

`HarnessToolCallingAdvisor.java` 中工具调用记录 `result=null`、`isError=false`、`durationMs=0`（均未真实填充）；`AgentEngine.executeInternal` 拿到 `toolCallRecords` 后只打日志和统计个数，**没有落库**。结果：会话历史有、成本有、指标有，但「每一步工具调了什么、入参出参、谁成功谁失败」这条链路是断的。

**缺口 2：质量评测完全空白**

`ChatMetrics` 只有性能和成本，没有任何「答案对不对」的指标，抓不到「没报错但答错」这一类问题。

**缺口 3：告警没闭环**

指标有了但没接 Alertmanager/Grafana；成本告警是 `log.warn/error`（`CostTracker.fireAlert` 只发 Spring Event），没有推到人/群的通道。

---

## 六、方案推荐

核心约束是**纯 Java 21 + Spring AI 2.0**：主流开源观测平台（Langfuse/Phoenix/Opik）的 SDK 主力是 Python/JS，Java SDK 都很弱。因此最务实的路径是**补齐自建观测**，外部平台作为可选增强。

### 路线 A（推荐主路线）：补齐自建观测

**A1. Trace 落库（补缺口 1）**

新增 `agent_trace` 表（MyBatis-Plus，复用持久化模块），每轮 agent loop 写一条：

```sql
CREATE TABLE agent_trace (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id     VARCHAR(64),
  user_id        BIGINT,
  turn_no        INT,              -- 第几轮
  prompt_snapshot TEXT,            -- 组装后的 system prompt（截断存）
  user_input     TEXT,
  model_output   TEXT,             -- 该轮模型输出
  token_in       INT,
  token_out      INT,
  tool_calls     JSON,             -- [{name, arguments, result, isError, durationMs}]
  latency_ms     INT,
  success        TINYINT,
  error_msg      VARCHAR(512),
  created_at     DATETIME
);
```

改造点：在 `AgentEngine` 两条执行路径（`executeInternal` 与 `executeInternalStream` 的 `doOnComplete`）把 `toolCallRecords` + prompt + 输出 + token **持久化**；同时补上 `HarnessToolCallingAdvisor` 里 `result`/`durationMs` 的真实值。

**A2. 可视化 + 告警闭环（补缺口 3）**

- Grafana（开源）接 `/actuator/prometheus`，建三张面板：请求/错误/延迟、Token 与成本趋势、工具调用分布（按工具名）。
- Alertmanager 配阈值告警：错误率 > 5%、P95 延迟超阈值、单用户成本超限、工具失败率升高。
- 把 `CostTracker.fireAlert` 从「发 Spring Event」扩展为真正推送到钉钉/企业微信/邮件（复用 `addAlertListener` 钩子加 webhook listener）。

**A3. 质量评测（补缺口 2）**

项目已有 `query_local.py`/`scan_dbs.py`/`scripts/` 等 Python 脚本，团队不排斥 Python。离线评测用 Python + DeepEval（或 Ragas）：建评测集 → LLM-as-Judge（接 DeepSeek）对事实性/相关性/工具选择打分 → CI 定时回归，分数下降即告警。详见 [agent-evaluation.md](./agent-evaluation.md)。

### 路线 B（可选）：引入成熟开源平台

| 方案 | 接入方式（Java 侧） | 适合理由 |
|---|---|---|
| Arize Phoenix | OTLP-native，用 OpenTelemetry Java SDK 埋点上报 | Java 生态最标准路径，可叠加自动埋点 + 手动 Span |
| Langfuse（自托管） | REST API（`/api/public/ingestion`）或社区 Java SDK | 功能最全（Trace + Eval + Prompt 版本 + 成本），但 Java SDK 弱，需自封装上报客户端 |

**建议**：先走 A 补齐 Trace 落库和告警；后期需漂亮 Trace 面板、Prompt 版本管理、团队协作评测时，再引入 Langfuse 自托管，用 REST API 把 `agent_trace` 数据同步一份过去，两者不冲突。

---

## 七、平台组方案参考（事件溯源式 Trace）

平台组的「会话性能洞察 / 质量评估」采用的是**自建事件溯源 + 聚合分析 + 规则诊断**，没有用现成开源平台。其四层设计：

**1. 事件溯源采集（Event Sourcing）**

关键证据：右上角「事件数 1,279」「事件/秒 7.7」——不是按消息记录，而是把会话中**每一个事件**（流式 chunk、工具调用、权限请求、轮次边界）都带时间戳落库，指标全部**事后从事件流聚合计算**。平台组通过 ACP 驱动 Claude Code，在代理层旁路采集，Agent 零侵入。

**2. 时间归因（Time Attribution）**

把总耗时拆成 LLM 耗时 / 工具耗时 / 等待。注意「工具耗时 203s > 总耗时 167s」说明有并行/后台工具，**并行工具时间做了叠加统计**——只有事件级记录才算得准。

**3. 规则式诊断（最值得抄的部分）**

不是 LLM 生成，是启发式规则：

- `单次最大事件间隔 47s，期间可能没有新的流式输出或工具活动` → **卡死/空转检测**：相邻事件时间差超阈值即报
- `工具 Bash 单次最长 65s，明显高于同会话其他工具（均值 10s）` → **离群检测**：单次工具耗时 vs 本会话均值比较

这两条规则成本极低（遍历事件数组算 gap 和离群），直接回答「Agent 是不是卡住了/哪个工具有问题」。

**4. 可视化：活动泳道图 + 轮次下钻**

- 泳道分三行（输入 / 模型 / 工具），彩色时间段块
- 「合并 chunk 与工具调用，不含 LLM 等待间隙」→ 把上千流式 delta 合并成连续活动块，空窗期不画
- TURN 级下钻：每步 USER/ASSISTANT/TOOL 事件 + 每步耗时 + Raw 原始数据
- 独立的「质量评估」Tab，与性能分析分开

**5. 权限阻塞分析**

`权限阻塞：请求 11 / 已解决 9 / 待处理 2 / 总等待 239s / 最长等待 66s`——把权限审批生命周期（发起→批准/拒绝）也作为事件记录，算出「多少时间浪费在等人点确认」。

### 对照 java-harness 的落地映射

| 平台组做法 | java-harness 改造点 |
|---|---|
| 事件溯源落库 | 新增 `agent_event` 表：`{sessionId, turnNo, eventType(token/tool/permission/turn_boundary), payload, startedAt, endedAt}`，在 `executeInternalStream` 的 `doOnNext` 里每个 SSE 事件写一条 |
| LLM/工具/等待时间归因 | 从事件流算相邻事件分类聚合；补 `ToolCallRecord.durationMs`（在 `ToolExecutionService` 管线填） |
| 卡死检测 | 遍历事件算 max gap，超阈值（如 30s）标 Warning |
| 工具离群检测 | 单次工具耗时 > 会话均值 × N 倍即报 |
| 权限阻塞统计 | 在 `DefaultPermissionChecker` 拦截处打事件对（request/resolved 时间戳） |
| 泳道时间轴 UI | Vue 前端把 `agent_event` 按类型分三行渲染，同类型相邻且间隔 < 1s 则合并 |
| 独立「质量评估」 | 对应 agent-evaluation.md 的 DeepEval 离线回归，做成独立 Tab |

> 平台组的方案验证了「自建事件级 Trace 是 Harness 类项目的正路」——Harness 天然控制事件流经的咽喉，外部平台 Python SDK 反而帮不上忙。其中**性价比最高的是「诊断提示」两条规则**（最大间隔 + 工具耗时离群），不到 100 行 Java 就能把「Agent 卡住了/工具异常」从翻日志变成看面板。

---

## 八、开源方案速查

| 类别 | 工具 | 定位 / 特点 |
|---|---|---|
| 观测/追踪平台 | Langfuse | 开源最主流之一，完全自托管；Tracing + Evals + Prompt 管理 + 成本；官方支持 LlamaIndex/LangChain |
| | Arize Phoenix | OTLP-native，本地/自托管；带 `phoenix-evals` 评测 |
| | Opik（Comet） | 追踪、评测、RAG 评估、实验管理一体 |
| | OpenObserve | 统一日志/指标/追踪，成本低 |
| | OpenLLMetry | 基于 OTel 的埋点 SDK，接任意 OTel 后端 |
| 评测框架 | DeepEval | Pytest 风格，内置 14+ 指标，LLM-as-Judge，可接 CI |
| | Ragas | RAG/Agent 评估专用（忠实度、上下文精度、相关性） |
| | Promptfoo | prompt + 模型对比评测，偏 prompt 回归 |
| 标准/规范 | OpenTelemetry GenAI SemConv | 标准化 LLM/Agent 语义约定（`gen_ai.*`），避免厂商锁定 |

> 自托管、一套搞定观测+评测+成本 → Langfuse 或 Phoenix；已有 OTel 栈 → Phoenix + OTel GenAI；只想先做离线评测 → DeepEval/Ragas/Promptfoo；底层走 OTel GenAI 标准避免锁死。

---

## 九、落地优先级清单

| 优先级 | 动作 | 解决 | 工作量 |
|---|---|---|---|
| P0 | 补 `ToolCallRecord` 的 result/duration + Trace 落库 | 能下钻回放，后续排查前提 | 小 |
| P1 | Grafana 面板 + Alertmanager 告警 + CostTracker 推送到群 | 性能/成本问题实时发现 | 小 |
| P1 | 离线评测集 + DeepEval 回归 | 发现「没报错但答错」的质量问题 | 中 |
| P2 | TraceViewer 页面（复用 Vue） | 可视化回放 | 中 |
| P2 | 按需接 Langfuse/Phoenix | 团队协作评测 + Prompt 管理 | 中 |

> 一句话总结：缺的不是指标和成本（已有），缺的是「每一步发生了什么」的 Trace 落库、和「答案对不对」的质量评测。优先补 `ToolCallRecord` 并落库，再上 Grafana + DeepEval；外部开源平台（Phoenix/Langfuse）作为可选增强，不必第一步硬上。
