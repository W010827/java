# WorkHub 生产镜像 · 三阶段构建
#
#   Stage 1  web  —— Node 编译 Vue 前端，产出静态文件 dist/
#   Stage 2  api  —— Maven 编译 SpringBoot 后端，产出可执行 jar
#   Stage 3  run  —— 只装 JRE + jar + dist，非 root 运行
#
# 最终镜像里没有 Node、没有 Maven、没有源码，也没有 node_modules。
#
# 构建：
#   docker build -t workhub:1.0.0 .
#
# ⚠️ 基础镜像来源
#   默认走 public.ecr.aws/docker/library —— 这是 AWS 公共 ECR 对 Docker 官方镜像库的
#   完整镜像，内容与 Docker Hub 上的 library/* 一致，但在国内网络下通常可直连。
#   若你的网络能直连 Docker Hub，可以改回官方：
#     docker build --build-arg REGISTRY=docker.io/library -t workhub:1.0.0 .

ARG REGISTRY=public.ecr.aws/docker/library


# ============================================================
# Stage 1 / 3 —— 前端：Vue 3 + Vite
# ============================================================
FROM ${REGISTRY}/node:22-alpine AS web
WORKDIR /web

# 先只拷依赖清单：只要 package.json 没变，这一层就命中缓存，
# 改业务代码不会重新装依赖（省几分钟）。
#
# ⚠️ 这里**故意不拷 package-lock.json**，用 npm install 而不是 npm ci：
#   锁文件是在 Windows 上生成的，里面只记录了 win32 的平台专属二进制
#   （@rollup/rollup-win32-x64-msvc、@esbuild/win32-x64），一个 linux 版本都没有 ——
#   这是 npm 的已知行为（可选依赖只记录"生成锁文件那一台机器"的版本）。
#   而 `npm ci` 严格照锁文件装，于是 Linux 容器里 rollup / esbuild 找不到自己的原生模块，
#   构建直接报：Cannot find module '@rollup/rollup-linux-x64-musl'
#   改成在容器内按 package.json 重新解析，npm 就会拉取 linux 版的那两个包。
#   （想恢复严格的 npm ci：先在 Linux 环境跑一次 npm install 生成完整锁文件再提交。）
COPY package.json ./
RUN npm install --no-audit --no-fund

# 再拷源码构建
COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build


# ============================================================
# Stage 2 / 3 —— 后端：Maven + JDK 21
# ============================================================
FROM ${REGISTRY}/maven:3.9-eclipse-temurin-21 AS api
WORKDIR /api

# Maven 中央仓在国内经常连不稳，用阿里云镜像（public 仓库完整代理 central）。
# 放 /root/.m2/settings.xml 是因为 maven 官方镜像默认就是 root 身份构建。
COPY deploy/maven-settings.xml /root/.m2/settings.xml

# 同理：pom.xml 不变就复用依赖缓存
COPY server-java/pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline || true

COPY server-java/src ./src
# 不使用 -q：编译出错时需要看到原因
RUN mvn -B -DskipTests package


# ============================================================
# Stage 3 / 3 —— 运行
# ============================================================
FROM ${REGISTRY}/eclipse-temurin:21-jre AS runtime

LABEL org.opencontainers.image.title="WorkHub" \
      org.opencontainers.image.description="个人工作管理平台（SpringBoot + Vue 3 + MySQL）" \
      org.opencontainers.image.version="1.0.0"

ENV TZ=Asia/Shanghai
# curl 仅用于 HEALTHCHECK；顺手装 tzdata 让时区生效
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && ln -snf "/usr/share/zoneinfo/$TZ" /etc/localtime \
 && echo "$TZ" > /etc/timezone \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 从构建阶段取产物：后端 jar + 前端静态文件
COPY --from=api /api/target/workhub-java.jar /app/app.jar
COPY --from=web /web/dist /app/dist

# 非 root 运行
RUN groupadd -r workhub \
 && useradd -r -g workhub -M -d /app workhub \
 && mkdir -p /data \
 && chown -R workhub:workhub /app /data

# 数据目录：上传的文件（storage/）+ 分享链接签名密钥（secret.key）。
# ⚠️ 必须挂卷持久化 —— secret.key 一旦重新生成，之前发出去的分享链接会全部失效。
VOLUME ["/data"]

# WORKHUB_HOST 一定要是 0.0.0.0：
# 代码里默认值是 127.0.0.1（本机跑时的安全默认），在容器里那样就只有容器自己能访问。
ENV WORKHUB_BASE=/data \
    WORKHUB_STATIC=/app/dist \
    WORKHUB_PORT=8098 \
    WORKHUB_HOST=0.0.0.0

USER workhub
EXPOSE 8098

# 容器内自检，编排时可直接用 (healthy) 作为依赖条件
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:8098/api/health" || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
