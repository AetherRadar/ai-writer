# Java / Python 版本差异报告

供手动修改参考。按模块列出差异点及建议修改方向。

---

## 一、API 与请求体差异

### 1. edit-suggest 的 context_mode 行为

| 项目 | Python | Java |
|------|--------|------|
| 字段 | `context_mode`: "quick" \| "full" | 有 `contextMode` 字段，但未使用 |
| 行为 | `full` 时调用 `ensure_memory_pack(force_refresh=True)` 重建记忆包 | 始终只读已有 memory pack，不重建 |

**修改建议**：在 `SessionController.suggestEdit` 中，当 `contextMode == "full"` 时，调用 Orchestrator 的 `ensureMemoryPack` 或等价逻辑，强制刷新 memory pack 后再做编辑建议。

---

### 2. edit-suggest 的 selection_occurrence

| 项目 | Python | Java |
|------|--------|------|
| 字段 | `selection_occurrence`（可选，默认 1） | 无 |
| 行为 | 子串匹配时支持指定「第几次出现」 | 固定为第一次出现 |

**修改建议**：在 `EditSuggestRequest` 中增加 `selection_occurrence`，在 `EditorAgent.suggestRevisionSelection` 中支持按 occurrence 选择匹配位置。

---

### 3. draft review 行为

| 项目 | Python | Java |
|------|--------|------|
| 无 review 时 | 404 | 按需调用 `EditorAgent.reviewDraft` 生成并保存 |

**说明**：Java 为增强行为，如需与 Python 完全一致可改为 404；一般建议保留 Java 行为。

---

## 二、存储路径与格式差异

### 4. Memory Pack 存储

| 项目 | Python | Java |
|------|--------|------|
| 路径 | `memory_packs/{chapter}.json` | `memory_packs/{chapter}.yaml` |
| 格式 | JSON | YAML |

**影响**：两套后端无法共享同一 data 目录下的 memory pack 文件。

**修改建议**：
- 方案 A：将 Java `MemoryPackStorage` 改为写入 `memory_packs/{chapter}.json`，与 Python 一致。
- 方案 B：在 `MemoryPackController.readPack` 中增加对 `memory_packs/{chapter}.yaml` 的回退读取（当前只读 `.json` 和 `drafts/.../memory_pack.yaml`），否则 Java 写入的 pack 无法被 GET 接口读到。

---

### 5. Memory Pack GET 接口的 status 结构

| 项目 | Python | Java |
|------|--------|------|
| 返回字段 | `exists`, `chapter`, `built_at`, `source`, `evidence_stats`, `card_snapshot` | `exists`, `chapter`, `summary`, `facts_count`, `scene_brief`, `updated_at` |

**修改建议**：如需前端兼容，可让 Java 的 `MemoryPackController.buildStatus` 与 Python 的 `build_status` 返回相同字段（`evidence_stats`, `card_snapshot` 等）。

---

### 6. Bindings 存储路径

| 项目 | Python | Java |
|------|--------|------|
| 路径 | `index/chapters/{chapter}/bindings.yaml` | `drafts/{chapter}/bindings.yaml` |

**影响**：两套后端不共享 bindings 文件。

**修改建议**：若需共用 data 目录，将 Java `BindingsController.getBindingPath` 改为 `index/chapters/{chapter}/bindings.yaml`。

---

## 三、Bindings 逻辑差异

### 7. 绑定内容与算法

| 项目 | Python | Java |
|------|--------|------|
| 角色 | `characters` + BM25 匹配阈值、去停用词 | `characters` + 简单子串计数 |
| 世界观 | `world_entities` + `world_rules`，BM25 | `world`，简单子串计数 |
| 来源 | `sources` | 无 |

**修改建议**：在 `BindingsController` 中引入与 Python 类似的 BM25 逻辑、停用词、`world_rules` 等，以提升绑定质量与一致性。当前 Java 为简化实现。

---

## 四、Evidence 搜索差异

### 8. semantic_rerank

| 项目 | Python | Java |
|------|--------|------|
| 支持 | `evidence_service.search` 支持 `semantic_rerank`, `rerank_query`, `rerank_top_k` | 未实现 |

**修改建议**：在 `EvidenceController` 和 `ContextSelectEngine` 中增加 `semantic_rerank` 相关参数，并在 `plot_point` 类 gap 时使用 LLM 做语义重排。

---

### 9. Evidence rebuild 返回格式

| 项目 | Python | Java |
|------|--------|------|
| 返回 | `{"success": true, "meta": {...}}`，`meta` 为各索引的元数据 | 需确认是否与 Python 一致 |

---

## 五、Canon 接口差异

### 10. character-state 更新方式

| 项目 | Python | Java |
|------|--------|------|
| 更新 | `POST /character-state`，body 为完整 state | `PUT /character-state/{name}` 和 `POST /character-state`，body 为 state |

**说明**：Java 多提供 `PUT /character-state/{name}`，语义上等价，可保留。

---

### 11. facts 按章节查询

| 项目 | Python | Java |
|------|--------|------|
| 路径 | `GET /facts/{chapter}` | `GET /facts?chapter=xxx` |

**说明**：语义相同，路径风格不同。若前端按 Python 路径调用，需在 Java 中增加 `GET /facts/{chapter}` 路由（注意与 `GET /facts/{factId}` 冲突，需区分）。

---

## 六、其他差异

### 12. WebSocket

| 项目 | Python | Java |
|------|--------|------|
| 端点 | `/ws/trace`, `/ws/{project_id}/session` | 有 `WebSocketConfig`，需确认路径是否一致 |

---

### 13. Fanfiction 路由前缀

| 项目 | Python | Java |
|------|--------|------|
| 前缀 | `/fanfiction`（无 project_id） | `/fanfiction` ✓ 一致 |

---

### 14. 项目重命名

| 项目 | Python | Java |
|------|--------|------|
| 方法 | `PATCH /projects/{id}` | `PATCH /projects/{id}` ✓ |

---

## 七、建议优先级

| 优先级 | 项目 | 建议 |
|--------|------|------|
| 高 | Memory Pack 存储格式/路径 | 统一 JSON 或让 Controller 能读取 Java 写入的 YAML |
| 高 | Bindings 存储路径 | 若需共用 data，统一为 `index/chapters/` |
| 中 | context_mode | 实现 `full` 模式下的 memory pack 重建 |
| 中 | selection_occurrence | 支持选区编辑的「第 N 次出现」 |
| 低 | semantic_rerank | 证据搜索的 LLM 重排 |
| 低 | Bindings BM25 | 提升绑定精度 |

---

## 八、文件索引（便于定位修改）

| 功能 | Python | Java |
|------|--------|------|
| edit-suggest | `routers/session.py` | `SessionController.java`, `EditorAgent.java` |
| 记忆包 | `storage/memory_pack.py` | `MemoryPackStorage.java`, `MemoryPackController.java` |
| 绑定 | `services/chapter_binding_service.py`, `storage/bindings.py` | `BindingsController.java` |
| 证据 | `services/evidence_service.py`, `routers/evidence.py` | `EvidenceController.java`, `ContextSelectEngine.java` |
| 审稿 | `routers/drafts.py`, `storage/drafts.py` | `DraftController.java`, `EditorAgent.java` |
