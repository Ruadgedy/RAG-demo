# RAG-QA 智能问答系统

[![CI](https://github.com/Ruadgedy/RAG-demo/actions/workflows/ci.yml/badge.svg?branch=dev)](https://github.com/Ruadgedy/RAG-demo/actions/workflows/ci.yml)
[![Build Status](https://img.shields.io/github/actions/workflow/status/Ruadgedy/RAG-demo/ci.yml?branch=dev)](https://github.com/Ruadgedy/RAG-demo/actions)
[![Java Version](https://img.shields.io/badge/Java-17+-blue.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green.svg)](https://spring.io/projects/spring-boot)
[![Vue.js](https://img.shields.io/badge/Vue-3.4-brightgreen.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> 🔍 基于 RAG（检索增强生成）技术的私有知识库问答系统，支持文档上传、智能问答、流式输出

<!-- screenshot -->

## ✨ 功能特性

- 📚 **知识库管理** — 创建、管理多个知识库，支持文档上传和解析
- 🔍 **RAG 检索** — 混合向量检索，从文档中精准召回相关内容
- 💬 **智能问答** — 基于检索结果生成答案，支持流式输出
- 👤 **用户认证** — JWT 登录注册，保护知识库安全
- 📄 **多格式支持** — 支持 PDF、TXT、DOCX 等常见文档格式
- 🔧 **可扩展** —  模块化设计，方便切换 Embedding 模型和 LLM

## 🏗️ 技术栈

### 后端
- **框架**: Spring Boot 3.2 + Spring AI 1.0 M6
- **语言**: Java 17
- **向量库**: Chroma (本地向量数据库)
- **数据库**: MySQL 8
- **认证**: Spring Security + JWT
- **文档解析**: Apache Tika + Pdfbox

### 前端
- **框架**: Vue 3 + Vite
- **UI**: 自定义组件库
- **状态管理**: Pinia
- **HTTP**: Axios

## 📋 前置条件

- JDK 17+
- Node.js 18+
- MySQL 8+ (或 Docker)
- Chroma (可通过 Docker 启动)

## 🚀 快速启动

### 1. 克隆项目

```bash
git clone https://github.com/Ruadgedy/RAG-demo.git
cd RAG-demo
```

### 2. 配置环境变量

```bash
# 复制环境变量模板
cp .env.example .env

# 编辑 .env，填入必要的 API Key
vim .env
```

**必填配置项：**
```env
OPENAI_API_KEY=your_minimax_or_huggingface_api_key
OPENAI_BASE_URL=https://api.minimax.chat/v1
OPENAI_MODEL=MiniMax-M2.5
```

### 3. 启动后端

```bash
cd rag-qa-backend

# 方式一：直接运行（需要本地 MySQL 和 Chroma）
./mvnw spring-boot:run

# 方式二：Docker 启动依赖服务
docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root --name mysql mysql:8
docker run -d -p 8000:8000 --name chroma chromadb/chroma

# 然后启动应用
./mvnw spring-boot:run
```

### 4. 启动前端

```bash
cd rag-qa-frontend
npm install
npm run dev
```

### 5. 访问应用

- 前端地址: http://localhost:5173
- 后端地址: http://localhost:8080
- API 文档: http://localhost:8080/swagger-ui.html

**默认测试账号：**
- 用户名: `test@example.com`
- 密码: `password`

## 🐳 Docker Compose 部署

```bash
# 一键启动所有服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

## 📖 项目文档

- [环境配置指南](./env-guide.md) — 详细的环境配置说明
- [任务进度](./task-progress.md) — 开发进度追踪
- [功能列表](./feature-list.json) — 所有功能点清单

## 🔧 开发指南

### 项目结构

```
RAG-demo/
├── rag-qa-backend/          # Spring Boot 后端
│   ├── src/main/java/       # Java 源码
│   │   └── com/ragqa/
│   │       ├── controller/  # REST API 控制器
│   │       ├── service/     # 业务逻辑
│   │       ├── model/       # 数据模型
│   │       ├── dto/         # 数据传输对象
│   │       └── config/      # 配置类
│   └── src/main/resources/
│       └── application.properties  # 应用配置
│
├── rag-qa-frontend/          # Vue 3 前端
│   ├── src/
│   │   ├── views/           # 页面组件
│   │   ├── components/     # 通用组件
│   │   └── api/            # API 调用
│   └── vite.config.js
│
├── chroma/                   # Chroma 向量数据库持久化
├── docs/                     # 项目文档
│   └── plans/              # 设计文档
└── uploads/                  # 上传文件存储
```

### API 接口

| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 用户登录 |
| `/api/knowledge-base` | GET | 获取知识库列表 |
| `/api/knowledge-base` | POST | 创建知识库 |
| `/api/knowledge-base/{id}` | DELETE | 删除知识库 |
| `/api/document/upload` | POST | 上传文档 |
| `/api/chat` | POST | 问答（普通） |
| `/api/chat/stream` | POST | 问答（流式） |

### 运行测试

```bash
cd rag-qa-backend
./mvnw test
```

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

## 🙏 致谢

- [Spring AI](https://spring.io/projects/spring-ai) — Spring AI 框架
- [Chroma](https://www.trychroma.com/) — 向量数据库
- [Vue.js](https://vuejs.org/) — 前端框架
