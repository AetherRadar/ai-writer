# WenShape 全书分析降本提效 TODO

## 1. 目标与成功定义

适用场景：
- 批量导入全书章节（`章节号 + 正文`）
- 批量生成章节摘要 / 事实 / 时间线 / 角色状态
- 支持后续续写与改写（分支重算）

业务目标：
- 批量分析总 token 成本下降 >= 50%
- 批量分析总耗时下降 >= 40%（100 章基准）
- 事实质量保持可用（不牺牲一致性）

成功判定（必须同时满足）：
- 成本：同一数据集相同模型下，`total_tokens_new <= 0.5 * total_tokens_baseline`
- 性能：`p95_chapter_latency_new <= 0.6 * p95_chapter_latency_baseline`
- 质量：事实抽样准确率下降不超过 5%，解析成功率不下降

---

## 2. 现状问题（基于代码定位）

### 2.1 每章至少 2 次大模型抽取，成本线性放大

当前 `_build_analysis()` 同时做：
- 章节摘要抽取
- canon facts/timeline/states 抽取

代码位置：
- `backend/app/orchestrator/_analysis_mixin.py:143`
- `backend/app/orchestrator/_analysis_mixin.py:168`
- `backend/app/orchestrator/_analysis_mixin.py:183`

问题：
- 同一章全文被重复喂给 LLM，token 近似翻倍。

### 2.2 提示词把全文直塞 + schema 首尾重复

代码位置：
- `backend/app/prompts.py:2575`（canon updates）
- `backend/app/prompts.py:2736`（chapter summary）
- `backend/app/prompts.py:2727`、`backend/app/prompts.py:2878`（Schema 重复）

问题：
- 静态提示词开销高，章节越多放大越明显。

### 2.3 批量同步时，每章还有额外的“重点角色绑定”LLM调用

代码位置：
- `backend/app/orchestrator/_analysis_mixin.py:275`
- `backend/app/agents/_summary_mixin.py:97`

问题：
- 批量分析路径里，变成“摘要 + 事实 + 绑定”多次调用，进一步增成本。

### 2.4 研究检索链路默认开启语义重排，存在隐性 LLM 成本

代码位置：
- `backend/config.yaml:85`（`semantic_rerank: true`）
- `backend/app/services/text_chunk_service.py:141`

问题：
- 在多轮 research 中，可能产生额外 rerank LLM 调用。

### 2.5 成本观测不完整，难以精确优化

代码位置：
- `backend/app/llm_gateway/providers/custom_provider.py:45`

问题：
- SSE fallback 场景 `usage` 可能全 0，导致无法按阶段统计真实 token。

### 2.6 Writer 侧上下文预算偏大，易造成输入膨胀

代码位置：
- `backend/app/context/retriever.py:14`（`MAX_CONTEXT_TOKENS = 100000`）
- `backend/app/orchestrator/_context_mixin.py:528`
- `backend/app/agents/writer.py:470`

问题：
- 即使不是全书分析，也会拖慢续写阶段的响应与成本。

### 2.7 成稿后每章都重算卷摘要，迭代写作时开销偏高

代码位置：
- `backend/app/orchestrator/_analysis_mixin.py:503`
- `backend/app/orchestrator/_analysis_mixin.py:531`

问题：
- 连续确认章节时，卷摘要反复计算。

---

## 3. 基线采集（先做，不然优化无效）

输出两份基线：
- `baseline_small`（10章）
- `baseline_large`（100章）

采集字段：
- 总 token、每章 token、每阶段 token（summary/canon/binding/rerank）
- 总耗时、每章耗时、P95
- 解析失败率（YAML parse error 比例）
- 事实质量抽样（人工）

建议落盘文件（项目目录下）：
- `analysis/baseline_metrics.json`
- `analysis/baseline_chapter_metrics.ndjson`

---

## 4. 设计约束与原则

1. 先可观测，再优化（没有分项统计不改逻辑）
2. 对“未变更章节”零成本复用
3. 抽取任务优先合并，减少重复喂全文
4. 先做确定性压缩，再做 LLM 抽取
5. 高成本步骤只对低置信章节触发
6. 批量模式与在线写作模式隔离开关，避免互相影响

---

## 5. 数据结构与配置补充（建议新增）

## 5.1 增量分析清单（Manifest）

建议文件：`data/<project>/analysis/manifest.json`

建议字段：
- `chapter`: 章节号
- `content_hash`: 正文哈希（用于跳过）
- `summary_hash`: 摘要结果哈希
- `canon_hash`: facts/timeline/states 哈希
- `last_analyzed_at`: 时间戳
- `model_profile_id`: 使用的模型配置
- `tokens`: `{summary, canon, binding, rerank, total}`
- `latency_ms`: `{summary, canon, binding, total}`
- `status`: `ok|failed|partial|skipped`
- `error`: 最近错误信息

用途：
- 跳过未变更章节
- 失败章节重试
- 成本追溯

## 5.2 批量模式配置开关

建议在配置中增加（或以运行参数传入）：
- `analysis.bulk_mode: true/false`
- `analysis.bulk_disable_focus_binding: true`
- `retrieval.semantic_rerank_in_bulk: false`
- `analysis.prompt_compact_mode: true`
- `analysis.max_llm_calls_per_chapter: 1|2|3`
- `analysis.budget.max_tokens_per_job`
- `analysis.budget.max_minutes_per_job`

---

## 6. TODO 路线图（可执行）

## P0：观测与护栏（必须先做）

- [ ] 建立按阶段 token 统计
  - 维度：`chapter_summary_tokens`、`canon_tokens`、`focus_binding_tokens`、`rerank_tokens`
  - 触点：`backend/app/agents/base.py:110`、`backend/app/llm_gateway/providers/custom_provider.py:45`
  - 输出：`analysis/cost_ledger.ndjson`

- [ ] 建立批量任务 KPI 日志
  - 指标：每章耗时、每章调用次数、跳过章节数、累计 token
  - 触点：`backend/app/orchestrator/_analysis_mixin.py:203`

- [ ] 加入批量预算护栏与中断续跑
  - 达到预算阈值后停止，并记录下一个待处理章节

验收：
- 可以明确回答“哪一步最费 token、哪一步最慢、哪些章节被跳过/失败”。

## P1：低风险立竿见影优化

- [ ] 增量分析：章节哈希跳过未变更章节
  - 触点：`_build_analysis()`、`analyze_sync()`、`analyze_batch()`
  - 风险：哈希逻辑需包含标题变化（可单独 `title_hash`）

- [ ] 批量模式默认关闭 LLM 重点角色绑定
  - 保留算法绑定兜底
  - 触点：`backend/app/orchestrator/_analysis_mixin.py:275`

- [ ] 批量模式默认关闭 semantic rerank
  - 仅对低置信章节按需开启
  - 触点：`backend/config.yaml:85`

- [ ] 精简抽取提示词模板（compact mode）
  - 保留强约束，减少重复 schema 区块
  - 触点：`backend/app/prompts.py`（canon/chapter summary）

验收：
- 同数据集 token 成本下降 >= 25%，事实质量无明显下降。

## P2：结构级优化（核心收益）

- [ ] 合并“章节摘要 + canon 更新”为单次抽取调用
  - 当前 2 次/章，目标 1 次/章
  - 触点：`backend/app/orchestrator/_analysis_mixin.py:143`

- [ ] 引入“确定性章节预摘要（LLM-free）”
  - 复用 `_build_chapter_digest()` 思路，先产出结构化 digest
  - 触点：`backend/app/orchestrator/_context_mixin.py:420`

- [ ] 建立 Map-Reduce 全书摘要链路
  - Map：每章 deterministic digest
  - Reduce：按卷/按批 LLM 汇总，不再逐章全文多次抽取

- [ ] 低置信回补机制
  - 只对异常章节二次抽取，而不是全量二次跑

验收：
- 100 章基准下 token 降 >= 50%，耗时降 >= 40%。

## P3：续写/改写长期优化

- [ ] 改写采用“分支影响范围重算”
  - 从 N 章改写，仅重算受影响章节，不重算全书

- [ ] 卷摘要改为增量更新
  - 仅当该卷章节摘要变更时刷新

- [ ] Writer 输入收敛
  - 已有 `working_memory` 时减少 raw cards/facts 堆叠
  - 控制上下文膨胀

验收：
- 从任意章节分叉改写时，重算范围显著收敛。

---

## 7. PR 拆分建议（避免大爆炸）

- PR-1：P0 观测 + cost ledger
- PR-2：章节哈希跳过 + manifest
- PR-3：批量模式开关（禁 focus binding / 禁 rerank）
- PR-4：prompt compact mode
- PR-5：单调用抽取（summary+canon）
- PR-6：map-reduce + 低置信回补
- PR-7：分支影响范围重算

每个 PR 要求：
- 具备回滚开关
- 具备对照日志（baseline vs current）
- 不修改无关业务路径

---

## 8. 风险与回滚策略

主要风险：
- 合并抽取后解析复杂度上升，解析失败率短期可能上升
- 关闭 rerank 可能导致召回质量波动
- 哈希跳过策略若设计不全，可能漏分析真实变化

回滚策略：
- 所有优化默认 behind flag
- 任一指标异常可即时切回旧路径
- 保留 per-chapter 回退（失败章节回退到 legacy 双调用抽取）

---

## 9. 验证方案

测试集：
- 小：10 章
- 中：50 章
- 大：100+ 章

指标：
- 成本：总 token、每章 token、每阶段 token
- 性能：总耗时、每章 P95
- 质量：事实准确率（抽样）、解析成功率、冲突检测变化

通过标准：
- 达到降本目标
- 摘要/事实可用性不退化
- 任务可中断续跑

---

## 10. 本轮非目标

- 不重写底层存储架构
- 不替换整套模型供应商系统
- 不先做 UI 大改（先稳后端链路）
