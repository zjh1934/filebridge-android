#!/usr/bin/env bash
# 启动 FileBridge 文件网关
#   FILEBRIDGE_BASE   持久根目录（默认 /data/user_persistent_data/filebridge）
#   FILEBRIDGE_TOKEN  访问密钥，生产务必改掉
#   FILEBRIDGE_SECRET 预签名密钥，生产务必独立设置
#   PORT              监听端口（默认 8765）
set -e
cd "$(dirname "$0")/.."
export FILEBRIDGE_BASE="${FILEBRIDGE_BASE:-/data/user_persistent_data/filebridge}"
export FILEBRIDGE_TOKEN="${FILEBRIDGE_TOKEN:-demo-token-change-me}"
export FILEBRIDGE_SECRET="${FILEBRIDGE_SECRET:-${FILEBRIDGE_TOKEN}-sign}"
echo "ROOT  = $FILEBRIDGE_BASE/files"
echo "TOKEN = $FILEBRIDGE_TOKEN"
echo "PORT  = ${PORT:-8765}"
exec python3 -m uvicorn server.app:app --host 0.0.0.0 --port "${PORT:-8765}"
