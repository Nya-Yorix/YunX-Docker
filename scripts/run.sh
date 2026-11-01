#!/usr/bin/env bash
# 直接运行 server（非 Docker，前台进程，Ctrl+C 停止）
# 数据目录：data/   下载目录：downloads/   （与 docker compose.yaml 布局一致，均已被 .gitignore 忽略）
#
# 运行时会询问是否重新编译；产物不存在时必须先编译。
#
# 用法：
#   scripts/run.sh                    # 询问是否重编译 → 运行
#   YUNX_PORT=9000 scripts/run.sh     # 自定义端口（默认 8080，被占用自动 +1）
#   YUNX_PASSWORD=xxx scripts/run.sh  # 指定登录密码（留空则首次生成并写入 data/initial-password.txt）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/server/build/install/yunx-server"
DATA_DIR="$ROOT/data"
DOWNLOAD_DIR="$ROOT/downloads"

log() { printf '\033[1;32m[run]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[run] 错误:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- 是否重编译 ----------
if [ ! -x "$APP/bin/yunx-server" ]; then
    log "未找到编译产物，需要先编译"
    REBUILD=y
else
    read -r -p "是否重新编译? [y/N] " REPLY
    case "$REPLY" in [yY]|[yY][eE][sS]) REBUILD=y ;; *) REBUILD=n ;; esac
fi

if [ "$REBUILD" = y ]; then
    bash "$ROOT/scripts/build.sh"
fi
[ -x "$APP/bin/yunx-server" ] || die "编译产物不存在: $APP（编译失败？查看上方日志）"

# ---------- Java 21 ----------
if [ -x "$ROOT/tools/jdk21/bin/java" ]; then
    export JAVA_HOME="$ROOT/tools/jdk21"
elif [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21'; then
    : # 沿用现有 JAVA_HOME
elif java -version 2>&1 | grep -q 'version "21'; then
    : # 系统 java 即 21
else
    die "未找到 Java 21，请先执行 scripts/build.sh（会自动下载 JDK 21 到 tools/jdk21）"
fi

# ---------- 防止重复启动 ----------
mkdir -p "$DATA_DIR" "$DOWNLOAD_DIR"
PIDFILE="$DATA_DIR/yunx-server.pid"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    die "已有实例在运行 (PID $(cat "$PIDFILE"))，先停止它再启动"
fi

# ---------- 端口 ----------
PORT="${YUNX_PORT:-8080}"
if command -v ss >/dev/null 2>&1 && ss -tln | grep -q ":$PORT "; then
    log "端口 $PORT 被占用，改用 $((PORT + 1))"
    PORT=$((PORT + 1))
fi

# ---------- 启动 ----------
export YUNX_DESKTOP_DATA_DIR="$DATA_DIR"
export YUNX_DOWNLOAD_DIR="$DOWNLOAD_DIR"
export YUNX_PORT="$PORT"
export YUNX_USERNAME="${YUNX_USERNAME:-admin}"
export TZ="${TZ:-Asia/Shanghai}"

echo "$$" > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT

log "数据目录: $DATA_DIR"
log "下载目录: $DOWNLOAD_DIR"
log "监听端口: $PORT"
log "启动中... （首次部署密码写入 $DATA_DIR/initial-password.txt）"
cd "$APP"
./bin/yunx-server
