# Feature #27 — 删除级联清理（知识库 + 文档）

| 项目 | 内容 |
|------|------|
| **Feature ID** | #27 |
| **关联修复** | FIX-003, FIX-004 |
| **关联类** | `KnowledgeBaseService`, `DocumentService` |
| **关联需求** | FR-004（知识库删除）、NFR-006（数据持久化）、NFR-007（异常处理） |
| **优先级** | P0 |
| **编写日期** | 2026-06-27 |

---

## 1. 功能概述

### 1.1 背景

RAG 系统涉及 4 个独立存储介质：
- **MySQL**（受 FK CASCADE 管理）
- **Chroma 向量数据库**（外部 HTTP 服务）
- **BM25 内存索引**（JVM 堆）
- **本地文件系统**（OS 层）

原 `KnowledgeBaseService.delete()` 仅调 `repository.delete(kb)`，导致：
- Chroma 中该 KB 的所有向量变成**孤儿数据**（最严重——会被检索召回造成幻觉答案）
- BM25 索引无限增长
- uploads 目录文件无限堆积

原 `DocumentService.deleteDocument()` 也漏掉 BM25 索引清理。

### 1.2 修复方案

**KnowledgeBaseService.delete()**：
1. `documentRepository.findByKnowledgeBaseId(id)` 抓快照（在 CASCADE 之前）
2. 循环：每个 doc 执行 `chromaService.deleteByDocumentId` + `bm25Service.removeByDocumentId` + `Files.deleteIfExists`
3. 每步独立 try-catch（防御性容错）
4. 最后 `repository.delete(kb)` 触发 FK CASCADE 清 MySQL

**DocumentService.deleteDocument()**：
- 在 `chromaService.deleteByDocumentId` 后追加 `bm25Service.removeByDocumentId`
- 同样 try-catch 容错

---

## 2. 测试用例

### TC-27-01: 删除知识库触发完整级联清理

| 项 | 内容 |
|----|------|
| **用例编号** | TC-27-01 |
| **测试目标** | 验证 KB 删除时 Chroma + BM25 + MySQL + 文件四链路全部清理 |
| **关联需求** | FR-004 |
| **前置条件** | 知识库存在，下含 1 个文档（docId="xxx"） |
| **测试步骤** | 1. `repository.findById(kbId)` 返回 KB<br>2. `documentRepository.findByKnowledgeBaseId(kbId)` 返回 [doc]<br>3. `service.delete(kbId)`<br>4. 验证调用链 |
| **预期结果** | • `chromaService.deleteByDocumentId(docId)` 被调用 1 次<br>• `bm25Service.removeByDocumentId(docId.toString())` 被调用 1 次<br>• `repository.delete(kb)` 被调用 1 次 |
| **验证方式** | Mockito verify |
| **状态** | ✅ 已通过 `KnowledgeBaseServiceTest.shouldCascadeCleanupOnDelete` |

### TC-27-02: 空知识库删除不应报错

| 项 | 内容 |
|----|------|
| **用例编号** | TC-27-02 |
| **测试目标** | 验证没有任何文档的 KB 也能正常删除 |
| **前置条件** | KB 存在但 `findByKnowledgeBaseId` 返回空列表 |
| **测试步骤** | 1. `service.delete(kbId)`<br>2. 验证行为 |
| **预期结果** | • `repository.delete(kb)` 被调用<br>• `chromaService.deleteByDocumentId` **从未被调用**（无 docId）<br>• `bm25Service.removeByDocumentId` **从未被调用** |
| **验证方式** | Mockito verify + never() |
| **状态** | ✅ 已通过 `KnowledgeBaseServiceTest.shouldHandleEmptyKnowledgeBaseDeletion` |

### TC-27-03: Chroma 清理失败不应阻塞整体删除

| 项 | 内容 |
|----|------|
| **用例编号** | TC-27-03 |
| **测试目标** | 防御性容错：Chroma 不可用时 KB 仍能被删除 |
| **关联需求** | NFR-007（异常处理） |
| **前置条件** | KB 存在，下含 1 个文档 |
| **测试步骤** | 1. `chromaService.deleteByDocumentId(docId)` 抛出 `RuntimeException("Chroma 不可用")`<br>2. `service.delete(kbId)` |
| **预期结果** | • 不抛异常<br>• warn 日志输出<br>• `repository.delete(kb)` 仍被调用<br>• `bm25Service.removeByDocumentId` 仍被调用 |
| **验证方式** | Mockito doThrow + verify |
| **状态** | ✅ 已通过 `KnowledgeBaseServiceTest.shouldContinueDeletionEvenIfChromaCleanupFails` |

### TC-27-04: 删除不存在的 KB 应抛 IllegalArgumentException

| 项 | 内容 |
|----|------|
| **用例编号** | TC-27-04 |
| **测试目标** | 验证错误路径 |
| **前置条件** | KB ID 不存在 |
| **测试步骤** | 1. `repository.findById(kbId)` 返回 Optional.empty()<br>2. `service.delete(kbId)` |
| **预期结果** | • 抛出 `IllegalArgumentException` 包含 "知识库不存在"<br>• 不调用任何 Chroma / BM25 |
| **验证方式** | assertThatThrownBy |
| **状态** | ✅ 已通过 `KnowledgeBaseServiceTest.shouldThrowExceptionWhenDeletingNonExistentKnowledgeBase` |

### TC-27-05: 删除单个文档同步清理 BM25

| 项 | 内容 |
|----|------|
| **用例编号** | TC-27-05 |
| **测试目标** | 验证 DocumentService.deleteDocument 同时清理 Chroma + BM25 + 文件 + MySQL |
| **关联需求** | FR-005（间接，检索数据完整性） |
| **前置条件** | 文档存在，含 filePath |
| **测试步骤** | 1. `service.deleteDocument(docId)`<br>2. 验证调用链 |
| **预期结果** | • `chromaService.deleteByDocumentId(docId)` 被调用<br>• `bm25Service.removeByDocumentId(docId.toString())` 被调用<br>• `documentRepository.delete(doc)` 被调用<br>• `documentChunkRepository.deleteByDocumentId(docId)` 被调用 |
| **验证方式** | Mockito verify |
| **状态** | ✅ 已通过 `DocumentServiceTest.shouldDeleteDocument` |

### TC-27-06: BM25 清理失败不应阻塞文档删除

| 项 | 内容 |
|----|------|
| **用例编号** | TC-27-06 |
| **测试目标** | 防御性容错：BM25 不可用时文档仍能被删除 |
| **关联需求** | NFR-007 |
| **前置条件** | 文档存在 |
| **测试步骤** | 1. `bm25Service.removeByDocumentId(docId.toString())` 抛 RuntimeException<br>2. `service.deleteDocument(docId)` |
| **预期结果** | • 不抛异常<br>• warn 日志输出<br>• `documentRepository.delete(doc)` 仍被调用 |
| **验证方式** | Mockito doThrow + verify |
| **状态** | ✅ 已通过 `DocumentServiceTest.shouldContinueDeletionEvenIfBm25CleanupFails` |

---

## 3. 集成场景测试（E2E）

### TC-27-07: 完整生命周期 — 上传→处理→删除

| 项 | 内容 |
|----|------|
| **用例编号** | TC-27-07 |
| **测试目标** | E2E 验证：上传文档 → 异步处理完成 → 删除 KB，所有外部资源清理 |
| **前置条件** | MySQL、Chroma、Ollama 全部可用 |
| **测试步骤** | 1. POST `/api/knowledge-bases` 创建 KB<br>2. POST `/api/knowledge-bases/{kbId}/documents` 上传 PDF<br>3. 轮询直到文档 status=COMPLETED<br>4. 验证 Chroma 中有该 doc 的向量<br>5. 验证 BM25 索引含该 doc 的 chunk<br>6. DELETE `/api/knowledge-bases/{kbId}`<br>7. 验证 Chroma 中无残留向量（通过 query + where documentId）<br>8. 验证 uploads/{kbId} 目录为空<br>9. 验证 MySQL 中 KB / document / document_chunk 已清 |
| **预期结果** | • 所有外部资源完全清理<br>• 后续检索不应返回该 doc 的内容 |
| **验证方式** | 集成测试（需要真实 Chroma + MySQL） |
| **状态** | ⏳ 待实现（建议作为后续 ST 阶段） |

---

## 4. 覆盖矩阵

| FIX | 场景 | TC |
|-----|------|-----|
| FIX-003 KB 级联清理 | 正常路径 | TC-27-01 |
| FIX-003 | 空 KB | TC-27-02 |
| FIX-003 | Chroma 容错 | TC-27-03 |
| FIX-003 | 错误路径 | TC-27-04 |
| FIX-004 文档 BM25 清理 | 正常路径 | TC-27-05 |
| FIX-004 | BM25 容错 | TC-27-06 |
| 集成 | E2E | TC-27-07 |

---

## 5. 验收标准

- [x] 6 个单元测试全部通过
- [x] 4 链路清理（Chroma / BM25 / MySQL / 文件）全部覆盖
- [x] 容错场景（外部服务不可用）覆盖
- [ ] TC-27-07 E2E 测试建议作为下一阶段 ST 任务

---

## 6. 自动化测试结果

```
[INFO] Running com.ragqa.service.KnowledgeBaseServiceTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.ragqa.service.DocumentServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```