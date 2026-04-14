# =============================================
# RAG-QA Backend Dockerfile
# 多阶段构建：构建 + 运行
# =============================================

# --- Build Stage ---
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# 复制 Maven 相关文件（利用 Docker 缓存加速构建）
COPY pom.xml .
COPY src ./src

# 下载依赖（利用 Docker 缓存）
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -B

# 构建应用
RUN mvn package -DskipTests -B

# --- Runtime Stage ---
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 安装必要工具（curl 用于健康检查）
RUN apk add --no-cache curl

# 创建非 root 用户
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# 创建上传目录
RUN mkdir -p /app/uploads && chown -R appuser:appgroup /app

# 从构建阶段复制 jar 文件
COPY --from=builder /app/target/*.jar app.jar

# 设置用户
USER appuser

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# 暴露端口
EXPOSE 8080

# JVM 优化配置
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC"

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
