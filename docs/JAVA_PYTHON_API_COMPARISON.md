# Java / Python API 对照

写作流程相关接口的对照与差异说明。

## 路径前缀

- **Python**: `/projects/{project_id}/...` 或 `/api/projects/{project_id}/...`
- **Java**: `/projects/{projectId}/...` 或 `/api/projects/{projectId}/...`（通过 ApiPrefixFilter 支持 `/api`）

## 写作流程核心接口

| 功能 | Python | Java | 备注 |
|-----|--------|------|------|
| 开始会话 | POST `/projects/{id}/session/start` | POST `/projects/{id}/session/start` | ✓ 一致 |
| 会话状态 | GET `/projects/{id}/session/status` | GET `/projects/{id}/session/status` | ✓ 一致 |
| 提交反馈 | POST `/projects/{id}/session/feedback` | POST `/projects/{id}/session/feedback` | ✓ 一致 |
| 选区编辑 | POST `/projects/{id}/session/edit-suggest` | POST `/projects/{id}/session/edit-suggest` | ✓ 一致 |
| 回答问题 | POST `/projects/{id}/session/answer-questions` | POST `/projects/{id}/session/answer-questions` | ✓ 一致 |
| 取消会话 | POST `/projects/{id}/session/cancel` | POST `/projects/{id}/session/cancel` | ✓ 一致 |
| 流式生成 | （内部调用） | GET `/projects/{id}/session/stream-draft?chapter=...` | Java 单独暴露 |

## 草稿接口

| 功能 | Python | Java | 备注 |
|-----|--------|------|------|
| 列出章节 | GET `/projects/{id}/drafts` | GET `/projects/{id}/drafts` | ✓ 一致 |
| 章节摘要 | GET `/projects/{id}/drafts/summaries` | GET `/projects/{id}/drafts/summaries` | ✓ 一致 |
| 最新草稿 | GET `/projects/{id}/drafts/{chapter}/{version}` | GET `/projects/{id}/drafts/{chapter}` | Java 多一个「最新版本」快捷 |
| 成稿 | GET `/projects/{id}/drafts/{chapter}/final` | GET `/projects/{id}/drafts/{chapter}/final` | ✓ 一致 |
| 场景简报 | GET `/projects/{id}/drafts/{chapter}/scene-brief` | GET `/projects/{id}/drafts/{chapter}/scene-brief` | ✓ 一致 |
| 审稿 | GET `/projects/{id}/drafts/{chapter}/review` | GET `/projects/{id}/drafts/{chapter}/review` | Java 无则按需生成 |
| 手动保存 | PUT `/projects/{id}/drafts/{chapter}/content` | PUT `/projects/{id}/drafts/{chapter}/content` | ✓ 一致 |
| 自动保存 | PUT `/projects/{id}/drafts/{chapter}/autosave` | PUT `/projects/{id}/drafts/{chapter}/autosave` | ✓ 一致 |

## 保存后行为（已对齐）

- **Python**: 手动保存 → 重建 bindings
- **Java**: 手动保存 → 重建 bindings + 刷新 review + 刷新 memory pack

## 证据 / 上下文

| 功能 | Python | Java | 备注 |
|-----|--------|------|------|
| 证据搜索 | POST `/projects/{id}/evidence/search` | POST `/projects/{id}/evidence/search` | ✓ 一致 |
| 文本分块搜索 | POST `/projects/{id}/text-chunks/search` | POST `/projects/{id}/text-chunks/search` | ✓ 一致 |
| Memory Pack | GET `/projects/{id}/memory-pack/{chapter}` | GET `/projects/{id}/memory-pack/{chapter}` | ✓ 一致 |

## 其他接口

- `/projects`, `/cards`, `/canon`, `/volumes`, `/outline`, `/config`, `/proxy`, `/bindings`, `/facts` 等均已实现对应 Java 版本，路径与行为基本一致。
