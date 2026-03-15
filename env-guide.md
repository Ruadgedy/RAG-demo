# 环境服务指南

> 用户可编辑。Claude读取此文件来管理服务。端口变化或新增服务时请更新。

## 服务列表

| 服务名称 | 端口 | 启动命令 | 停止命令 | 验证URL |
|----------|------|----------|----------|---------|
| 后端API | 8080 | `cd rag-qa-backend && mvn spring-boot:run` | `lsof -ti :8080 \| xargs kill -9` | http://localhost:8080/actuator/health |
| 前端开发 | 5173 | `cd rag-qa-frontend && npm run dev` | `lsof -ti :5173 \| xargs kill -9` | http://localhost:5173 |

## 启动所有服务

### 后端 (macOS/Unix)
```bash
cd rag-qa-backend && mvn spring-boot:run > /tmp/rag-qa-backend.log 2>&1 &
sleep 10
head -50 /tmp/rag-qa-backend.log
# → 从输出中提取PID和端口，记录到task-progress.md
```

### 前端 (macOS/Unix)
```bash
cd rag-qa-frontend && npm run dev > /tmp/rag-qa-frontend.log 2>&1 &
sleep 5
head -30 /tmp/rag-qa-frontend.log
# → 从输出中提取PID和端口，记录到task-progress.md
```

## 验证服务运行

```bash
# 验证后端
curl -f http://localhost:8080/actuator/health

# 验证前端
curl -f http://localhost:5173
```

## 停止所有服务

### 按端口停止 (macOS/Unix)
```bash
# 后端
lsof -ti :8080 | xargs kill -9

# 前端
lsof -ti :5173 | xargs kill -9
```

### 按PID停止 (如果有记录)
```bash
kill <PID>
```

## 验证服务停止

```bash
# 端口应无输出
lsof -i :8080
lsof -i :5173
```

## 重启协议 (4步)

1. **Kill** — 停止所有服务（按PID或端口）
2. **Verify dead** — 运行验证服务停止，端口5秒内必须无响应
3. **Start** — 启动所有服务并捕获输出 → 提取新PID/端口 → 更新task-progress.md
4. **Verify alive** — 运行验证服务运行，10秒内健康检查必须响应

## 注意事项

- 首次启动后端需要下载依赖，可能需要较长时间
- 确保.env文件中已配置LLM API Key
- 前端依赖Node.js环境，请先运行 `npm install`
