#!/usr/bin/env bash
# 工作管理台 · Docker 管理脚本
#
# 用法（Git Bash / Linux / macOS 通用）：
#   bash deploy/docker.sh build                构建镜像 workhub:1.0.0
#   bash deploy/docker.sh up                   启动 db + app（后台），并打印访问地址
#   bash deploy/docker.sh ps                   查看容器状态与健康检查结果
#   bash deploy/docker.sh logs [服务]          跟随日志，服务默认 app（可选 db）
#   bash deploy/docker.sh restart              重启
#   bash deploy/docker.sh down                 停止并移除容器（数据卷保留）
#   bash deploy/docker.sh sh                   进入 app 容器（bash）
#   bash deploy/docker.sh sql "select ..."     在 app 容器里连 MySQL 执行 SQL
#   bash deploy/docker.sh save [文件名]        导出镜像为 tar.gz，用于离线拷到内网服务器
#   bash deploy/docker.sh load <文件名>        从 tar.gz 导入镜像
#   bash deploy/docker.sh push <仓库前缀>      打标签并推送到镜像仓库
#   bash deploy/docker.sh nuke                 停止 + 删除数据卷（⚠️ 库和上传文件全没）
#
# 说明：
#   - 自动读取 deploy/.env（数据库密码等），不用手工 export
#   - 基础镜像默认取 public.ecr.aws（Docker Hub 不通时的替代），
#     想换回官方：REGISTRY=docker.io/library bash deploy/docker.sh build

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

IMAGE="workhub:1.0.0"
ENV_FILE="$ROOT/deploy/.env"

# ---- 读 deploy/.env ---------------------------------------------------------
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
fi
REGISTRY="${REGISTRY:-public.ecr.aws/docker/library}"

# ---- docker compose 包装：统一带上 --env-file --------------------------------
dc() {
  if [ -f "$ENV_FILE" ]; then
    docker compose --env-file "$ENV_FILE" "$@"
  else
    docker compose "$@"
  fi
}

# ---- 前置检查：守护进程是否在跑 ---------------------------------------------
ensure_daemon() {
  if ! docker info >/dev/null 2>&1; then
    cat >&2 <<'EOF'
✗ 连不上 Docker 守护进程。

  请先启动 Docker Desktop（Windows 上就是开始菜单里那个鲸鱼图标），
  等它变成绿色 / 显示 "Engine running" 后重试：
      docker desktop start        # 或直接点图标
  自检：
      docker info
EOF
    exit 1
  fi
}

# ---- 把 jdbc:mysql://xxx:port/db?... 解析成 mysql 客户端参数 ---------------
mysql_args_from_url() {
  url="${WORKHUB_DB_URL:-jdbc:mysql://db:3306/workhub}"
  # 去掉 jdbc: 前缀和 ? 之后的参数
  hostport="${url#jdbc:mysql://}"
  hostport="${hostport%%\?*}"
  hostport="${hostport%%/*}"
  printf '%s' "${hostport#*:}"
}

usage() {
  # 打印文件开头的连续注释块（跳过 shebang），加命令时不用维护行号
  awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"
  exit "${1:-0}"
}

case "${1:-}" in
  # ---------------------------------------------------------------- 构建
  build)
    ensure_daemon
    echo "[build] 基础镜像来源：$REGISTRY"
    echo "[build] 构建 $IMAGE（首次约 5-15 分钟，取决于网速）"
    dc build --build-arg "REGISTRY=$REGISTRY" app || exit 1
    echo "[build] 完成"
    docker images "$IMAGE" --format '  {{.Repository}}:{{.Tag}}  {{.Size}}  ({{.CreatedSince}})'
    ;;

  # ---------------------------------------------------------------- 启动
  up)
    ensure_daemon
    dc up -d --build || exit 1
    echo
    echo "[up] 等待健康检查…"
    for i in $(seq 1 30); do
      state=$(docker inspect -f '{{.State.Health.Status}}' workhub-app 2>/dev/null || echo "starting")
      case "$state" in
        healthy) echo "[up] app 已就绪 ✓"; break ;;
        unhealthy) echo "[up] ⚠️ app 健康检查失败，用 logs 看原因：bash deploy/docker.sh logs" >&2; break ;;
      esac
      printf '  (%d/30) %s\r' "$i" "$state"
      sleep 3
    done
    echo
    dc ps
    echo
    echo "  访问：http://localhost:8098"
    ;;

  # ---------------------------------------------------------------- 停止
  down)     ensure_daemon; dc down ;;
  restart)  ensure_daemon; dc restart ;;

  # ---------------------------------------------------------------- 观测
  ps)       ensure_daemon; dc ps ;;
  logs)     ensure_daemon; dc logs -f --tail=200 "${2:-app}" ;;

  # ---------------------------------------------------------------- 进容器
  sh)
    ensure_daemon
    docker exec -it workhub-app bash
    ;;

  # ---------------------------------------------------------------- 查库
  sql)
    ensure_daemon
    [ -z "${2:-}" ] && { echo "用法：bash deploy/docker.sh sql \"select * from users limit 5\"" >&2; exit 1; }
    port=$(mysql_args_from_url)
    docker exec -i workhub-db mysql -h 127.0.0.1 -P "${port:-3306}" \
      -u"${WORKHUB_DB_USER:-root}" -p"${WORKHUB_DB_PASSWORD:-}" workhub -e "$2"
    ;;

  # ---------------------------------------------------------------- 离线分发
  save)
    ensure_daemon
    out="${2:-$ROOT/workhub-1.0.0.tar.gz}"
    echo "[save] 导出 $IMAGE → $out"
    docker save "$IMAGE" | gzip -9 > "$out" || exit 1
    echo "[save] 完成：$(ls -lh "$out" | awk '{print $5}')"
    echo "[save] 拷到目标机器后：bash deploy/docker.sh load $(basename "$out")"
    ;;

  load)
    ensure_daemon
    [ -z "${2:-}" ] && { echo "用法：bash deploy/docker.sh load workhub-1.0.0.tar.gz" >&2; exit 1; }
    [ -f "$2" ] || { echo "✗ 文件不存在：$2" >&2; exit 1; }
    echo "[load] 导入 $2"
    case "$2" in
      *.gz) gzip -dc "$2" | docker load ;;
      *)    docker load -i "$2" ;;
    esac
    ;;

  # ---------------------------------------------------------------- 推仓库
  push)
    ensure_daemon
    prefix="${2:-}"
    [ -z "$prefix" ] && { echo "用法：bash deploy/docker.sh push registry.example.com/workhub" >&2; exit 1; }
    tag="$prefix:$([ "${prefix##*:}" = "$prefix" ] && echo 1.0.0 || echo "${prefix##*:}")"
    base="${prefix%:*}"
    echo "[push] $IMAGE → $base:1.0.0"
    docker tag "$IMAGE" "$base:1.0.0" || exit 1
    docker push "$base:1.0.0" || exit 1
    echo "[push] 完成。目标机器上：docker pull $base:1.0.0 && docker tag $base:1.0.0 $IMAGE"
    ;;

  # ---------------------------------------------------------------- 危险操作
  nuke)
    ensure_daemon
    cat >&2 <<'EOF'
⚠️ 此操作会删除容器 **以及数据卷**：
     - MySQL 里的全部业务数据
     - /data 下上传的文件与分享签名密钥
   不可恢复。确认要执行请重新输入并加 --yes：
     bash deploy/docker.sh nuke --yes
EOF
    [ "${2:-}" = "--yes" ] || exit 1
    dc down -v
    echo "[nuke] 已删除容器与数据卷"
    ;;

  ""|-h|--help|help) usage 0 ;;
  *) echo "未知命令：$1" >&2; echo; usage 1 ;;
esac
