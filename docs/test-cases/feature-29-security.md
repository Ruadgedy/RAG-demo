# Feature #29 — 安全防护（路径遍历 + JWT Secret）

| 项目 | 内容 |
|------|------|
| **Feature ID** | #29 |
| **关联修复** | FIX-008, FIX-010 |
| **关联类** | `DocumentService`, `JwtService` |
| **关联需求** | NFR-008（配置外置）、NFR-007（异常处理） |
| **优先级** | P0（安全） |
| **编写日期** | 2026-06-27 |

---

## 1. 功能概述

### 1.1 背景

**CVE-1：路径遍历（Path Traversal）**

`DocumentService.uploadDocument()` 用 `file.getOriginalFilename()` 直接拼接路径：

```java
String storedFileName = fileName;  // 攻击者可控
Path filePath = uploadPath.resolve(storedFileName);
Files.copy(file.getInputStream(), filePath);
```

攻击者可上传 `../../etc/passwd.pdf` 写到 `uploads/` 之外，绕过应用沙箱。

**CVE-2：硬编码凭证（Hard-coded Credentials）**

`application.properties` 含硬编码 JWT Secret：

```properties
jwt.secret=mySecretKeyForJWTTokenGenerationThatIsLongEnough123456
```

这是公开的开发密钥。如果生产部署忘记设置环境变量，会使用这个弱密钥，攻击者可伪造任意用户 token。

### 1.2 修复方案

**路径遍历双层防御**：
1. 第一层：文件名包含 `/` 或 `\` 直接拒绝
2. 第二层：`getFileName()` + `normalize()` + `startsWith` 边界校验

**JWT Secret 强制环境变量**：
1. `application.properties` 改为 `${JWT_SECRET:}`（强制环境变量注入）
2. `JwtService.@PostConstruct validateJwtConfig()` 启动校验缺失或 < 32 字节则启动失败

---

## 2. 测试用例

### 2.1 路径遍历攻击测试

#### TC-29-01: 阻止 `../../` 路径遍历攻击

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-01 |
| **测试目标** | 验证最常见的 Linux 风格路径遍历攻击被拦截 |
| **关联 CVE** | Path Traversal |
| **前置条件** | KB 存在 |
| **测试数据** | 文件名 = `../../etc/passwd.pdf` |
| **测试步骤** | 1. 构造 MultipartFile，原文件名 = `../../etc/passwd.pdf`<br>2. 调用 `documentService.uploadDocument(kbId, file)` |
| **预期结果** | • 抛出 `IllegalArgumentException` 含 "非法文件名"<br>• 不创建 Document 记录<br>• 不写文件到任何位置 |
| **验证方式** | assertThatThrownBy + verify(documentRepository, never()).save(any()) |
| **状态** | ✅ 已通过 `DocumentServiceTest.shouldRejectPathTraversalFilename` |

#### TC-29-02: 阻止绝对路径攻击

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-02 |
| **测试目标** | 验证绝对路径攻击被拦截 |
| **测试数据** | 文件名 = `/etc/passwd.pdf` |
| **测试步骤** | 同 TC-29-01 |
| **预期结果** | • 抛出 `IllegalArgumentException` 含 "非法文件名"<br>• 不写文件 |
| **验证方式** | assertThatThrownBy |
| **状态** | ✅ 已通过 `DocumentServiceTest.shouldRejectAbsolutePathFilename` |

#### TC-29-03: 阻止 Windows 风格路径攻击

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-03 |
| **测试目标** | 验证反斜杠路径分隔符也被拦截 |
| **测试数据** | 文件名 = `..\..\windows\evil.pdf` |
| **测试步骤** | 同 TC-29-01 |
| **预期结果** | • 抛出 `IllegalArgumentException` 含 "非法文件名" |
| **验证方式** | assertThatThrownBy |
| **状态** | ✅ 已通过 `DocumentServiceTest.shouldRejectBackslashPathFilename` |

#### TC-29-04: 合法文件名应被接受

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-04 |
| **测试目标** | 验证合法文件名不被误杀 |
| **测试数据** | 文件名 = `user-guide.pdf`、`技术文档.docx`、`年报 2026.xlsx` |
| **测试步骤** | 1. 构造合法文件名的 MultipartFile<br>2. 调用 `uploadDocument` |
| **预期结果** | • 不抛异常<br>• Document 记录正常创建 |
| **验证方式** | 单元测试 |
| **状态** | ✅ 已通过 `DocumentServiceTest.shouldAcceptLegitimateFilename` |

#### TC-29-05: 路径遍历日志记录

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-05 |
| **测试目标** | 安全审计：路径遍历尝试应记录到日志 |
| **前置条件** | 启用 audit logging |
| **测试步骤** | 1. 攻击者上传 `../../etc/passwd.pdf`<br>2. 检查日志 |
| **预期结果** | • WARN 级别日志输出<br>• 包含攻击者 IP、原始文件名、userId<br>• 触发告警（如配置了） |
| **验证方式** | 集成测试 + Logback ListAppender |
| **状态** | ⏳ 建议 ST 阶段补全 |

### 2.2 JWT Secret 强制校验测试

#### TC-29-06: 缺失 JWT_SECRET 环境变量应启动失败

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-06 |
| **测试目标** | 验证 FIX-010：未设置 JWT_SECRET 时 Spring Boot 启动失败 |
| **关联 CVE** | Hard-coded Credentials |
| **前置条件** | 环境变量 JWT_SECRET 未设置 |
| **测试步骤** | 1. 启动 Spring Boot 应用<br>2. 观察启动过程 |
| **预期结果** | • `JwtService.validateJwtConfig()` 抛出 `IllegalStateException`<br>• 错误信息含 "JWT 密钥未配置！请设置环境变量 JWT_SECRET"<br>• Spring Boot 启动失败并退出 |
| **验证方式** | SpringBootTest + assertThatThrownBy |
| **状态** | ⏳ 建议实现 |

#### TC-29-07: JWT_SECRET 长度不足应启动失败

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-07 |
| **测试目标** | 验证密钥 < 32 字节（256 bit）应拒绝启动 |
| **前置条件** | JWT_SECRET = `shortkey`（解码后 < 32 字节） |
| **测试步骤** | 1. 设置 JWT_SECRET=<br>2. 启动应用 |
| **预期结果** | • 抛出 `IllegalStateException`<br>• 错误信息含 "JWT 密钥长度不足！当前 X 字节，至少需要 32 字节"<br>• 启动失败 |
| **验证方式** | SpringBootTest |
| **状态** | ⏳ 建议实现 |

#### TC-29-08: JWT_SECRET 非 Base64 应启动失败

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-08 |
| **测试目标** | 验证非 Base64 格式的密钥被拒绝 |
| **前置条件** | JWT_SECRET = `not-base64-string!!!` |
| **测试步骤** | 1. 设置 JWT_SECRET=<br>2. 启动应用 |
| **预期结果** | • 抛出 `IllegalStateException`<br>• 错误信息含 "JWT 密钥不是合法的 Base64 字符串" |
| **验证方式** | SpringBootTest |
| **状态** | ⏳ 建议实现 |

#### TC-29-09: 合法 JWT_SECRET 应启动成功

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-09 |
| **测试目标** | 验证 ≥32 字节 Base64 密钥可正常启动 |
| **前置条件** | JWT_SECRET = `$(openssl rand -base64 32)` 输出 |
| **测试步骤** | 1. 设置 JWT_SECRET=<br>2. 启动应用<br>3. 检查启动日志 |
| **预期结果** | • 启动成功<br>• 日志输出 "JWT 配置校验通过，密钥长度 32 字节" |
| **验证方式** | SpringBootTest |
| **状态** | ⏳ 建议实现 |

#### TC-29-10: 启动校验仅执行一次

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-10 |
| **测试目标** | 验证 `@PostConstruct` 只在启动时执行一次（不应每次请求都校验） |
| **测试步骤** | 1. 启动应用<br>2. 发起 100 次 HTTP 请求触发 JwtService<br>3. 检查启动日志次数 |
| **预期结果** | • "JWT 配置校验通过" 日志仅输出 1 次 |
| **验证方式** | 集成测试 + 日志计数 |
| **状态** | ⏳ 建议实现 |

### 2.3 安全合规性测试

#### TC-29-11: 配置审计 — application.properties 无明文密钥

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-11 |
| **测试目标** | 确保 application.properties 不含任何明文 JWT 密钥 |
| **测试步骤** | 1. `grep -E "jwt\.secret\s*=\s*[A-Za-z0-9]+" src/main/resources/application.properties` |
| **预期结果** | • 无匹配（除 `${JWT_SECRET:}` 变量引用） |
| **验证方式** | Shell 脚本 + CI 检查 |
| **状态** | ⏳ 建议加入 CI 流水线 |

#### TC-29-12: OWASP Top 10 — A01:2021 Broken Access Control

| 项 | 内容 |
|----|------|
| **用例编号** | TC-29-12 |
| **测试目标** | 验证修复的路径遍历属于 OWASP A01 范畴 |
| **关联标准** | OWASP Top 10 2021 |
| **测试方法** | 手工渗透测试 + 自动化 fuzz |
| **测试步骤** | 1. 准备 100 种 payload：`..\\..\\`、`%2e%2e%2f`、URL-encoded、Unicode 双字节等<br>2. 全部 POST 到 `/api/knowledge-bases/{kbId}/documents`<br>3. 检查每个 payload 是否被拒绝 |
| **预期结果** | • 100 种 payload 全部返回 400 Bad Request<br>• 无文件写入到 uploads 之外 |
| **验证方式** | Burp Suite + 安全扫描 |
| **状态** | ⏳ 建议安全团队 ST 阶段执行 |

---

## 3. 覆盖矩阵

| CVE | 攻击向量 | TC |
|-----|----------|-----|
| Path Traversal | Linux `../` | TC-29-01 |
| Path Traversal | Linux 绝对路径 `/` | TC-29-02 |
| Path Traversal | Windows `..\\` | TC-29-03 |
| Path Traversal | 误杀合法 | TC-29-04 |
| Path Traversal | 审计日志 | TC-29-05 |
| Hard-coded Creds | 缺失环境变量 | TC-29-06 |
| Hard-coded Creds | 长度不足 | TC-29-07 |
| Hard-coded Creds | 格式错误 | TC-29-08 |
| Hard-coded Creds | 正常路径 | TC-29-09 |
| Hard-coded Creds | 仅启动一次 | TC-29-10 |
| 运维 | 配置审计 | TC-29-11 |
| OWASP | A01 渗透 | TC-29-12 |

---

## 4. 验收标准

- [x] TC-29-01 ~ TC-29-04 路径遍历单元测试通过
- [ ] TC-29-05 日志审计待 ST 阶段
- [ ] TC-29-06 ~ TC-29-10 JWT 启动校验测试待补全
- [ ] TC-29-11 CI 配置审计检查待添加
- [ ] TC-29-12 OWASP 渗透测试待安全团队

---

## 5. 安全合规建议

1. **CI 流水线增加 grep 检查**：防止有人重新硬编码密钥
2. **Kubernetes Secret 注入**：JWT_SECRET 通过 K8s Secret 注入
3. **定期轮换密钥**：建议 90 天轮换一次
4. **安全审计日志**：路径遍历尝试记录到独立 audit log，定期审查

---

## 6. 自动化测试结果

```
[INFO] Running com.ragqa.service.DocumentServiceTest
- shouldRejectPathTraversalFilename       ✅ PASS
- shouldRejectAbsolutePathFilename        ✅ PASS
- shouldRejectBackslashPathFilename       ✅ PASS
- shouldAcceptLegitimateFilename          ✅ PASS
[INFO] BUILD SUCCESS
```