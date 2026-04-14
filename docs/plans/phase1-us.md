# Phase 1 工程基础 — 详细用户故事

> 阶段目标：让项目可快速上手、可测试、可部署
> 整理时间：2026-04-14

---

## 1. README.md + Docker Compose 快速启动

### US-1.1: 项目介绍 README
**作为** 开发者  
**我想要** 打开 README 就知道项目是什么、解决什么问题  
**以便** 快速评估是否要使用这个项目

**验收标准：**
- [ ] README 包含 1 张产品截图或 GIF
- [ ] 包含功能特性列表（5-8 条）
- [ ] 包含适用场景说明
- [ ] 包含技术栈图标和版本号
- [ ] README 长度不超过 200 行

### US-1.2: 快速启动文档
**作为** 开发者  
**我想要** 按照 README 在 5 分钟内跑起项目  
**以便** 无需阅读大量文档就能体验产品

**验收标准：**
- [ ] 前置条件清单（Java 17+、Docker、Node 18+）
- [ ] 3 步启动命令（后端 + 前端 + Chroma）
- [ ] 启动后访问地址说明（前端 http://localhost:5173）
- [ ] 演示账号/测试数据说明
- [ ] 常见启动错误及解决方案（FAQ）

### US-1.3: Docker Compose 一键部署
**作为** 运维工程师  
**我想要** 一条命令启动完整开发环境  
**以便** 避免手动配置各种服务

**验收标准：**
- [ ] `docker-compose up` 启动 MySQL + Chroma + 后端 + 前端
- [ ] 所有服务健康检查通过
- [ ] `docker-compose down` 可干净清理
- [ ] volume 数据持久化
- [ ] 开发模式热重载生效

### US-1.4: 环境变量配置模板
**作为** 开发者  
**我想要** 有环境变量模板，不用手动查文档配置  
**以便** 快速配置 API key 和数据库连接

**验收标准：**
- [ ] `.env.example` 文件包含所有配置项
- [ ] 每个配置项有中文注释说明
- [ ] 敏感配置项标记为必填
- [ ] 本地 `.env` 文件加入 `.gitignore`

---

## 2. GitHub Actions CI/CD 流水线

### US-2.1: 持续集成流水线
**作为** 开发者  
**我想要** 每次 push 自动运行构建和测试  
**以便** 尽早发现代码问题

**验收标准：**
- [ ] PR 和 main 分支 push 触发 CI
- [ ] 检查代码格式（Checkstyle / Spotless）
- [ ] 编译项目（mvn compile）
- [ ] 运行单元测试
- [ ] 构建 Docker 镜像
- [ ] CI 失败时 PR 显示红 ❌
- [ ] CI 成功时 PR 显示绿 ✅

### US-2.2: CI 状态徽章
**作为** 开发者  
**我想要** README 显示 CI 状态徽章  
**以便** 一眼看出项目健康状态

**验收标准：**
- [ ] README 顶部有 GitHub Actions 状态徽章
- [ ] 徽章链接到 Actions 页面
- [ ] 徽章显示最新一次运行结果

---

## 3. API 文档（SpringDoc OpenAPI）

### US-3.1: OpenAPI 3.0 文档生成
**作为** API 消费者  
**我想要** 自动生成的 API 文档  
**以便** 了解有哪些接口可用

**验收标准：**
- [ ] 访问 `/swagger-ui.html` 可查看 API 文档
- [ ] 访问 `/v3/api-docs` 可获取 JSON 规范
- [ ] 所有 Controller 接口都有注释
- [ ] 请求/响应 DTO 有字段说明
- [ ] 认证接口标注清楚（JWT Bearer Token）

### US-3.2: API 文档增强
**作为** 前端开发者  
**我想要** API 文档包含示例请求/响应  
**以便** 更快对接接口

**验收标准：**
- [ ] 关键接口有 `example` 示例
- [ ] 错误响应有说明（400/401/403/500）
- [ ] 支持在线调试（Try it out）

---

## 4. Dockerfile 支持

### US-4.1: 后端 Dockerfile
**作为** 运维工程师  
**我想要** 后端有 Docker 镜像构建文件  
**以便** 容器化部署

**验收标准：**
- [ ] 多阶段构建（build stage + runtime stage）
- [ ] 使用 Eclipse Temurin JDK 17 精简镜像
- [ ] 镜像大小控制在 300MB 以内
- [ ] 健康检查配置（HEALTHCHECK）
- [ ] 非 root 用户运行

### US-4.2: 前端 Dockerfile
**作为** 运维工程师  
**我想要** 前端有 Docker 镜像构建文件  
**以便** 前后端统一部署

**验收标准：**
- [ ] 多阶段构建（build stage + nginx stage）
- [ ] 使用 nginx 作为生产服务器
- [ ] API 代理配置正确
- [ ] 静态资源 gzip 压缩

---

## 5. 单元测试覆盖率提升

### US-5.1: 测试基础架构
**作为** 开发者  
**我想要** 测试框架已配置好，直接写测试用例  
**以便** 方便地添加单元测试

**验收标准：**
- [ ] Spring Boot Test 依赖已配置
- [ ] JUnit 5 + Mockito 已引入
- [ ] 测试配置文件（application-test.yml）存在
- [ ] 可独立运行测试（不依赖外部服务）

### US-5.2: Service 层单元测试
**作为** 开发者  
**我想要** 核心 Service 有单元测试  
**以便** 重构时能快速验证功能正确性

**验收标准：**
- [ ] KnowledgeBaseServiceTest（CRUD 测试）
- [ ] ChatServiceTest（问答逻辑测试）
- [ ] EmbeddingServiceTest（向量化测试，可 mock）
- [ ] DocumentServiceTest（文档处理测试）
- [ ] UserServiceTest（用户注册登录测试）

### US-5.3: Controller 层测试
**作为** 开发者  
**我想要** 主要 API 有 MockMvc 测试  
**以便** 验证接口行为正确

**验收标准：**
- [ ] AuthControllerTest（登录/注册/拦截验证）
- [ ] KnowledgeBaseControllerTest（知识库 CRUD）
- [ ] ChatControllerTest（问答接口）

### US-5.4: 测试覆盖率报告
**作为** 项目维护者  
**我想要** 看到测试覆盖率报告  
**以便** 评估测试充分性

**验收标准：**
- [ ] Jacoco 配置生成覆盖率报告
- [ ] CI 中生成 HTML 覆盖率报告
- [ ] 核心业务代码覆盖率 > 50%

---

## 6. Flyway 数据库迁移

### US-6.1: Flyway 集成
**作为** 开发者  
**我想要** 数据库结构变更通过版本化管理  
**以便** 团队成员同步数据库变更

**验收标准：**
- [ ] Flyway 依赖已添加到 pom.xml
- [ ] `db/migration/` 目录创建
- [ ] 初始版本 V1__init_schema.sql 创建表结构
- [ ] 应用启动时自动执行迁移
- [ ] 迁移日志记录到 `flyway_schema_history` 表

### US-6.2: 迁移脚本规范
**作为** 开发者  
**我想要** 迁移脚本命名规范、可追溯  
**以便** 清楚每个版本做了什么变更

**验收标准：**
- [ ] 命名格式：`V{version}__{description}.sql`
- [ ] 每个脚本有注释说明用途
- [ ] 可重复执行（幂等性）
- [ ] 记录回滚语句（@Rollback 注释）

---

## ✅ 完成状态

> 更新于 2026-04-14

| US 编号 | 描述 | 状态 | 变更文件 |
|---------|------|------|----------|
| US-1.1 | 项目介绍 README | ✅ 完成 | README.md |
| US-1.2 | 快速启动文档 | ✅ 完成 | 已合并到 README |
| US-1.3 | Docker Compose | ✅ 完成 | docker-compose.yml |
| US-1.4 | 环境变量模板 | ✅ 完成 | .env.example |
| US-2.1 | CI 流水线 | ✅ 完成 | .github/workflows/ci.yml |
| US-2.2 | CI 状态徽章 | ✅ 完成 | README.md 徽章 |
| US-3.1 | OpenAPI 文档 | ✅ 完成 | OpenApiConfig.java, Controller 注解 |
| US-3.2 | API 文档增强 | ⏭️ 后续 | - |
| US-4.1 | 后端 Dockerfile | ✅ 完成 | rag-qa-backend/Dockerfile |
| US-4.2 | 前端 Dockerfile | ✅ 完成 | rag-qa-frontend/Dockerfile, nginx.conf |
| US-5.1 | 测试基础架构 | ✅ 完成 | pom.xml (H2), application-test.yml |
| US-5.2 | Service 测试 | ✅ 完成 | UserServiceTest, ChatServiceTest, DocumentServiceTest |
| US-5.3 | Controller 测试 | ✅ 完成 | AuthControllerTest, KbControllerTest, ChatControllerTest |
| US-5.4 | 覆盖率报告 | ✅ 完成 | CI 已配置 Jacoco |
| US-6.1 | Flyway 集成 | ✅ 完成 | pom.xml, V1__init_schema.sql |
| US-6.2 | 迁移脚本规范 | ✅ 完成 | V1__init_schema.sql |

---

## 工作量估算

| US 编号 | 描述 | 复杂度 | 优先级 |
|---------|------|--------|--------|
| US-1.1 | 项目介绍 README | M | P0 |
| US-1.2 | 快速启动文档 | S | P0 |
| US-1.3 | Docker Compose | M | P0 |
| US-1.4 | 环境变量模板 | S | P1 |
| US-2.1 | CI 流水线 | M | P0 |
| US-2.2 | CI 状态徽章 | S | P1 |
| US-3.1 | OpenAPI 文档 | M | P0 |
| US-3.2 | API 文档增强 | S | P2 |
| US-4.1 | 后端 Dockerfile | M | P0 |
| US-4.2 | 前端 Dockerfile | M | P0 |
| US-5.1 | 测试基础架构 | S | P0 |
| US-5.2 | Service 测试 | L | P1 |
| US-5.3 | Controller 测试 | M | P1 |
| US-5.4 | 覆盖率报告 | S | P2 |
| US-6.1 | Flyway 集成 | M | P0 |
| US-6.2 | 迁移脚本规范 | S | P1 |

> 复杂度：S=0.5天, M=1天, L=2天  
> 优先级：P0=必须, P1=应该, P2=可以

---

## 执行顺序建议

```
第1批（并行）：
├── US-1.1 README 基础
├── US-3.1 OpenAPI 文档
└── US-5.1 测试基础架构

第2批（并行）：
├── US-1.3 Docker Compose
├── US-1.4 环境变量模板
├── US-4.1 后端 Dockerfile
└── US-6.1 Flyway 集成

第3批（并行）：
├── US-2.1 CI 流水线
├── US-4.2 前端 Dockerfile
├── US-5.2 Service 测试
└── US-5.3 Controller 测试

第4批（收尾）：
├── US-1.2 快速启动完善
├── US-2.2 状态徽章
└── US-5.4 覆盖率报告
```
