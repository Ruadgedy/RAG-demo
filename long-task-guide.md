# RAG-QA 项目工作指南

本指南为Worker提供项目开发的具体指令。

## 项目概述

- **项目名称**: RAG智能问答系统
- **技术栈**: Java (Spring Boot + Spring AI) + Vue3 + Chroma
- **测试框架**: JUnit
- **覆盖率工具**: JaCoCo
- **变异测试**: Pitest

## 质量门槛

| 指标 | 最低要求 |
|------|----------|
| 行覆盖率 | 90% |
| 分支覆盖率 | 80% |
| 变异分数 | 80% |

## 环境命令

### 激活环境

Java和Maven通常已安装在系统环境：
```bash
# 验证Java
java -version

# 验证Maven
mvn -version
```

Node.js环境（前端）：
```bash
# 验证Node
node -v

# 验证npm
npm -v
```

### 直接测试命令

**后端测试**:
```bash
cd rag-qa-backend
mvn test
```

**后端覆盖率报告**:
```bash
cd rag-qa-backend
mvn jacoco:report
# 报告生成在 target/site/jacoco/index.html
```

**变异测试**:
```bash
cd rag-qa-backend
mvn org.pitest:pitest-maven:mutationCoverage
```

**前端测试**:
```bash
cd rag-qa-frontend
npm test
```

## 配置管理

### 添加/更新配置

对于Java Spring Boot项目，配置通过以下方式管理：

1. **环境变量** - 在 `.env` 文件中设置，或系统环境变量
2. **配置文件** - 在 `src/main/resources/application.properties` 中设置

示例：
```properties
# application.properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.base-url=${OPENAI_BASE_URL}
```

配置完成后，后端会自动读取环境变量。

### 必需配置

| 配置 | 类型 | 说明 |
|------|------|------|
| OPENAI_API_KEY | env | LLM API密钥 |
| OPENAI_BASE_URL | env | LLM API地址 |
| CHROMA_PERSIST_DIR | env | 向量数据库存储目录 |
| FILE_UPLOAD_DIR | env | 上传文件存储目录 |

## 服务命令

### 启动服务

**后端**:
```bash
cd rag-qa-backend
mvn spring-boot:run
```

**前端**:
```bash
cd rag-qa-frontend
npm install
npm run dev
```

### 健康检查

- 后端: http://localhost:8080/actuator/health
- 前端: http://localhost:5173

详细服务管理请参考 `env-guide.md`。

## TDD工作流

### 1. Orient（定位）

1. 阅读 `feature-list.json` 选择下一个failing特征
2. 阅读 `docs/plans/*-srs.md` 中对应的需求
3. 阅读 `docs/plans/*-design.md` 中对应的设计
4. 阅读 `docs/plans/*-ucd.md` 中UI规范（如果是UI功能）

### 2. Bootstrap（引导）

运行测试确保环境正常：
```bash
cd rag-qa-backend
mvn test -Dtest=*Test
```

### 3. Config Gate（配置门）

检查必需配置：
```bash
# 检查.env文件
cat .env
```

确保所有必需的配置都已设置。

### 4. TDD Red（红）

编写失败的测试：
- 测试文件放在 `src/test/java/`
- 命名规范: `*ServiceTest.java`, `*ControllerTest.java`
- 示例：
```java
@Test
void shouldReturnKnowledgeBaseList() {
    // Given
    // When
    List<KnowledgeBase> result = knowledgeBaseService.list();
    // Then
    assertThat(result).isNotNull();
}
```

### 5. TDD Green（绿）

实现代码让测试通过：
- 控制器: `src/main/java/.../controller/`
- 服务: `src/main/java/.../service/`
- 仓储: `src/main/java/.../repository/`

### 6. Coverage Gate（覆盖门）

运行覆盖率：
```bash
mvn jacoco:report
```

检查是否达到90%行覆盖率、80%分支覆盖率。

### 7. TDD Refactor（重构）

在保持测试通过的情况下重构代码。

### 8. Mutation Gate（变异门）

运行变异测试：
```bash
mvn org.pitest:pitest-maven:mutationCoverage
```

确保变异分数 >= 80%。

### 9. 验证执行

运行完整测试：
```bash
mvn test
```

### 10. Code Review（代码审查）

检查代码是否符合：
- `docs/plans/*-srs.md` 中的需求
- `docs/plans/*-design.md` 中的设计
- `docs/plans/*-ucd.md` 中的UI规范（如果是UI功能）

### 11. Persist（持久化）

提交代码：
```bash
git add .
git commit -m "feat: 实现功能描述"
```

## 真实测试约定

### 识别方法

Java项目使用JUnit 5：
- 测试类以 `Test` 结尾
- 测试方法以 `@Test` 注解
- 集成测试使用 `@SpringBootTest`
- 单元测试使用 `@ExtendWith(MockitoExtension.class)`

### 运行真实测试

```bash
# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=KnowledgeBaseServiceTest
```

### 示例真实测试

```java
@SpringBootTest
class ChatServiceTest {
    
    @Autowired
    private ChatService chatService;
    
    @Test
    void shouldReturnAnswer() {
        // Given
        String message = "什么是RAG?";
        String kbId = "test-kb";
        
        // When
        String answer = chatService.chat(message, kbId);
        
        // Then
        assertThat(answer).isNotBlank();
    }
}
```

## 关键规则

1. **必须TDD** - 所有功能必须先写测试
2. **覆盖率门槛** - 行覆盖90%、分支80%、变异80%
3. **配置门** - 功能实现前必须配置好
4. **UI验证** - 前端功能必须用Chrome DevTools验证
5. **提交前检查** - 确保所有测试通过、覆盖率达标

## 示例参考

参考示例目录: `examples/`
