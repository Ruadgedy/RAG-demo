#!/usr/bin/env bash
# =============================================================
# RAG-QA 全栈一键启动脚本
#
# 流程：
#   1. 环境检查（Java 17+ / Maven / Node 18+ / npm）
#   2. 调用 backend/start.sh 逻辑：Chroma + JWT Secret + 后端
#   3. 启动前端 Vite dev server（自动 npm install）
#   4. 等待两端就绪
#   5. Ctrl+C 优雅退出
#
# Usage:
#   ./start.sh                     # 同时启动前后端
#   ./start.sh --backend-only      # 仅启动后端（不启前端）
#   ./start.sh --frontend-only     # 仅启动前端（不启后端）
#   ./start.sh --skip-chroma       # 跳过 Chroma
#   ./start.sh --no-install        # 跳过 npm install
#   ./start.sh --no-build          # 跳过 mvn package
#   ./start.sh --help              # 查看帮助
# =============================================================
set -euo pipefail

# --------- 颜色 ---------
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[FAIL]${NC}  $*"; }
section() { echo -e "\n${CYAN}==>${NC} $*"; }

# --------- 路径 ---------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/rag-qa-backend"
FRONTEND_DIR="$SCRIPT_DIR/rag-qa-frontend"
BACKEND_START="$BACKEND_DIR/start.sh"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
BACKEND_PORT="${SERVER_PORT:-8080}"

# --------- 参数 ---------
BACKEND_ONLY=false
FRONTEND_ONLY=false
SKIP_CHROMA=false
NO_INSTALL=false
NO_BUILD=false

print_usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --backend-only    仅启动后端
  --frontend-only   仅启动前端
  --skip-chroma     跳过 Chroma 启动
  --no-install      跳过 npm install（前端）
  --no-build        跳过 mvn package（后端）
  --help, -h        显示帮助

启动后访问：
  前端: http://localhost:${FRONTEND_PORT}
  后端: http://localhost:${BACKEND_PORT}
  API:  http://localhost:${BACKEND_PORT}/swagger-ui.html
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --backend-only)   BACKEND_ONLY=true; shift ;;
        --frontend-only)  FRONTEND_ONLY=true; shift ;;
        --skip-chroma)    SKIP_CHROMA=true; shift ;;
        --no-install)     NO_INSTALL=true; shift ;;
        --no-build)       NO_BUILD=true; shift ;;
        --help|-h)        print_usage; exit 0 ;;
        *)                error "未知参数: $1"; print_usage; exit 1 ;;
    esac
done

if [[ "$BACKEND_ONLY" == true && "$FRONTEND_ONLY" == true ]]; then
    error "--backend-only 与 --frontend-only 互斥"
    exit 1
fi

# --------- 子进程跟踪 + 优雅退出 ---------
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
    echo ""
    section "正在清理子进程..."
    if [[ -n "$FRONTEND_PID" ]] && kill -0 "$FRONTEND_PID" 2>/dev/null; then
        info "停止前端 (PID=$FRONTEND_PID)..."
        kill "$FRONTEND_PID" 2>/dev/null || true
        # 同时杀掉 vite 的子进程组
        pkill -P "$FRONTEND_PID" 2>/dev/null || true
    fi
    if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" 2>/dev/null; then
        info "停止后端 (PID=$BACKEND_PID)..."
        kill "$BACKEND_PID" 2>/dev/null || true
        # 后端可能 spawn 了 Maven，需要杀进程组
        pkill -P "$BACKEND_PID" 2>/dev/null || true
    fi
    # 给点时间优雅退出
    sleep 1
    # 兜底：强杀残留
    [[ -n "$FRONTEND_PID" ]] && kill -9 "$FRONTEND_PID" 2>/dev/null || true
    [[ -n "$BACKEND_PID" ]] && kill -9 "$BACKEND_PID" 2>/dev/null || true
    success "所有子进程已停止"
    exit 0
}
trap cleanup INT TERM

# ============================================================
# Step 1: 前置检查
# ============================================================
section "Step 1  环境检查"

check_cmd() {
    if ! command -v "$1" &>/dev/null; then
        error "$1 未安装：$2"
        exit 1
    fi
}

if [[ "$FRONTEND_ONLY" == false ]]; then
    check_cmd java "请安装 JDK 17+"
    JAVA_VER=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    [[ "$JAVA_VER" -lt 17 ]] && { error "需要 JDK 17+（当前 $JAVA_VER）"; exit 1; }
    success "Java  $(java -version 2>&1 | head -n 1 | sed 's/^[^"]*//')"

    check_cmd mvn "请安装 Maven 3.8+"
    success "Maven $(mvn -version | head -n 1)"
fi

if [[ "$BACKEND_ONLY" == false ]]; then
    check_cmd node "请安装 Node.js 18+"
    NODE_VER=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
    [[ "$NODE_VER" -lt 18 ]] && { error "需要 Node 18+（当前 $NODE_VER）"; exit 1; }
    success "Node   $(node -v)"

    check_cmd npm "请安装 npm"
    success "npm    v$(npm -v)"
fi

[[ ! -d "$BACKEND_DIR"  ]] && { error "后端目录不存在: $BACKEND_DIR"; exit 1; }
[[ ! -d "$FRONTEND_DIR" ]] && { error "前端目录不存在: $FRONTEND_DIR"; exit 1; }
[[ ! -f "$BACKEND_START" ]] && { error "后端启动脚本缺失: $BACKEND_START"; exit 1; }

# ============================================================
# Step 2: 启动后端（Chroma + JWT + Spring Boot）
# ============================================================
if [[ "$FRONTEND_ONLY" == false ]]; then
    section "Step 2  启动后端服务（Chroma + JWT + Spring Boot）"

    BACKEND_ARGS=()
    [[ "$SKIP_CHROMA" == true ]] && BACKEND_ARGS+=(--skip-chroma)
    [[ "$NO_BUILD" == true ]]    && BACKEND_ARGS+=(--no-build)

    # 在子 shell 中启动后端，但保留 PID 供后续跟踪
    # 注：set -u 下空数组展开需用 "${arr[@]+"${arr[@]}"}" 守护
    if [[ ${#BACKEND_ARGS[@]} -gt 0 ]]; then
        (cd "$BACKEND_DIR" && bash "$BACKEND_START" "${BACKEND_ARGS[@]}") &
    else
        (cd "$BACKEND_DIR" && bash "$BACKEND_START") &
    fi
    BACKEND_PID=$!
    info "后端进程已启动 (PID=$BACKEND_PID)，等待就绪..."

    # 等待后端 health 端口（最长 90s）
    # 注：--noproxy '*' 避免公司 SOCKS5 代理拦截 localhost
    for i in $(seq 1 90); do
        if curl --noproxy '*' -sf "http://localhost:${BACKEND_PORT}/actuator/health" -o /dev/null --max-time 2; then
            success "后端已就绪 http://localhost:${BACKEND_PORT}"
            break
        fi
        if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
            error "后端进程已退出"
            exit 1
        fi
        sleep 1
    done

    if ! curl --noproxy '*' -sf "http://localhost:${BACKEND_PORT}/actuator/health" -o /dev/null --max-time 2; then
        error "后端启动超时（90s）"
        exit 1
    fi
fi

# ============================================================
# Step 3: 启动前端（npm install + vite dev）
# ============================================================
if [[ "$BACKEND_ONLY" == false ]]; then
    section "Step 3  启动前端服务（Vite dev server）"

    cd "$FRONTEND_DIR"

    # 依赖检查：node_modules 不存在或 package-lock 较新则重装
    if [[ "$NO_INSTALL" == false ]]; then
        if [[ ! -d node_modules ]] || [[ package-lock.json -nt node_modules ]]; then
            info "执行 npm install（首次或依赖过期）..."
            npm install
            success "依赖安装完成"
        else
            info "node_modules 已就绪，跳过 install"
        fi
    else
        warn "跳过 npm install（--no-install）"
    fi

    info "后台启动 vite dev server..."
    npm run dev > "$FRONTEND_DIR/vite.log" 2>&1 &
    FRONTEND_PID=$!
    info "前端进程已启动 (PID=$FRONTEND_PID)，日志：$FRONTEND_DIR/vite.log"

    # 等待前端端口（最长 60s）
    # 注：--noproxy '*' 避免公司 SOCKS5 代理拦截 localhost
    for i in $(seq 1 60); do
        if curl --noproxy '*' -sf "http://localhost:${FRONTEND_PORT}" -o /dev/null --max-time 2; then
            success "前端已就绪 http://localhost:${FRONTEND_PORT}"
            break
        fi
        if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
            error "前端进程已退出，查看日志：$FRONTEND_DIR/vite.log"
            tail -30 "$FRONTEND_DIR/vite.log" 2>/dev/null || true
            exit 1
        fi
        sleep 1
    done

    if ! curl --noproxy '*' -sf "http://localhost:${FRONTEND_PORT}" -o /dev/null --max-time 2; then
        error "前端启动超时（60s）"
        tail -30 "$FRONTEND_DIR/vite.log" 2>/dev/null || true
        exit 1
    fi
fi

# ============================================================
# Step 4: 全栈就绪
# ============================================================
section "============================================="
echo -e "${GREEN}  RAG-QA 全栈服务已就绪${NC}"
echo -e "${GREEN}  前端: http://localhost:${FRONTEND_PORT}${NC}"
echo -e "${GREEN}  后端: http://localhost:${BACKEND_PORT}${NC}"
echo -e "${GREEN}  API:  http://localhost:${BACKEND_PORT}/swagger-ui.html${NC}"
echo -e "${GREEN}  日志: 前端→$FRONTEND_DIR/vite.log / 后端→终端${NC}"
echo -e "${CYAN}=============================================${NC}"
echo ""
info "按 Ctrl+C 停止所有服务"

# 阻塞直到任一子进程退出
wait -n "$BACKEND_PID" "$FRONTEND_PID" 2>/dev/null || true
EXIT_CODE=$?

# 如果是用户 Ctrl+C，cleanup 已执行；这里是异常退出
if [[ $EXIT_CODE -ne 0 ]] && [[ $EXIT_CODE -ne 130 ]]; then
    warn "子进程退出 (code=$EXIT_CODE)，清理中..."
    cleanup
fi