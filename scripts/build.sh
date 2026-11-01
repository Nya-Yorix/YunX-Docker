#!/usr/bin/env bash
# 非 Docker 编译：编译 server 模块（跑测试 + installDist）
# 产物：server/build/install/yunx-server
#
# 本机没有 JDK 21 / Gradle 时，自动下载到 tools/（国内镜像，无需代理）：
#   tools/jdk21/            Microsoft OpenJDK 21
#   tools/gradle-<ver>/     Gradle
#
# 用法：
#   scripts/build.sh              # 测试 + 编译
#   SKIP_TESTS=1 scripts/build.sh # 跳过测试
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/tools"
GRADLE_VERSION="${GRADLE_VERSION:-8.14.3}"
JVM_ARGS="-Xmx2g"

log() { printf '\033[1;36m[build]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build] 错误:\033[0m %s\n' "$*" >&2; exit 1; }

mkdir -p "$TOOLS"

# ---------- JDK 21 ----------
find_system_jdk21() {
    local cand
    for cand in /usr/lib/jvm/*21* /opt/jdk*21* /usr/java/jdk-21*; do
        [ -x "$cand/bin/java" ] && { echo "$cand"; return 0; }
    done
    return 1
}

JDK_HOME=""
if [ -x "$TOOLS/jdk21/bin/java" ]; then
    JDK_HOME="$TOOLS/jdk21"
    log "使用本地 JDK: $JDK_HOME"
elif JDK_HOME=$(find_system_jdk21); then
    log "使用系统 JDK 21: $JDK_HOME"
else
    case "$(uname -m)" in
        x86_64)  JDK_ARCH="x64" ;;
        aarch64) JDK_ARCH="aarch64" ;;
        *) die "不支持的架构: $(uname -m)" ;;
    esac
    JDK_URL="https://aka.ms/download-jdk/microsoft-jdk-21-linux-${JDK_ARCH}.tar.gz"
    JDK_TGZ="$TOOLS/microsoft-jdk-21-linux-${JDK_ARCH}.tar.gz"
    log "下载 JDK 21: $JDK_URL"
    curl -fL --retry 3 -o "$JDK_TGZ" "$JDK_URL" || die "JDK 下载失败，检查网络或手动放置到 $TOOLS/jdk21"
    rm -rf "$TOOLS/jdk21.tmp"
    mkdir -p "$TOOLS/jdk21.tmp"
    tar -xzf "$JDK_TGZ" -C "$TOOLS/jdk21.tmp" --strip-components=1
    mv "$TOOLS/jdk21.tmp" "$TOOLS/jdk21"
    rm -f "$JDK_TGZ"
    JDK_HOME="$TOOLS/jdk21"
    log "JDK 21 就绪: $JDK_HOME"
fi

# ---------- Gradle ----------
GRADLE_HOME="$TOOLS/gradle-$GRADLE_VERSION"
if [ -x "$GRADLE_HOME/bin/gradle" ]; then
    log "使用本地 Gradle: $GRADLE_HOME"
else
    GRADLE_URL="https://mirrors.cloud.tencent.com/gradle/gradle-$GRADLE_VERSION-bin.zip"
    GRADLE_ZIP="$TOOLS/gradle-$GRADLE_VERSION-bin.zip"
    log "下载 Gradle $GRADLE_VERSION: $GRADLE_URL"
    curl -fL --retry 3 -o "$GRADLE_ZIP" "$GRADLE_URL" \
        || curl -fL --retry 3 -o "$GRADLE_ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
        || die "Gradle 下载失败，检查网络或手动放置 zip 到 $TOOLS"
    rm -rf "$GRADLE_HOME.tmp"
    mkdir -p "$GRADLE_HOME.tmp"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q "$GRADLE_ZIP" -d "$GRADLE_HOME.tmp"
    else
        (cd "$GRADLE_HOME.tmp" && "$JDK_HOME/bin/jar" -xf "$GRADLE_ZIP") || die "解压失败：请安装 unzip"
    fi
    chmod +x "$GRADLE_HOME.tmp/gradle-$GRADLE_VERSION/bin/gradle" 2>/dev/null || true
    mv "$GRADLE_HOME.tmp/gradle-$GRADLE_VERSION" "$GRADLE_HOME"
    rmdir "$GRADLE_HOME.tmp"
    rm -f "$GRADLE_ZIP"
    log "Gradle 就绪: $GRADLE_HOME"
fi

# ---------- 编译 ----------
TEST_ARGS=()
[ "${SKIP_TESTS:-0}" = "1" ] && TEST_ARGS=(-x test)

log "开始编译 server（测试 + installDist）..."
cd "$ROOT"
JAVA_HOME="$JDK_HOME" "$GRADLE_HOME/bin/gradle" -p server --no-daemon \
    -Dorg.gradle.jvmargs="$JVM_ARGS" \
    "${TEST_ARGS[@]}" test installDist

log "编译完成: $ROOT/server/build/install/yunx-server"
