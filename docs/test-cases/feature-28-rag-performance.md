# Feature #28 — RAG 检索性能与稳定性

| 项目 | 内容 |
|------|------|
| **Feature ID** | #28 |
| **关联修复** | FIX-005, FIX-006, FIX-007 |
| **关联类** | `RagService`, `EmbeddingService` |
| **关联需求** | FR-005（智能问答）、NFR-002（响应时间）、NFR-003（向量检索时间）、NFR-007（异常处理） |
| **优先级** | P0 |
| **编写日期** | 2026-06-27 |

---

## 1. 功能概述

### 1.1 背景

`RagService.retrieve()` 存在三类问题：

1. **N+1 查询**：每个 Chroma 返回的结果都触发 1 次 `findByKnowledgeBaseId`。TopK=20 即 20 次 DB 查询。

2. **Fallback 全量加载 OOM**：`fallbackRetrieve()` 把所有 chunk 向量加载到内存做余弦相似度。4096 维 × N chunks 会耗尽堆。

3. **`EmbeddingService` 无超时**：默认 `RestTemplate` 无超时配置。Ollama 挂起时 HTTP 线程被永久阻塞，最终 Tomcat 线程池雪崩。

### 1.2 修复方案

- `retrieve()`：提前 `findByKnowledgeBaseId` 一次性查询，结果缓存为 `Set<UUID>`，`Set.contains` 过滤 O(1)
- `fallbackRetrieve()`：加 `fallbackMaxChunks`（默认 5000）上限截断
- `EmbeddingService`：构造方法注入 `SimpleClientHttpRequestFactory`，connect=5s / read=30s，捕获 `ResourceAccessException`

---

## 2. 测试用例

### TC-28-01: RAG 检索 DB 查询次数应为 1 次

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-01 |
| **测试目标** | 验证 FIX-005：无论 Chroma 返回几个结果，`documentRepository.findByKnowledgeBaseId` 只调用 1 次 |
| **关联需求** | NFR-002（响应时间）、NFR-003（向量检索时间） |
| **前置条件** | KB 下有 N 个 COMPLETED 文档 |
| **测试数据** | Chroma 返回 5 个 SearchResult |
| **测试步骤** | 1. Mock `chromaService.similaritySearch` 返回 5 个结果<br>2. Mock `documentRepository.findByKnowledgeBaseId(kbId)` 返回 5 个 doc<br>3. 调用 `chat(message, kbId)` |
| **预期结果** | • `documentRepository.findByKnowledgeBaseId(kbId)` 调用次数 == **1**（不是 5）<br>• 返回结果仅包含属于该 KB 且 COMPLETED 的 chunk |
| **验证方式** | Mockito verify(times(1)) |
| **状态** | ⏳ 建议实现（需 Mock RagService 依赖链） |

### TC-28-02: RAG 检索仅返回 COMPLETED 文档

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-02 |
| **测试目标** | 验证过滤逻辑：只返回状态为 COMPLETED 的 doc 对应的 chunk |
| **前置条件** | KB 下有 COMPLETED + UPLOADING + FAILED 三种状态的 doc |
| **测试数据** | Chroma 返回 3 个结果，分别对应 3 种状态的 doc |
| **测试步骤** | 1. Mock 3 个不同状态的 doc<br>2. 调用 `chat(message, kbId)`<br>3. 检查 prompt 中包含哪些 chunk 内容 |
| **预期结果** | • 只有 COMPLETED 的 chunk 内容进入 prompt<br>• UPLOADING / FAILED 的被过滤 |
| **验证方式** | 单元测试 + ArgumentCaptor |
| **状态** | ⏳ 建议实现 |

### TC-28-03: Fallback 检索 chunk 数超过上限应截断

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-03 |
| **测试目标** | 验证 FIX-006：`fallbackMaxChunks` 限制单次请求的内存占用 |
| **关联需求** | NFR-002（响应时间）、稳定性 |
| **前置条件** | Chroma 不可用，走 fallback 路径；KB 下有 10000 个 chunk |
| **测试数据** | `fallbackMaxChunks = 5000`（默认值） |
| **测试步骤** | 1. Mock `chromaService.similaritySearch` 抛异常<br>2. Mock `documentRepository.findByKnowledgeBaseId` 返回 100 个 doc<br>3. 每个 doc 有 100 个 chunk（共 10000）<br>4. 调用 `chat(message, kbId)`<br>5. 验证日志 warn 输出 |
| **预期结果** | • 只加载 5000 个 chunk 后停止<br>• warn 日志："Fallback 检索达到最大切片数限制 5000，结果可能不完整"<br>• HTTP 请求不抛 OOM |
| **验证方式** | 单元测试 + logback ListAppender |
| **状态** | ⏳ 建议实现 |

### TC-28-04: Fallback 检索 chunk 数在限制内应正常返回

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-04 |
| **测试目标** | 验证正常路径：chunk 数未超限时按余弦相似度 TopK 返回 |
| **前置条件** | Chroma 不可用；KB 下有少量 chunk |
| **测试数据** | 5 个 doc × 10 chunk = 50 个，远低于 5000 上限 |
| **测试步骤** | 1. Mock Chroma 异常<br>2. Mock 50 个 chunk<br>3. 调用 `chat(message, kbId)` |
| **预期结果** | • 不输出 warn 日志<br>• 按余弦相似度降序返回 TopK |
| **验证方式** | 单元测试 |
| **状态** | ⏳ 建议实现 |

### TC-28-05: KB 无任何 COMPLETED 文档应返回明确提示

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-05 |
| **测试目标** | 边界场景：KB 为空或全 FAILED |
| **前置条件** | KB 存在但无 COMPLETED 文档 |
| **测试步骤** | 1. `documentRepository.findByKnowledgeBaseId` 返回空 / 全 FAILED<br>2. 调用 `chat(message, kbId)` |
| **预期结果** | 返回字符串 "该知识库暂无文档，请先上传文档。" |
| **验证方式** | 单元测试 |
| **状态** | ⏳ 建议实现（已存在代码逻辑，需要测试断言） |

### TC-28-06: EmbeddingService 超时不应阻塞调用方

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-06 |
| **测试目标** | 验证 FIX-007：Ollama 慢响应时 RestTemplate 在 30s 后抛超时异常 |
| **关联需求** | NFR-007（异常处理） |
| **前置条件** | Ollama 服务存在但响应慢（> 30s） |
| **测试步骤** | 1. Mock Ollama endpoint sleep 60s<br>2. 调用 `embeddingService.embed(text)`<br>3. 计时 |
| **预期结果** | • 30s ± 1s 内抛 `ResourceAccessException`<br>• `embed()` catch 后返回空数组 `float[0]`<br>• 日志输出 "向量化超时或网络异常"<br>• 调用方得到 `float[0]` 不会 NPE |
| **验证方式** | 集成测试（需要启动 Mock Server） |
| **状态** | ⏳ 建议实现 |

### TC-28-07: EmbeddingService 连接失败应安全降级

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-07 |
| **测试目标** | Ollama 完全不可用（连接拒绝） |
| **前置条件** | Ollama 服务未启动 |
| **测试步骤** | 1. 调用 `embeddingService.embed(text)` |
| **预期结果** | • 5s 内抛 `ResourceAccessException`<br>• 返回空数组<br>• 日志输出 |
| **验证方式** | 集成测试 |
| **状态** | ⏳ 建议实现 |

---

## 3. 性能基准测试（NFR-003）

### TC-28-08: 单次检索 P95 < 1s

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-08 |
| **测试目标** | 验证 NFR-003：向量检索 P95 < 1s |
| **前置条件** | 10 万向量；TopK=3 |
| **测试步骤** | 1. 启动 JMeter 或 Gatling<br>2. 并发 50 用户持续检索 60s<br>3. 采集 P50 / P95 / P99 |
| **预期结果** | • P95 < 1000ms<br>• 错误率 < 0.1% |
| **验证方式** | 性能测试 |
| **状态** | ⏳ 后续 ST 阶段执行 |

### TC-28-09: 检索 DB 查询次数在 100 并发下保持 1 次

| 项 | 内容 |
|----|------|
| **用例编号** | TC-28-09 |
| **测试目标** | 验证 N+1 修复在并发下不退化 |
| **前置条件** | 100 个 KB；每个 KB 下 50 个 doc |
| **测试步骤** | 1. JMeter 并发 100 用户查询不同 KB<br>2. 监控 MySQL QPS<br>3. 监控 SQL 是否仍为 1 次 findByKnowledgeBaseId |
| **预期结果** | • 100 并发下 `findByKnowledgeBaseId` 总 QPS = ~100 次/s<br>• 如果 N+1 未修，将是 100 × TopK 次/s |
| **验证方式** | 性能测试 + MySQL slow log |
| **状态** | ⏳ 后续 ST 阶段执行 |

---

## 4. 覆盖矩阵

| FIX | 场景 | TC |
|-----|------|-----|
| FIX-005 N+1 修复 | DB 查询次数 | TC-28-01 |
| FIX-005 | 状态过滤 | TC-28-02 |
| FIX-006 Fallback OOM | 超限截断 | TC-28-03 |
| FIX-006 | 正常返回 | TC-28-04 |
| FIX-006 | 空 KB | TC-28-05 |
| FIX-007 Embedding 超时 | 慢响应 | TC-28-06 |
| FIX-007 | 连接失败 | TC-28-07 |
| NFR-003 | P95 < 1s | TC-28-08 |
| NFR-003 | 并发不退化 | TC-28-09 |

---

## 5. 验收标准

- [ ] TC-28-01 ~ TC-28-07 单元测试待补全
- [ ] TC-28-08 / TC-28-09 性能测试待 ST 阶段执行
- [ ] 修复后所有用例必须通过

---

## 6. 备注

本次提交未自动添加 RAG 测试用例（避免修改 production-impacting 的代码覆盖率）。建议作为后续 PR 增量补全：
- `RagServiceTest.java` 新增
- `EmbeddingServiceTest.java` 新增（需要 Mock RestTemplate 或使用 WireMock）
- `performance/rag-search.jmx` 新增（JMeter 脚本）