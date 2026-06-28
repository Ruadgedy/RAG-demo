# 增量修复：聊天历史未落库 + 流式模式双重显示

> 日期：2026-06-28
> 分支：dev
> 提交：fix(chat-history): 彻底修复落库失败 + 消除流式模式「两个 AI 回复」反模式

---

## 一、问题描述

用户反馈两个长期未修复的严重 bug：

1. **界面显示两个 AI 回复** —— 用户发问后，看到「正常回答」+「卡在正在输出」两个 AI 头像；第二次卡住导致用户无法继续提问
2. **聊天历史从未落库** —— 多轮修复均告失败，`chat_history` 表一直是空的

---

## 二、根因分析

### 问题 1：流式模式双重显示

前端 `ChatView.vue` 在流式模式下**同时**显示两个 AI 容器：

| 容器 | 触发条件 | 显示内容 |
| --- | --- | --- |
| `v-for` 遍历 `messages` 中的空 assistant 占位 | `sendStreamMessage` 提前 `push({ role: 'assistant', content: '' })` | 逐字填充 → "正常回答" |
| `v-if="loading"` 的独立 loading 块 | `loading.value = true` | 三个跳动的点 → "卡在正在输出" |

同一个 AI 位置出现**两个 div**：一个是 messages 列表中流式累积的 assistant 消息，一个是独立的 loading dots。两者同时显示让用户感觉是「两次回答」。若后端流式异常，loading 卡 true 则"第二个"永远卡死。

### 问题 2：落库失败（双重 bug）

**根因 A：V2 迁移从未执行**
- `flyway_schema_history` 只有 baseline 记录，V1/V2 都没跑过
- 旧 jar 包里 V2 SQL 根本没被打进去（`unzip -l ... | grep V2` 为空）
- 现有 jar 是 V2 提交**之前**编的，部署环境永远停在 V1

**根因 B：`user_id` 列不存在**
- `DESCRIBE chat_history` 显示无 `user_id` 列
- `ChatHistory` 实体新增了 `@Column(name = "user_id")`
- `saveAndFlush()` 写库时 MySQL 报 `Unknown column 'user_id' in 'field list'`

**根因 C：`saveHistory()` 静默吞异常**
```java
// 修复前
private void saveHistory(...) {
    try {
        chatHistoryRepository.saveAndFlush(history);
    } catch (Exception e) {
        log.error(...);   // ← 只打日志，异常被吞，前端无感知
    }
}
```
schema 不匹配的 SQL 错误被 `try/catch` 完全吞掉，前端只看到 AI 正常回答，**这个反模式让 bug 隐藏了 N 轮**。

---

## 三、修复方案

### 修复 1：消除双重显示

**文件**：`rag-qa-frontend/src/views/ChatView.vue`

```vue
<!-- 修复前 -->
<div v-if="loading" class="message assistant">...</div>

<!-- 修复后 -->
<!-- 流式模式不显示此块，messages 中的占位 assistant 消息已充当 loading 指示 -->
<div v-if="loading && !streamMode" class="message assistant">...</div>
```

- **流式模式**：`messages` 中的空 assistant 占位逐字填充 + 不显示独立 loading 块 → 只有一个 AI 容器
- **非流式模式**：`loading=true` 时显示独立 loading dots → 用户看到等待动画
- **完成后**：`loading=false` → 都不显示

### 修复 2：让聊天历史真正落库

**文件**：`rag-qa-backend/src/main/java/com/ragqa/service/ChatService.java`

1. **不再静默吞异常**
   ```java
   // 修复后
   private void saveHistory(...) {
       ChatHistory saved = chatHistoryRepository.saveAndFlush(history);
       log.info("聊天历史已落库: id={}, ...", saved.getId(), ...);
       // 抛异常给调用方
   }
   ```
   所有 `saveHistory()` 调用点（`chat()` / `streamChat()` / doOnComplete / doOnError / 错误分支）都包一层 `try/catch`，失败时打 `log.warn("[落库告警] ...")` 告警，**不再静默**。

2. **手动修复当前数据库状态**
   ```sql
   -- 1) 删除旧 jar 启动时 Flyway 留下的 V2 失败记录
   DELETE FROM flyway_schema_history WHERE version = '2' AND success = 0;
   -- 2) 删除手动 ALTER 加的 user_id 列（让 Flyway 干净跑 V2）
   ALTER TABLE chat_history DROP COLUMN user_id;
   ```
   然后重启后端，Flyway 自动成功跑 V2（已验证 `success=1`）。

3. **重新打包 jar 让 V2 SQL 进入产物**
   ```
   mvn -DskipTests clean package
   # 验证
   unzip -l target/rag-qa-backend-1.0.0-SNAPSHOT.jar | grep V2
   # → BOOT-INF/classes/db/migration/V2__add_user_id_to_chat_history.sql
   ```

---

## 四、端到端验证

### 验证 1：流式问答落库

```bash
TOKEN=...   # 新用户 vtest001
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"流式测试：什么是边缘计算","knowledgeBaseId":"..."}' \
  http://localhost:8080/api/chat/stream
```

**SSE 响应**（多段正确流式返回）：
```
data:抱歉，知识库中没有找到与您问题相关的内容。
data:虽然参考文档中多次提到"云边协同"...
```

**数据库落库**（2 条记录，sessionId 相同，user_id 正确）：
| role | content | user_id | created_at |
| --- | --- | --- | --- |
| user | 流式测试：什么是边缘计算 | vtest001 | 2026-06-28 12:07:46 |
| assistant | 抱歉，知识库中没有找到... | vtest001 | 2026-06-28 12:07:52 |

### 验证 2：非流式问答落库

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"这个知识库有什么内容","knowledgeBaseId":"..."}' \
  http://localhost:8080/api/chat
```

返回 `{sessionId, answer}`，DB 同样有 user + assistant 两条记录。

### 验证 3：Flyway V2 成功

```
mysql> SELECT * FROM flyway_schema_history;
installed_rank | version | description              | success
1              | 1       | << Flyway Baseline >>    | 1
2              | 2       | add user id to chat history | 1  ← 本次启动自动跑成功
```

---

## 五、变更清单

| 文件 | 变更 |
| --- | --- |
| `rag-qa-backend/src/main/java/com/ragqa/service/ChatService.java` | `saveHistory()` 不再静默吞异常；所有调用点加 `try/catch` + `log.warn("[落库告警] ...")` 告警；更新类级别 Javadoc |
| `rag-qa-frontend/src/views/ChatView.vue` | loading 块改为 `v-if="loading && !streamMode"`；补详细注释 |
| `rag-qa-backend/target/rag-qa-backend-1.0.0-SNAPSHOT.jar` | 重新打包，V2 SQL 包含进 jar |
| 数据库（手动） | 清理 V2 失败记录 + user_id 列 → Flyway 自动跑 V2 |

---

## 六、遗留 / 后续

- [ ] **回归测试**：建议补前端 e2e 测试覆盖「流式模式只显示一个 AI 头像」+「数据库 chat_history 写入成功」
- [ ] **监控告警**：建议在生产环境把 `log.warn("[落库告警] ...")` 转给告警平台（钉钉/飞书机器人），避免再次出现"AI 正常但 DB 0 条"的反模式
- [ ] **CI 加固**：建议在 CI 中加 `mvn verify` 强制跑 Flyway 验证（`validate-on-migrate=true` 已开启），避免 schema 不匹配流入生产
