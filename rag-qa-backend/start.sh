#!/usr/bin/env bash
# =============================================================
# RAG-QA Backend 一键启动脚本
#
# 流程：
#   1. 前置环境检查（Java / Maven / Docker / curl）
#   2. 加载 .env 环境变量
#   3. 启动本地 Chroma 向量数据库（Docker）
#   4. 等待 Chroma 就绪（healthcheck）
#   5. 生成 JWT Secret（≥32 字节 Base64）
#   6. 启动 Spring Boot 后端
#
# Usage:
#   ./start.sh                # 完整启动
#   ./start.sh --skip-chroma  # 跳过 Chroma（已外部启动）
#   ./start.sh --no-build     # 跳过 mvn package（仅 spring-boot:run）
#   ./start.sh --help         # 查看帮助
# =============================================================
set -euo pipefail

# --------- 颜色定义 ---------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# --------- 脚本元信息 ---------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

CHROMA_CONTAINER_NAME="${CHROMA_CONTAINER_NAME:-ragqa-chroma}"
CHROMA_IMAGE="${CHROMA_IMAGE:-chromadb/chroma:latest}"
CHROMA_PORT="${CHROMA_PORT:-8000}"
CHROMA_DATA_DIR="${CHROMA_DATA_DIR:-$SCRIPT_DIR/chroma-data}"
JWT_SECRET_MIN_BYTES=32
BACKEND_PORT="${SERVER_PORT:-8080}"
SKIP_CHROMA=false
NO_BUILD=false

# --------- 健康检查辅助 ---------
# 注：
# 1. curl 必须 --noproxy '*'，否则公司代理 socks5://127.0.0.1:1087 会拦截 localhost 请求
# 2. chroma >= 0.5 已废弃 /api/v1/heartbeat，需优先探测 /api/v2/heartbeat
chroma_ready() {
    local url="http://localhost:${CHROMA_PORT}"
    for endpoint in /api/v2/heartbeat /api/v1/heartbeat /; do
        local code
        code=$(curl --noproxy '*' -s -o /dev/null -w "%{http_code}" --max-time 2 "${url}${endpoint}" 2>/dev/null || echo "000")
        # 200 / 404 都算服务在跑（404 表示端口通了但路径不对，仍说明进程监听中）
        if [[ "$code" == "200" || "$code" == "404" ]]; then
            return 0
        fi
    done
    return 1
}

# --------- 日志函数 ---------
info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[FAIL]${NC}  $*"; }
section() { echo -e "\n${CYAN}==>${NC} $*"; }

# --------- 解析参数 ---------
print_usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --skip-chroma    跳过 Chroma 启动（假设外部已运行）
  --no-build       跳过 mvn package，直接 spring-boot:run
  --help, -h       显示本帮助

Chroma 启动优先级：
  1. 复用已在 :${CHROMA_PORT} 运行的实例
  2. 使用本地 chroma CLI（chroma run）
  3. 回退到 Docker 容器（chromadb/chroma:latest）

Examples:
  $(basename "$0")                  # 完整流程
  $(basename "$0") --no-build       # 跳过构建（开发期热重载）
  $(basename "$0") --skip-chroma    # Chroma 已起，仅启动后端
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-chroma)  SKIP_CHROMA=true; shift ;;
        --no-build)     NO_BUILD=true; shift ;;
        --help|-h)      print_usage; exit 0 ;;
        *)              error "未知参数: $1"; print_usage; exit 1 ;;
    esac
done

# --------- 优雅退出（Ctrl+C 时清理） ---------
BACKEND_PID=""
cleanup() {
    echo ""
    section "正在清理..."
    if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
        info "停止后端进程 (PID=$BACKEND_PID)..."
        kill "$BACKEND_PID" 2>/dev/null || true
        wait "$BACKEND_PID" 2>/dev/null || true
        success "后端已停止"
    fi
    # 仅清理脚本启动的本地 chroma 进程（通过 pidfile 识别，避免误杀其他实例）
    if [[ -f "${CHROMA_DATA_DIR}/chroma.pid" ]]; then
        LOCAL_PID=$(cat "${CHROMA_DATA_DIR}/chroma.pid" 2>/dev/null || echo "")
        if [[ -n "$LOCAL_PID" ]] && kill -0 "$LOCAL_PID" 2>/dev/null; then
            info "停止本地 chroma 进程 (PID=$LOCAL_PID)..."
            kill "$LOCAL_PID" 2>/dev/null || true
            sleep 1
            kill -9 "$LOCAL_PID" 2>/dev/null || true
            success "本地 chroma 已停止"
        fi
        rm -f "${CHROMA_DATA_DIR}/chroma.pid"
    fi
    warn "提示：Docker Chroma 容器（若由本脚本启动）仍保持运行（数据持久化）。如需停止：docker stop $CHROMA_CONTAINER_NAME"
    exit 0
}
trap cleanup INT TERM

# ============================================================
# Step 1: 前置环境检查
# ============================================================
section "Step 1/6  环境检查"

check_command() {
    if ! command -v "$1" &>/dev/null; then
        error "$1 未安装。请先安装 $2"
        exit 1
    fi
}

check_command java "JDK 17+"
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 17 ]]; then
    error "Java 版本过低 ($JAVA_VERSION)，需要 JDK 17+"
    exit 1
fi
success "Java  $(java -version 2>&1 | head -n 1 | sed 's/^[^"]*//')"

check_command mvn "Maven 3.8+"
success "Maven $(mvn -version | head -n 1)"

if [[ "$SKIP_CHROMA" == false ]]; then
    check_command docker "Docker Desktop / Docker Engine"
    success "Docker $(docker --version)"
fi

check_command curl "curl (健康检查依赖)"
success "curl $(curl --version | head -n 1)"

# ============================================================
# Step 2: 加载 .env
# ============================================================
section "Step 2/6  加载环境变量"

if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
    success "已加载 .env"
else
    warn ".env 不存在，使用默认环境变量"
    warn "建议从 .env.example 复制：cp .env.example .env"
fi

# 校验必要配置
: "${OPENAI_API_KEY:=}"
if [[ -z "$OPENAI_API_KEY" ]]; then
    warn "OPENAI_API_KEY 未配置，LLM 调用会失败"
fi

DB_PASSWORD="${DB_PASSWORD:-}"
info "DB      = ${DB_USER:-root}@${DB_HOST:-localhost}:${DB_PORT:-3306}/${DB_NAME:-ragqa}"
info "Chroma  = http://${CHROMA_URL_HOST:-localhost}:${CHROMA_PORT:-8000}"
info "Backend = http://localhost:${BACKEND_PORT}"

# ============================================================
# Step 3: 启动 Chroma 向量数据库
# ============================================================
start_chroma() {
    section "Step 3/6  启动 Chroma 向量数据库"

    # 检查 Chroma 是否已运行（带 --noproxy 兼容代理环境）
    if chroma_ready; then
        success "Chroma 已运行在 :${CHROMA_PORT}（复用现有实例）"
        return 0
    fi

    if ! command -v docker &>/dev/null; then
        error "Chroma 未运行且 docker 未安装，无法启动 Chroma"
        error "请手动启动 Chroma 或安装 Docker 后重试（也可用 --skip-chroma 跳过此步）"
        exit 1
    fi

    # 检查容器是否已存在但停止
    if docker ps -a --format '{{.Names}}' | grep -q "^${CHROMA_CONTAINER_NAME}$"; then
        info "发现已存在的容器 ${CHROMA_CONTAINER_NAME}，尝试启动..."
        docker start "$CHROMA_CONTAINER_NAME" >/dev/null
    else
        info "创建并启动容器 ${CHROMA_CONTAINER_NAME}..."
        mkdir -p "$CHROMA_DATA_DIR"
        docker run -d \
            --name "$CHROMA_CONTAINER_NAME" \
            -p "${CHROMA_PORT}:8000" \
            -v "${CHROMA_DATA_DIR}:/chroma/chroma/chroma-data" \
            --restart unless-stopped \
            "$CHROMA_IMAGE" >/dev/null
    fi

    info "等待 Chroma 就绪 (heartbeat)..."
    for i in $(seq 1 30); do
        if chroma_ready; then
            success "Chroma 就绪 (http://localhost:${CHROMA_PORT})"
            return 0
        fi
        sleep 1
    done

    error "Chroma 启动超时（30s）。请检查：docker logs ${CHROMA_CONTAINER_NAME}"
    exit 1
}

if [[ "$SKIP_CHROMA" == true ]]; then
    info "跳过 Chroma 启动（--skip-chroma）"
    if chroma_ready; then
        success "外部 Chroma 已运行在 :${CHROMA_PORT}"
    else
        warn "Chroma 似乎未运行在 :${CHROMA_PORT}，后端启动后检索将走 fallback"
    fi
else
    # 优先级：复用现有 → 本地 CLI → Docker 容器 → 失败
    if chroma_ready; then
        success "Chroma 已运行在 :${CHROMA_PORT}（复用现有实例）"
    elif command -v chroma &>/dev/null; then
        section "Step 3/6  启动 Chroma 向量数据库（本地 CLI）"
        CHROMA_VERSION=$(chroma --version 2>&1 | head -1)
        success "检测到本地 chroma CLI：${CHROMA_VERSION}"
        mkdir -p "$CHROMA_DATA_DIR"
        info "后台启动 chroma run --host 0.0.0.0 --port ${CHROMA_PORT} --path ${CHROMA_DATA_DIR} ..."
        nohup chroma run --host 0.0.0.0 --port "${CHROMA_PORT}" --path "${CHROMA_DATA_DIR}" \
            > "${CHROMA_DATA_DIR}/chroma.log" 2>&1 &
        CHROMA_LOCAL_PID=$!
        echo "${CHROMA_LOCAL_PID}" > "${CHROMA_DATA_DIR}/chroma.pid"
        info "等待 Chroma 就绪（PID=${CHROMA_LOCAL_PID}）..."
        for i in $(seq 1 30); do
            if chroma_ready; then
                success "Chroma 就绪（本地进程，日志：${CHROMA_DATA_DIR}/chroma.log）"
                break
            fi
            if ! kill -0 "$CHROMA_LOCAL_PID" 2>/dev/null; then
                error "本地 chroma 进程已退出，请查看日志：${CHROMA_DATA_DIR}/chroma.log"
                tail -20 "${CHROMA_DATA_DIR}/chroma.log" 2>/dev/null || true
                exit 1
            fi
            sleep 1
        done
        if ! chroma_ready; then
            error "本地 Chroma 启动超时（30s）"
            tail -20 "${CHROMA_DATA_DIR}/chroma.log" 2>/dev/null || true
            exit 1
        fi
    else
        start_chroma
    fi
fi

# ============================================================
# Step 4: 生成 JWT Secret
# ============================================================
section "Step 4/6  生成 JWT Secret"

generate_jwt_secret() {
    local secret=""
    if command -v openssl &>/dev/null; then
        # 32 字节随机 → Base64 编码（≥44 字符）
        secret=$(openssl rand -base64 32)
    elif command -v python3 &>/dev/null; then
        # 回退：Python 生成
        secret=$(python3 -c "import base64, os; print(base64.b64encode(os.urandom(32)).decode())")
    else
        error "未找到 openssl 或 python3，无法生成 JWT Secret"
        exit 1
    fi
    echo "$secret"
}

# 校验已有 Secret 长度
if [[ -n "${JWT_SECRET:-}" ]]; then
    SECRET_BYTES=$(printf '%s' "$JWT_SECRET" | wc -c | tr -d ' ')
    if [[ "$SECRET_BYTES" -ge "$JWT_SECRET_MIN_BYTES" ]]; then
        success "复用现有 JWT_SECRET（长度 ${SECRET_BYTES} 字节）"
    else
        warn "现有 JWT_SECRET 长度不足（${SECRET_BYTES} < ${JWT_SECRET_MIN_BYTES}），重新生成"
        export JWT_SECRET=$(generate_jwt_secret)
    fi
else
    export JWT_SECRET=$(generate_jwt_secret)
fi

SECRET_BYTES=$(printf '%s' "$JWT_SECRET" | wc -c | tr -d ' ')
success "JWT Secret 已就绪（${SECRET_BYTES} 字节）"

# ============================================================
# Step 5: 构建 + 启动后端
# ============================================================
section "Step 5/6  编译并启动 Spring Boot 后端"

mkdir -p uploads

JAR_FILE=$(find target -maxdepth 1 -name '*.jar' -not -name '*-sources.jar' -not -name '*-javadoc.jar' 2>/dev/null | head -n 1 || true)

# 关键：导出所有变量给子进程（mvn / java）
export JWT_SECRET
export CHROMA_URL="http://localhost:${CHROMA_PORT}"
export CHROMA_HOST="${CHROMA_URL}"
export FILE_UPLOAD_DIR="${FILE_UPLOAD_DIR:-$SCRIPT_DIR/uploads}"

if [[ "$NO_BUILD" == true ]]; then
    info "跳过构建（--no-build），直接 spring-boot:run..."
    mvn spring-boot:run &
    BACKEND_PID=$!
elif [[ -n "$JAR_FILE" && "${JAR_FILE}" != "" ]]; then
    info "复用已构建产物：$(basename "$JAR_FILE")"
    info "启动 java -jar..."
    java -jar "$JAR_FILE" &
    BACKEND_PID=$!
else
    info "未发现构建产物，先执行 mvn package..."
    mvn clean package -DskipTests
    JAR_FILE=$(find target -maxdepth 1 -name '*.jar' -not -name '*-sources.jar' -not -name '*-javadoc.jar' | head -n 1)
    if [[ -z "$JAR_FILE" ]]; then
        error "构建失败：未找到 jar 产物"
        exit 1
    fi
    success "构建完成：$(basename "$JAR_FILE")"
    java -jar "$JAR_FILE" &
    BACKEND_PID=$!
fi

# ============================================================
# Step 6: 等待后端健康
# ============================================================
section "Step 6/6  等待后端就绪"

info "等待 http://localhost:${BACKEND_PORT}/actuator/health..."
for i in $(seq 1 60); do
    if curl -sf "http://localhost:${BACKEND_PORT}/actuator/health" -o /dev/null --max-time 2; then
        echo ""
        success "============================================="
        success " RAG-QA 后端已就绪"
        success " URL:    http://localhost:${BACKEND_PORT}"
        success " API:    http://localhost:${BACKEND_PORT}/swagger-ui.html"
        success " 健康:   http://localhost:${BACKEND_PORT}/actuator/health"
        success " Chroma: http://localhost:${CHROMA_PORT}"
        success "============================================="
        echo ""
        info "按 Ctrl+C 停止后端（Chroma 容器保持运行）"
        # 阻塞等待后端进程
        wait "$BACKEND_PID" 2>/dev/null || true
        exit 0
    fi
    sleep 1
done

error "后端启动超时（60s）。请检查日志。"
exit 1