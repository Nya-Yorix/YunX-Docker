# syntax=docker/dockerfile:1
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /workspace
COPY --chown=gradle:gradle server/ server/
COPY --chown=gradle:gradle desktop/src/main/kotlin/ desktop/src/main/kotlin/
COPY --chown=gradle:gradle desktop/src/main/resources/icon.png desktop/src/main/resources/icon.png
COPY --chown=gradle:gradle desktop/src/test/kotlin/ desktop/src/test/kotlin/
USER gradle
RUN gradle -p server --no-daemon -Dorg.gradle.jvmargs=-Xmx2g test installDist

FROM eclipse-temurin:21-jre-jammy
# P2P 内核（磁力 / BT / ed2k）：构建期按目标架构下载 Gopeed 内核包内置进镜像，
# 首次启动由服务端自动导入 <dataDir>/gopeed/。版本与桌面版内核来源一致（Gopeed 官方 web 发行版）。
ARG GOPEED_VERSION=1.9.3
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 yunx && useradd --uid 1000 --gid yunx --create-home yunx \
    && mkdir /data /downloads /opt/gopeed-kernel && chown yunx:yunx /data /downloads /opt/gopeed-kernel
ARG TARGETARCH
RUN curl -fsSL -o /opt/gopeed-kernel/kernel.zip \
      "https://github.com/GopeedLab/gopeed/releases/download/v${GOPEED_VERSION}/gopeed-web-v${GOPEED_VERSION}-linux-${TARGETARCH}.zip" \
    && chown yunx:yunx /opt/gopeed-kernel/kernel.zip
COPY --from=build --chown=yunx:yunx /workspace/server/build/install/yunx-server /opt/yunx
ENV YUNX_DESKTOP_DATA_DIR=/data YUNX_DOWNLOAD_DIR=/downloads YUNX_PORT=8080 YUNX_P2P_KERNEL=/opt/gopeed-kernel/kernel.zip
USER yunx
WORKDIR /opt/yunx
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["/opt/yunx/bin/yunx-server"]
