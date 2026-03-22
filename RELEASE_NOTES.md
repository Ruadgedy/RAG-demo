# Release Notes — rag-qa

## [Unreleased]

### Added
- Feature #1: Spring Boot项目初始化 - 应用可以启动在8080端口
- Spring AI 1.0.0-M6依赖配置
- Maven settings.xml解决阿里云mirror问题
- Spring Boot Actuator健康检查端点
- Feature #16: 用户注册登录功能
  - Spring Security + JWT认证
  - 后端API: /api/auth/register, /api/auth/login
  - 前端登录页面和路由守卫

### Changed
- MiniMax模型配置修复: abab5.5-chat → MiniMax-M2.5

### Fixed
- MiniMax API调用失败问题（模型名称错误）
- 用户注册登录功能

---

_Format: [Keep a Changelog](https://keepachangelog.com/) — Updated after every git commit._
