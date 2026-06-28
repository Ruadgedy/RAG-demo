# 增量重构：chat_history 表结构 V3（单回合记录 + RAG 埋点）

> 日期：2026-06-28
> 分支：dev
> 提交：refactor(chat-history): V3 表结构重构——单回合记录 + CHAR(36) UUID + RAG 召回元数据

---

## 一、改动背景

`chat_history` 表结构设计不合理：

| 老问题 | 影响 |
| --- | --- |
| user 问题与 AI 回答拆成两条 `role=user/assistant` 记录 | 一个回合要 JOIN 才能还原；查询"用户问了哪些问题"需要 GROUP BY |
| `id binary(16)` + `knowledge_base_id binary(16)` | DB 里 UUID 是不可读的二进制，排查问题极其痛苦 |
| `user_id varchar(255)` | username 实际最长不过几十个字符，浪费索引空间 |
| 无 `query` 字段 | 无法直接按"问题文本"做去重/统计/搜索 |
| 无 RAG 召回元数据 | RAG 质量评估、bad case 排查全靠日志，无法结构化分析 |
| 缺 `idx_session` / `idx_knowledge_base` / `idx_created_at` | V1 SQL 写了但因 baseline 没跑过，索引从未建上 |
| 主键 UUID 拆 binary(16) | JPA 实体用 `UUID` 类型，应用层代码各处 `.toString()` 转换繁琐 |

---

## 二、新的表结构（V3）

```sql
CREATE TABLE chat_history (
    id                CHAR(36)        PRIMARY KEY,        -- 可读 UUID
    user_id           VARCHAR(64)     DEFAULT NULL,       -- 收紧到 64
    session_id        VARCHAR(255)    DEFAULT NULL,
    knowledge_base_id CHAR(36)        DEFAULT NULL,       -- 可读 UUID
    query             VARCHAR(128)    DEFAULT NULL,       -- 用户提问（最长 128）
    content           TEXT            NOT NULL,           -- 模型答案
    created_at        TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    rag_metadata      JSON            DEFAULT NULL,       -- RAG 召回元数据

    INDEX idx_session (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_knowledge_base (knowledge_base_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

> **注**：未建 FK 到 `knowledge_base(id)`——因为后者是 `binary(16)` 而新表是 `CHAR(36)`，MySQL 不允许两种类型直接 FK；现状是 `document.knowledge_base_id` 也没有 FK，与历史保持一致。如未来需要强一致，建议把 `knowledge_base.id` 统一改成 `CHAR(36)`。

### 核心设计变化：单回合 = 单记录

| 维度 | V2（修复前） | V3（本次重构） |
| --- | --- | --- |
| 一次问答落库条数 | **2 条**（user + assistant） | **1 条**（query + content + rag_metadata） |
| `role` 字段 | ✅ 有（user/assistant） | ❌ 移除（合并到 query/content） |
| `query` 字段 | ❌ 无 | ✅ VARCHAR(128) |
| `rag_metadata` 字段 | ❌ 无 | ✅ JSON（召回文档数/ID列表/片段数/检索耗时） |
| `id` 类型 | binary(16) UUID | CHAR(36) UUID 字符串 |
| `knowledge_base_id` 类型 | binary(16) UUID | CHAR(36) UUID 字符串 |
| `user_id` 长度 | 255 | 64 |
| 索引 | 仅 PRIMARY + idx_user_id | PRIMARY + idx_session + idx_user_id + idx_knowledge_base + idx_created_at |

### `rag_metadata` JSON 结构

```json
{
  "retrieved_doc_count": 1,
  "retrieved_chunk_count": 1,
  "retrieved_doc_ids": ["6cd00d36-09ad-48a6-8ddc-881d9bdf189e"],
  "retrieval_duration_ms": 213
}
```

---

## 三、代码连锁改动

| 文件 | 改动 |
| --- | --- |
| `db/migration/V3__redesign_chat_history.sql` | **新增**：DROP + 重建为新 schema |
| `model/ChatHistory.java` | 移除 `role`，新增 `query` + `ragMetadata`；`id` / `knowledgeBaseId` 改 `String`（CHAR(36)）；`@PrePersist` 自动生成 UUID 字符串 |
| `repository/ChatHistoryRepository.java` | 主键类型 `UUID` → `String`；`findByKnowledgeBaseIdOrderByCreatedAtDesc` 签名改 String |
| `service/RagService.java` | 新增 `record ChatResult(answer, retrievedDocs, retrievalDurationMs)`；`chat()` 返回类型从 `String` 改为 `ChatResult`；新增检索阶段计时 |
| `service/ChatService.java` | 把 2 次 `saveHistory` 合并为 1 次 `saveTurn`（单条记录：query + content + rag_metadata）；所有 `saveHistory` 调用点改为 `saveTurn`；新增 `buildRagMetadataJson()` 用 ObjectMapper 序列化 RAG 召回元数据 |
| `controller/ChatHistoryController.java` | `kbId` 路径变量从 `UUID` 改为 `String`；`/api/chat-history/{sessionId}` 返回**展开后的** `[{role, content}]` 列表（前端 `loadSession` 无需改） |
| `test/service/ChatServiceTest.java` | `times(2)` → `times(1)`（一个回合只调一次 `saveAndFlush`）；Mock `RagService.chat()` 返回 `ChatResult` |

### 关键设计决策

1. **保留前端 API 兼容性**：`/api/chat-history/{sessionId}` 仍然返回 `[{role, content}]` —— 内部用 stream 把单条 `chat_history` 记录展开为 user + assistant 两条消息，前端 `loadSession` 的 `res.data.map(h => ({ role: h.role, content: h.content }))` **无需任何改动**

2. **不静默吞异常**：`saveTurn` 抛异常 → 调用方记 `log.warn("[落库告警] ...")` 告警，与上一轮修复保持一致

3. **`rag_metadata` 序列化失败时返回 null**：不让 JSON 序列化错误阻断主问答流程，但前端会看到 `rag_metadata=null`，便于排查

4. **Java 端 UUID → String**：权衡了「保留 UUID + 自定义 AttributeConverter」vs「直接用 String」。String 更直观、序列化/反序列化/JSON 交互更简单，性能开销可忽略（每次问答多一次字符串转换）

---

## 四、端到端验证

### 1. Flyway V3 迁移成功

```sql
SELECT * FROM flyway_schema_history;
```
```
installed_rank | version | description            | success
1              | 1       | << Flyway Baseline >>  | 1
2              | 2       | add user id to chat history | 1
3              | 3       | redesign chat history  | 1  ← 本次新跑成功
```

### 2. 新表结构

```
Field              | Type           | Null | Key
id                 | char(36)       | NO   | PRI
user_id            | varchar(64)    | YES  | MUL
session_id         | varchar(255)   | YES  | MUL
knowledge_base_id  | char(36)       | YES  | MUL
query              | varchar(128)   | YES  |
content            | text           | NO   |
created_at         | timestamp      | YES  | MUL   DEFAULT CURRENT_TIMESTAMP
rag_metadata       | json           | YES  |
```

### 3. 落库验证

非流式 / 流式问答都正确落库，每回合 1 条记录：

| user_id | session | query | content preview | docs | ms |
| --- | --- | --- | --- | --- | --- |
| vtest_v3 | 41ac4a11 | V3 测试：什么是云原生 | ## 什么是云原生\n\n... | 1 | 3695 |
| vtest_v3 | ce89541d | V3 流式 v2：容器化技术 | 抱歉，知识库中没有找到... | 1 | 213 |

`rag_metadata` 完整可被 `JSON_EXTRACT()` 解析。

### 4. 历史查询接口兼容

`GET /api/chat-history/{sessionId}` 返回展开格式（前端无需改）：

```json
[
  { "role": "user",      "content": "V3 测试：什么是云原生" },
  { "role": "assistant", "content": "## 什么是云原生\n\n..." }
]
```

### 5. 单元测试

```
[INFO] Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## 五、变更清单

| 类型 | 文件 |
| --- | --- |
| 新增 | `rag-qa-backend/src/main/resources/db/migration/V3__redesign_chat_history.sql` |
| 修改 | `rag-qa-backend/src/main/java/com/ragqa/model/ChatHistory.java` |
| 修改 | `rag-qa-backend/src/main/java/com/ragqa/repository/ChatHistoryRepository.java` |
| 修改 | `rag-qa-backend/src/main/java/com/ragqa/service/RagService.java` |
| 修改 | `rag-qa-backend/src/main/java/com/ragqa/service/ChatService.java` |
| 修改 | `rag-qa-backend/src/main/java/com/ragqa/controller/ChatHistoryController.java` |
| 修改 | `rag-qa-backend/src/test/java/com/ragqa/service/ChatServiceTest.java` |
| 新增 | `docs/项目结构分析06-28-v3-chat-history-redesign.md`（本文件） |

---

## 六、后续建议

- [ ] **真正落地 RAG 评估**：基于 `rag_metadata.retrieved_doc_count/retrieval_duration_ms` 做监控告警，例如「P95 检索耗时 > 2s」触发告警
- [ ] **把 `query` 字段接入搜索**：可在前端会话列表按"用户问过什么"做搜索
- [ ] **统一 UUID 类型**：未来如果想做 FK 强一致，建议把 `knowledge_base.id` 也从 `binary(16)` 改为 `CHAR(36)`，再补 FK
- [ ] **坏案例标注**：可以在表上加 `is_bad_case BOOLEAN` + `bad_case_reason VARCHAR` 字段，便于人工标注 RAG 失败案例
