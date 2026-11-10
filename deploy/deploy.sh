#!/usr/bin/env bash
# 一键部署：前端解压 + 后端复制 + 重启服务
# 用法： sudo bash deploy/deploy.sh
set -euo pipefail

SRC_DIR=/home/ubuntu/ai-bot          # 服务器上存放 dist.zip 和 jar 的目录
WEB_ZIP="$SRC_DIR/dist.zip"          # 前端构建产物
WEB_DEST=/var/www/ai-bot             # nginx 指向的前端根目录
BACKEND_DEST=/opt/aibot              # 后端部署目录
SERVICE=aibot-backend               # systemd 服务名

echo "==> [1/3] 部署前端：$WEB_ZIP -> $WEB_DEST"
rm -rf "$WEB_DEST"
mkdir -p "$WEB_DEST"
unzip -o "$WEB_ZIP" -d "$WEB_DEST"
echo "    前端文件：$(ls "$WEB_DEST" | head -5 | tr '\n' ' ') ..."

echo "==> [2/3] 部署后端 jar"
mkdir -p "$BACKEND_DEST"
cp "$SRC_DIR"/purchase-ai-bot-*.jar "$BACKEND_DEST/app.jar"
echo "    jar -> $BACKEND_DEST/app.jar"

echo "==> [3/3] 重启后端服务：$SERVICE"
systemctl restart "$SERVICE"

echo "==> 完成。服务状态："
systemctl status "$SERVICE" --no-pager || true
