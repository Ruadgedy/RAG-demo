# 贡献指南

感谢你愿意为 RAG-QA 项目做出贡献！

## 如何贡献

### 1. Fork & Clone

```bash
# Fork 项目到你的 GitHub 账号
# 然后克隆你的 Fork
git clone https://github.com/YOUR_USERNAME/RAG-demo.git
cd RAG-demo
```

### 2. 创建分支

我们使用 `dev` 分支进行开发：

```bash
git checkout dev
git checkout -b feature/your-feature-name
# 或者
git checkout -b fix/your-bug-fix
```

### 3. 开发

- 编写代码
- 添加或更新测试
- 确保所有测试通过

### 4. 提交

我们使用 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

```
<type>(<scope>): <subject>

[optional body]

[optional footer(s)]
```

类型：
- `feat`: 新功能
- `fix`: 修复 bug
- `docs`: 文档变更
- `style`: 代码格式（不影响功能）
- `refactor`: 重构
- `test`: 测试相关
- `chore`: 构建/工具变更

示例：
```bash
git commit -m "feat(auth): add JWT token refresh"
git commit -m "fix(chat): fix stream response timeout"
git commit -m "docs: update README deployment guide"
```

### 5. Push & Pull Request

```bash
git push origin your-branch-name
```

然后在 GitHub 上创建 Pull Request 到 `dev` 分支。

## 开发环境

### 前置条件

- JDK 17+
- Node.js 18+
- MySQL 8+
- Docker（可选）

### 本地运行

```bash
# 后端
cd rag-qa-backend
./mvnw spring-boot:run

# 前端
cd rag-qa-frontend
npm install
npm run dev
```

### 运行测试

```bash
# 后端测试
cd rag-qa-backend
./mvnw test

# 前端测试
cd rag-qa-frontend
npm test
```

## 代码规范

### Java

- 遵循 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- 使用 Lombok 减少样板代码
- 所有公共类和方法需要 Javadoc

### 前端

- 遵循 Vue 3 最佳实践
- 使用 Composition API
- 组件文件用 PascalCase

## 问题反馈

如果你发现 bug 或有新功能建议：

1. 先搜索 [Issues](https://github.com/Ruadgedy/RAG-demo/issues) 看是否已有
2. 创建新 Issue 并提供详细信息
3. 对于 bug，请包含复现步骤

## 许可

通过贡献代码，你同意你的贡献将按照 [Apache License 2.0](LICENSE) 授权。
