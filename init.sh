#!/bin/bash
set -e

echo "=== RAG-QA Project Initialization ==="

# 检查Java版本
echo "Checking Java version..."
if ! command -v java &> /dev/null; then
    echo "Error: Java is not installed. Please install JDK 17+"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Error: Java 17+ is required. Current version: $JAVA_VERSION"
    exit 1
fi
echo "Java version OK: $(java -version 2>&1 | head -n 1)"

# 检查Maven
echo "Checking Maven..."
if ! command -v mvn &> /dev/null; then
    echo "Error: Maven is not installed. Please install Maven 3.8+"
    exit 1
fi
echo "Maven version OK: $(mvn -version | head -n 1)"

# 检查Node.js
echo "Checking Node.js..."
if ! command -v node &> /dev/null; then
    echo "Error: Node.js is not installed. Please install Node.js 18+"
    exit 1
fi

NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    echo "Error: Node.js 18+ is required. Current version: $NODE_VERSION"
    exit 1
fi
echo "Node.js version OK: $(node -v)"

# 检查npm
echo "Checking npm..."
if ! command -v npm &> /dev/null; then
    echo "Error: npm is not installed"
    exit 1
fi
echo "npm version OK: $(npm -v)"

# 创建必要的目录
echo "Creating directories..."
mkdir -p uploads
mkdir -p chroma-data
mkdir -p logs

# 复制环境配置文件
echo "Setting up environment..."
if [ ! -f .env ]; then
    if [ -f .env.example ]; then
        cp .env.example .env
        echo "Created .env from .env.example"
        echo "Please edit .env and add your API keys"
    fi
fi

echo ""
echo "=== Initialization Complete ==="
echo ""
echo "Next steps:"
echo "1. Edit .env and add your LLM API credentials"
echo "2. Backend: cd rag-qa-backend && mvn spring-boot:run"
echo "3. Frontend: cd rag-qa-frontend && npm install && npm run dev"
echo ""
