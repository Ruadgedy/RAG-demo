# Feature #30 — 文档处理状态机卡死恢复

| 项目 | 内容 |
|------|------|
| **Feature ID** | #30 |
| **关联修复** | FIX-009 |
| **关联类** | `DocumentProcessRecoveryScheduler`, `DocumentRepository.findStuckDocuments()` |
| **关联需求** | FR-002（文档解析与向量化）、NFR-006（数据持久化）、NFR-007（异常处理） |
| **优先级** | P0（可靠性） |
| **编写日期** | 2026-06-27 |

---

## 1. 功能概述

### 1.1 背景

`DocumentProcessService.processDocumentAsync()` 是 `@Async` 任务，执行流程：

```
UPLOADING → PARSING → CHUNKING → EMBEDDING → COMPLETED
```

整个流程可能耗时 5-30 分钟（取决于文档大小 + Ollama 性能）。期间发生以下事件会导致文档**永远卡在中间态**：

- 服务重启（kill -9 / OOM Kill）
- 节点宕机
- 进程被 systemd 强制停止
- JVM crash

用户只能"重新上传"，浪费存储。前端显示"处理中"但永远不会完成。

### 1.2 修复方案

新增 `DocumentProcessRecoveryScheduler`：

```java
@Scheduled(fixedDelayString = "${document.recovery.interval-ms:300000}", initialDelay = 60000)
@Transactional
public void recoverStuckDocuments() {
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeoutMinutes);
    List<Document> stuckDocs = documentRepository.findStuckDocuments(PROCESSING_STATES, threshold);
    for (Document doc : stuckDocs) {
        doc.setStatus(Document.DocumentStatus.FAILED);
        doc.setErrorMessage("文档处理超时（卡在 X 状态超过 N 分钟），可能因服务重启/OOM 终止。");
        documentRepository.save(doc);
    }
}
```

**默认配置**：
- 触发间隔：5 分钟（`interval-ms=300000`）
- 超时阈值：30 分钟（`timeout-minutes=30`）
- 启动延迟：60 秒（`initialDelay=60000`，避开启动期竞争）

---

## 2. 测试用例

### 2.1 单元测试

#### TC-30-01: 正常路径 — 无卡死文档时不应执行任何 save

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-01 |
| **测试目标** | 验证无卡死文档时 scheduler 是 no-op |
| **前置条件** | `findStuckDocuments` 返回空列表 |
| **测试步骤** | 1. Mock repository 返回 []<br>2. 调用 `recoverStuckDocuments()` |
| **预期结果** | • `repository.save()` 从未被调用<br>• 无异常抛出 |
| **验证方式** | verify(repository, never()).save(any()) |
| **状态** | ⏳ 建议实现 |

#### TC-30-02: 检测到 1 个卡死 PARSING 文档应标 FAILED

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-02 |
| **测试目标** | 验证基本恢复逻辑 |
| **前置条件** | `findStuckDocuments` 返回 1 个 doc（status=PARSING, uploaded_at = 31 分钟前） |
| **测试步骤** | 1. Mock repository<br>2. 调用 `recoverStuckDocuments()`<br>3. 验证 doc 的新状态 |
| **预期结果** | • doc.status == FAILED<br>• doc.errorMessage 含 "文档处理超时（卡在 PARSING 状态超过 30 分钟）"<br>• `repository.save(doc)` 被调用 |
| **验证方式** | ArgumentCaptor + assertThat |
| **状态** | ⏳ 建议实现 |

#### TC-30-03: 批量恢复 — 多个卡死文档

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-03 |
| **测试目标** | 验证批量处理逻辑 |
| **前置条件** | `findStuckDocuments` 返回 5 个卡死文档 |
| **测试步骤** | 1. Mock 5 个 doc<br>2. 调用 `recoverStuckDocuments()` |
| **预期结果** | • 5 个 doc 全部被 save<br>• 每个 doc 的 errorMessage 都包含其原状态名 |
| **验证方式** | verify(times(5)) |
| **状态** | ⏳ 建议实现 |

#### TC-30-04: 仅扫描 PROCESSING 状态（不含 COMPLETED / FAILED）

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-04 |
| **测试目标** | 验证状态过滤条件正确 |
| **测试方法** | 直接测试 `DocumentRepository.findStuckDocuments()` |
| **测试数据** | 数据库中存在 4 种状态的 doc：UPLOADING(超时)、PARSING(超时)、COMPLETED(超时)、FAILED(超时) |
| **测试步骤** | 1. 准备数据<br>2. 调用 `findStuckDocuments(PROCESSING_STATES, threshold)` |
| **预期结果** | • 仅返回 UPLOADING + PARSING<br>• COMPLETED 和 FAILED 被过滤 |
| **验证方式** | @DataJpaTest |
| **状态** | ⏳ 建议实现 |

#### TC-30-05: 仅扫描超过阈值的文档

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-05 |
| **测试目标** | 验证时间阈值过滤 |
| **测试数据** | 文档 A uploaded_at = 31 分钟前（应恢复）；文档 B uploaded_at = 5 分钟前（不应恢复） |
| **测试步骤** | 1. 调用 `findStuckDocuments(PROCESSING_STATES, now-30min)` |
| **预期结果** | • 仅返回文档 A |
| **验证方式** | @DataJpaTest |
| **状态** | ⏳ 建议实现 |

#### TC-30-06: 调度器频率为 5 分钟（fixedDelay）

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-06 |
| **测试目标** | 验证 `@Scheduled` 注解正确 |
| **前置条件** | SpringBootTest 加载完整上下文 |
| **测试步骤** | 1. 启动应用<br>2. 等待 11 分钟<br>3. 统计 `recoverStuckDocuments()` 调用次数 |
| **预期结果** | • 至少调用 2 次（启动 60s 后 + 间隔 5min × 2） |
| **验证方式** | Awaitility + Mockito spy |
| **状态** | ⏳ 建议实现 |

### 2.2 集成测试

#### TC-30-07: E2E — 服务重启后卡死文档被自动恢复

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-07 |
| **测试目标** | 模拟真实故障：上传 → 模拟服务崩溃 → 重启 → 验证恢复 |
| **前置条件** | MySQL + Chroma 可用；Ollama mock |
| **测试步骤** | 1. POST 上传 PDF 到 `/api/knowledge-bases/{kbId}/documents`<br>2. 等 5s 让 status 进入 PARSING<br>3. `kill -9` Spring Boot 进程<br>4. 启动 Spring Boot<br>5. 等待 6 分钟（initialDelay=60s + fixedDelay 一次）<br>6. 查询该 doc 状态 |
| **预期结果** | • doc.status == FAILED<br>• doc.errorMessage 含 "文档处理超时" |
| **验证方式** | 集成测试（手工或脚本驱动） |
| **状态** | ⏳ 建议 ST 阶段执行 |

### 2.3 边界 / 异常测试

#### TC-30-08: save 失败不应中断循环

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-08 |
| **测试目标** | 防御性容错 |
| **前置条件** | `findStuckDocuments` 返回 3 个 doc；第 2 个 save 抛异常 |
| **测试步骤** | 1. Mock 3 个 doc<br>2. Mock 第 2 次 save 抛 `DataIntegrityViolationException`<br>3. 调用 `recoverStuckDocuments()` |
| **预期结果** | • 第 1 个 save 成功<br>• 第 3 个 save 也尝试（不被第 2 个中断）<br>• 异常被 catch 但不向上抛<br>• 调度任务继续按周期执行 |
| **验证方式** | verify(times(3)) + doThrow |
| **状态** | ⏳ 建议实现 |

#### TC-30-09: 调度任务在 @Transactional 内执行

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-09 |
| **测试目标** | 验证事务边界：单个 recover 周期是 1 个事务 |
| **前置条件** | findStuckDocuments 返回 3 个 |
| **测试步骤** | 1. Spy scheduler bean<br>2. 调用 `recoverStuckDocuments()`<br>3. 验证 `TransactionTemplate` 执行次数 |
| **预期结果** | • 整个方法在 1 个事务中<br>• 任何 save 失败全部回滚 |
| **验证方式** | Spring TestTransactionListener |
| **状态** | ⏳ 建议实现 |

#### TC-30-10: 配置可调整（不硬编码）

| 项 | 内容 |
|----|------|
| **用例编号** | TC-30-10 |
| **测试目标** | 验证 `interval-ms` 和 `timeout-minutes` 可通过 application.properties 配置 |
| **测试步骤** | 1. 设置 `document.recovery.timeout-minutes=10`<br>2. 设置 `document.recovery.interval-ms=60000`<br>3. 启动应用<br>4. 上传文档 11 分钟后查状态 |
| **预期结果** | • 11 分钟后 doc 自动 FAILED（即使默认是 30 分钟）<br>• 调度间隔 60s（默认 5min） |
| **验证方式** | @SpringBootTest(properties = {...}) |
| **状态** | ⏳ 建议实现 |

---

## 3. 覆盖矩阵

| FIX | 场景 | TC |
|-----|------|-----|
| FIX-009 调度器 | 正常 no-op | TC-30-01 |
| FIX-009 | 单文档恢复 | TC-30-02 |
| FIX-009 | 批量恢复 | TC-30-03 |
| FIX-009 | 状态过滤 | TC-30-04 |
| FIX-009 | 时间过滤 | TC-30-05 |
| FIX-009 | 调度频率 | TC-30-06 |
| 集成 | E2E 故障恢复 | TC-30-07 |
| 容错 | save 失败继续 | TC-30-08 |
| 事务 | @Transactional 边界 | TC-30-09 |
| 配置 | 参数可调整 | TC-30-10 |

---

## 4. 验收标准

- [ ] TC-30-01 ~ TC-30-06 单元测试待补全（建议作为下一 PR）
- [ ] TC-30-07 E2E 集成测试待 ST 阶段
- [ ] TC-30-08 ~ TC-30-10 容错 + 配置测试待补全

---

## 5. 监控与告警建议

建议在生产环境增加以下监控：

1. **调度器心跳**：每执行一次 `recoverStuckDocuments()` 输出 INFO 日志 + metrics
   ```java
   log.info("Recovered {} stuck documents", recovered);
   meterRegistry.counter("ragqa.recovery.total").increment(recovered);
   ```

2. **告警规则**：
   - 5 分钟内恢复的文档数 > 10 → 告警（可能上游有故障）
   - 连续 3 个周期 0 恢复但仍有 PROCESSING 文档 → 告警（调度器本身故障）

3. **Dashboard 面板**：
   - 当前 PROCESSING 状态文档数（按状态分组）
   - 过去 24 小时恢复次数
   - 平均处理时长

---

## 6. 备注

本次提交新增了 `DocumentProcessRecoveryScheduler.java`，但单元测试代码建议作为独立 PR 增量：

```java
@ExtendWith(MockitoExtension.class)
class DocumentProcessRecoverySchedulerTest {
    @Mock private DocumentRepository documentRepository;
    @InjectMocks private DocumentProcessRecoveryScheduler scheduler;

    @Test
    void shouldMarkStuckDocumentsAsFailed() {
        // ... 待实现
    }
}
```

调度任务配置：
```properties
# application.properties
document.recovery.timeout-minutes=30
document.recovery.interval-ms=300000
```