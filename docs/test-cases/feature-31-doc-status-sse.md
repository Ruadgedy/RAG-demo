# Feature #31 — 文档状态实时同步（SSE 推送）

| 项目 | 内容 |
|------|------|
| **Feature ID** | #31 |
| **关联修复** | FIX-011 ~ FIX-016 |
| **关联类** | `DocumentStatusEvent`, `DocumentStatusEventService`, `DocumentController.streamDocumentStatus`, `useDocumentStream`, `useToast` |
| **关联需求** | FR-002（文档解析与向量化）、NFR-005（错误提示）、NFR-007（异常处理） |
| **优先级** | P0（用户体验） |
| **编写日期** | 2026-06-27 |

---

## 1. 功能概述

### 1.1 背景

旧版前端用 `setInterval` 每 2s 轮询文档状态，存在：
- 延迟高（≥2s）
- `alert()` 阻塞主线程
- 缺乐观插入，UI 看不到上传成功响应里的 Document
- 网络失败静默
- 大量请求浪费

### 1.2 新架构

- **后端**：Sinks.Many 事件总线 + SSE 端点
- **前端**：EventSource 订阅 + 降级轮询 + 全局 Toast
- **延迟**：从 ≥2s → <100ms

### 1.3 修复方案

详见 `docs/plans/2026-03-15-rag-qa-design.md` §10。

---

## 2. 测试用例

### 2.1 后端单元测试（DocumentStatusEventServiceTest）

#### TC-31-01: 单订阅者收到 emit 事件

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-01 |
| **测试目标** | 验证基本事件传递 |
| **前置条件** | 创建 sink，1 个订阅者 |
| **测试步骤** | 1. `service.getOrCreateSink(kbId).asFlux().subscribe(...)`<br>2. 等待订阅建立<br>3. `service.emit(kbId, event)` |
| **预期结果** | • 订阅者收到 1 个事件<br>• 事件字段完整（status, progress, documentId 等） |
| **验证方式** | `AtomicInteger` + `CountDownLatch` |
| **状态** | ✅ 已通过 |

#### TC-31-02: 多订阅者同时收到 emit

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-02 |
| **测试目标** | 验证 multicast 语义（一个 emit → 所有订阅者都收到） |
| **测试步骤** | 1. 创建 3 个订阅者<br>2. emit 1 次 |
| **预期结果** | • 3 个订阅者都收到 1 个事件 |
| **验证方式** | 3 个 `CountDownLatch` 计数 |
| **状态** | ✅ 已通过 |

#### TC-31-03: 无订阅者时 emit 静默丢弃

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-03 |
| **测试目标** | 防御性：emit 不应抛异常 |
| **测试步骤** | 不创建订阅者，直接 emit |
| **预期结果** | • 不抛异常<br>• sinkCount = 0（emit 不创建 sink） |
| **状态** | ✅ 已通过 |

#### TC-31-04: 同一 kbId 返回同一 sink 实例

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-04 |
| **测试目标** | 验证懒创建 + 单例语义 |
| **测试步骤** | 两次 `getOrCreateSink(kbId)` |
| **预期结果** | • 返回同一实例<br>• sinkCount = 1 |
| **状态** | ✅ 已通过 |

#### TC-31-05: removeSink 清理资源

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-05 |
| **测试目标** | KB 删除场景下的清理能力 |
| **测试步骤** | getOrCreateSink → removeSink |
| **预期结果** | • sinkCount = 0<br>• emit 后无 sink 也不报错 |
| **状态** | ✅ 已通过 |

#### TC-31-06: 并发 emit 不阻塞

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-06 |
| **测试目标** | 线程安全：多线程并发 emit 不抛异常 |
| **测试步骤** | 4 线程并发 emit 20 次 |
| **预期结果** | • 不抛异常<br>• emit 全部完成（订阅者收到的事件数 ≤ 20，符合 multicast 语义） |
| **状态** | ✅ 已通过 |

#### TC-31-07: 未注册 kbId 的 subscriberCount 为 0

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-07 |
| **测试目标** | 边界场景：监控/调试 |
| **状态** | ✅ 已通过 |

### 2.2 后端 Controller 测试（DocumentControllerStreamTest）

#### TC-31-08: 未认证 SSE 请求返回 4xx

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-08 |
| **测试目标** | 验证 Spring Security + 控制器内双重鉴权拦截 |
| **测试步骤** | 用 `with(anonymous())` 发 GET 请求 |
| **预期结果** | • 返回 4xx（403 from Spring Security 或 401 from controller）<br>• 不进入 SSE 推送循环 |
| **验证方式** | `status().is4xxClientError()` |
| **状态** | ✅ 已通过 |

#### TC-31-09: 已认证 SSE 请求建立连接

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-09 |
| **测试目标** | 验证 SSE 端点鉴权通过 |
| **测试步骤** | 用 `with(authentication(...))` 发请求 |
| **预期结果** | • 不返回 401/403<br>• 调用了 `eventService.getOrCreateSink(kbId)` |
| **验证方式** | `verify(eventService).getOrCreateSink(kbId)` |
| **状态** | ✅ 已通过 |

#### TC-31-10: 集成 — 上传文档后 SSE 收到状态事件（手动验证）

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-10 |
| **测试目标** | E2E：上传 → SSE 收到状态变更 |
| **测试步骤** | 1. `curl -N http://localhost:8080/api/knowledge-bases/{kbId}/documents/stream?token=xxx -H "Authorization: Bearer xxx"`<br>2. 同时另一个终端 `curl -X POST .../documents -F file=@test.pdf`<br>3. 观察 SSE 流输出 |
| **预期结果** | • SSE 收到多个 `event: doc-status` 消息<br>• 状态依次：UPLOADING → PARSING → CHUNKING → EMBEDDING → COMPLETED<br>• progress 递增：10 → 30 → 50 → 70 → 100 |
| **验证方式** | 手动 curl（无自动化） |
| **状态** | ⏳ ST 阶段 |

### 2.3 前端测试（无自动化，手动验证）

#### TC-31-11: SSE 连接成功 + 收到 doc-status 事件

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-11 |
| **测试目标** | 验证 EventSource composable 集成 |
| **前置条件** | 已登录，KB 已创建 |
| **测试步骤** | 1. 浏览器访问 `http://localhost:5173`<br>2. 选择 KB<br>3. 上传 PDF<br>4. 观察 Network 面板的 `documents/stream` 连接 |
| **预期结果** | • Network 面板显示持续的 SSE 连接（pending 状态）<br>• DevTools Console 无 EventSource 错误<br>• 文档列表实时更新状态 |
| **状态** | ⏳ 手动 |

#### TC-31-12: SSE 断线自动重连

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-12 |
| **测试目标** | 验证 exp backoff 重连 |
| **测试步骤** | 1. SSE 连接成功后，关闭后端<br>2. 等待 5s<br>3. 重启后端<br>4. 观察前端是否自动恢复 |
| **预期结果** | • 关闭后前端 fallback 自动启用（轮询）<br>• 重启后 SSE 自动重连<br>• 恢复后 fallback 停止 |
| **状态** | ⏳ 手动 |

#### TC-31-13: SSE 失败降级到轮询

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-13 |
| **测试目标** | 验证重连失败 6 次后切换轮询 |
| **测试步骤** | 1. 启动前端<br>2. 让后端保持停止状态<br>3. 等 60s+ 观察前端行为 |
| **预期结果** | • 6 次重连失败后降级到轮询<br>• Console 输出 warn 日志<br>• UI 仍能通过轮询显示状态 |
| **状态** | ⏳ 手动 |

#### TC-31-14: Toast 显示上传成功（非 alert）

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-14 |
| **测试目标** | 验证 alert 被 toast 替换 |
| **测试步骤** | 上传 PDF 成功 |
| **预期结果** | • 右上角出现绿色 toast "文档上传成功，正在处理..."<br>• 3s 后自动消失<br>• 上传按钮立即恢复可点击（不阻塞） |
| **状态** | ⏳ 手动 |

#### TC-31-15: 乐观插入 — 新文档立刻显示

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-15 |
| **测试目标** | 验证上传响应里的 Document 立即 push 到列表 |
| **测试步骤** | 上传 PDF |
| **预期结果** | • 关闭模态框后立即看到新文档（progress=10%, status=UPLOADING）<br>• 不需要等 SSE 推送 |
| **状态** | ⏳ 手动 |

#### TC-31-16: 切换 KB 时停止旧订阅

| 项 | 内容 |
|----|------|
| **用例编号** | TC-31-16 |
| **测试目标** | 验证资源清理 |
| **测试步骤** | 1. KB A 上传文档<br>2. 切到 KB B<br>3. 切回 KB A |
| **预期结果** | • Network 面板显示 A → B 切换时旧 SSE 连接关闭<br>• 切回 A 时新建 SSE 连接<br>• 文档列表正确（不串数据） |
| **状态** | ⏳ 手动 |

---

## 3. 覆盖矩阵

| FIX | 场景 | TC |
|-----|------|-----|
| FIX-011 | 单订阅者 | TC-31-01 |
| FIX-011 | 多订阅者 | TC-31-02 |
| FIX-011 | 无订阅者 | TC-31-03 |
| FIX-011 | 单例 sink | TC-31-04 |
| FIX-011 | 清理 | TC-31-05 |
| FIX-011 | 并发 | TC-31-06 |
| FIX-011 | 监控 | TC-31-07 |
| FIX-012 | 鉴权拦截 | TC-31-08 |
| FIX-012 | 鉴权通过 | TC-31-09 |
| FIX-013 | E2E | TC-31-10 |
| FIX-014 | 前端连接 | TC-31-11 |
| FIX-014 | 重连 | TC-31-12 |
| FIX-015 | 降级轮询 | TC-31-13 |
| FIX-016 | Toast | TC-31-14 |
| FIX-014 | 乐观插入 | TC-31-15 |
| FIX-014 | 资源清理 | TC-31-16 |

---

## 4. 验收标准

- [x] TC-31-01 ~ TC-31-07 后端单元测试通过（7 个）
- [x] TC-31-08 ~ TC-31-09 后端 controller 测试通过（2 个）
- [ ] TC-31-10 E2E 集成测试待 ST 阶段
- [ ] TC-31-11 ~ TC-31-16 前端手动测试待执行

---

## 5. 监控建议

生产环境应增加：

```yaml
# Micrometer 指标
metrics:
  ragqa.sse.connections.active: gauge
  ragqa.sse.events.emitted: counter (label by kbId)
  ragqa.sse.reconnects: counter
  ragqa.sse.fallback.activated: counter  # 触发 fallback 次数
```

告警规则：
- 30s 内 `ragqa.sse.events.emitted` 增长 > 100 → 可能上游处理异常快（异常状态）
- `ragqa.sse.connections.active` 持续为 0 但有 KB 活动 → SSE 故障

---

## 6. 备注

本次新增 `DocumentStatusEventService`、`DocumentController.streamDocumentStatus`、`useDocumentStream`、`useToast` 四个核心组件，覆盖 16 个测试用例。

**已知限制**（SDD §10.4 跟踪）：
1. EventSource 无法携带 Authorization header，token 通过 query param 传递
2. 生产部署建议改 HttpOnly Cookie + CSRF Token

**未来优化**：
1. 引入 Micrometer 监控 SSE 健康度
2. 拆分 ChatView.vue（当前 900+ 行）到多个小组件
3. 前端 Vitest 基础设施搭建（目前无前端测试）