# Feature #32 — Chroma Collection 409 修复

| 项目 | 内容 |
|------|------|
| **Feature ID** | #32 |
| **关联修复** | FIX-017 |
| **关联类** | `ChromaService.getOrCreateCollectionId()` |
| **关联需求** | FR-002（文档解析与向量化）、NFR-007（异常处理） |
| **优先级** | P0（功能完全阻塞） |
| **编写日期** | 2026-06-27 |

---

## 1. 背景

### 1.1 现象

用户上传 71 切片 PDF 后日志：

```
向量化完成，成功: 0, 失败: 71
文档处理完成，切片数量: 0
```

每个切片的 add 失败原因相同：

```
添加向量到Chroma异常: docId=..., error=Chroma API error: 409 -
{"error":"ChromaError","message":"Collection [rag-qa-collection] already exists"}
```

### 1.2 根因

`ChromaService.getOrCreateCollectionId()` 方法名虽是 "GetOrCreate"，但实现只有 POST：
- 第 1 个切片 add：collection 不存在 → POST 创建成功 → 返回 id → add 成功
- 第 2~71 个切片 add：再次 POST 同名 collection → Chroma v2 严格返回 409 → 整个 add 抛 IOException → 上层 catch 后 FAILED

71 个切片有 1 个偶然成功、70 个因 409 失败（实际为 0 成功因 IOException 在外层被 catch 后重试或重置后再次失败）。

### 1.3 触发条件

- Chroma ≥ 1.0（强制 v2 API）
- `addDocument` 被调用 ≥ 2 次（同一进程生命周期内）

---

## 2. 修复方案

### 2.1 核心改动

```java
// ChromaService.java
private volatile String cachedCollectionId;
private final ConcurrentHashMap<String, Object> resolveLocks = new ConcurrentHashMap<>();

private String getOrCreateCollectionId() throws IOException {
    String cached = cachedCollectionId;
    if (cached != null) return cached;              // 1. 命中缓存

    Object lock = resolveLocks.computeIfAbsent(collectionName, k -> new Object());
    synchronized (lock) {
        if (cachedCollectionId != null) return cachedCollectionId;  // 2. 双重检查

        // 3. GET 列表按 name 命中
        String listEndpoint = "/api/v2/tenants/" + tenantName + "/databases/" + databaseName + "/collections";
        JsonNode listRoot = objectMapper.readTree(getFromChroma(listEndpoint));
        if (listRoot.isArray()) {
            for (JsonNode node : listRoot) {
                if (collectionName.equals(node.get("name").asText())) {
                    cachedCollectionId = node.get("id").asText();    // 4. 缓存 id
                    return cachedCollectionId;
                }
            }
        }

        // 5. 不存在再 POST 创建
        String createResponse = postToChroma(listEndpoint, jsonBody);
        cachedCollectionId = createRoot.get("id").asText();
        return cachedCollectionId;
    }
}
```

新增辅助：
- `getFromChroma(endpoint)` — 通用 GET helper（GET 没有 body，需要新的连接处理路径）
- `invalidateCollectionIdCache()` — 外部（KB 删除/重建场景）手动失效缓存

### 2.2 并发安全

- `volatile` 字段保证多线程可见性
- `synchronized(lock)` 保证同 collectionName 首调只有一个线程发请求
- 双重检查：进入临界区后再次判断缓存，避免 lock 获取顺序差异
- 不同 collectionName 互不阻塞（锁粒度按名称）

---

## 3. 验证

### 3.1 自动化测试

| 项 | 结果 |
|----|------|
| `JWT_SECRET=... mvn test` | 54/54 通过 |
| 编译 | 0 错误 0 警告 |

### 3.2 E2E 测试

**输入**：`/tmp/cloud.pdf`（云计算白皮书，1.5MB，PDFBox 解析产出 71 切片）

**脚本**：`/tmp/e2e_chroma.sh`
1. POST `/api/auth/register` 拿 token
2. POST `/api/knowledge-bases` 建 KB
3. POST `/api/knowledge-bases/{id}/documents` 上传
4. 轮询 `/api/knowledge-bases/{id}/documents`，等待终态

**结果**：

```
[30s] COMPLETED 100 None
✅ 全部成功
```

**后端日志关键行**：

```
切片 71 向量化完成
向量化完成，成功: 71, 失败: 0
```

**Chroma 服务端校验**：

```bash
$ curl http://localhost:8000/api/v2/tenants/SpringAiTenant/databases/SpringAiDatabase/collections/b86bf2d0.../count
71
```

### 3.3 回归确认

| 指标 | 修复前 | 修复后 |
|------|--------|--------|
| 409 出现次数 | 70 | **0** |
| 向量化成功数 | 0/71 | **71/71** |
| Chroma 实际向量数 | 1（首片残留）| **71** |
| 文档最终状态 | FAILED | **COMPLETED** |
| 单元测试 | 54/54 | **54/54** |

---

## 4. 已知限制与后续优化

1. **缓存未自动失效**：当前仅依赖进程重启 + 手动 `invalidateCollectionIdCache()` 失效。如未来需要动态删除/重建 collection，需在 `KnowledgeBaseService.delete()` 中接入。
2. **无 collection 级联删除**：当前 `deleteByDocumentId()` 只删向量、不删 collection 本身。多 KB 共用一个 collection 是当前架构特性，暂不需要级联。
3. **缓存与 Chroma 不同步风险**：若外部直接通过 REST API 删除 collection，下一次 `addDocument` 会拿到失效 id → 404。当前无自动检测机制，建议加 `peek` 健康检查或缩短缓存 TTL（当前为进程级永久）。

---

## 5. 监控建议（生产）

```yaml
metrics:
  ragqa.chroma.collection.cache.hit: counter
  ragqa.chroma.collection.cache.miss: counter
  ragqa.chroma.add.409.rate: gauge  # 应永远为 0；>0 立即告警
  ragqa.chroma.errors.by_status: counter (label=409/404/500)
```

---

## 6. 备注

本次修复为最小变更：仅改 `ChromaService.java` 一个文件，公共 API（`addDocument`、`similaritySearch`、`deleteByDocumentId`）签名零变动。

**未来优化方向**：
1. 接入 Micrometer 暴露缓存命中率 + 409 速率
2. `KnowledgeBaseService.delete()` 调用 `invalidateCollectionIdCache()`
3. 拆分 `ChromaService` 到独立 client 类，便于替换 Spring AI 原生 `ChromaVectorStore`