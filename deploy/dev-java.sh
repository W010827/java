#!/usr/bin/env bash
# 本地开发 · SpringBoot 后端（MySQL）+ 前端
#
#   bash deploy/dev-java.sh          # 前后端一起起，Ctrl+C 全部退出
#   bash deploy/dev-java.sh api      # 只起后端（SpringBoot，127.0.0.1:8098）
#   bash deploy/dev-java.sh web      # 只起前端（http://localhost:5180）
#   bash deploy/dev-java.sh build    # 只编译打包后端 jar
#   bash deploy/dev-java.sh stop     # 端口没退干净时，强制结束这两个进程
#
# 前端页面 http://localhost:5180，改 src/ 下的文件会自动热更新。
# 页面里的 /api 请求由 Vite 转发到后端。
#
# 数据库是 MySQL（库名 workhub），连接参数走环境变量：
#   WORKHUB_DB_URL / WORKHUB_DB_USER / WORKHUB_DB_PASSWORD
# 密码建议写在 deploy/.env 里（该文件已被 .gitignore 排除），本脚本会自动加载：
#   echo 'WORKHUB_DB_PASSWORD=你的密码' >> deploy/.env
# 建库建表：mysql -uroot -p < deploy/mysql-schema.sql（不执行也行，后端启动会自动建表）
#
# 上传的文件仍在本地：{base-dir}/storage，分享签名密钥仍是 {base-dir}/secret.key。
#
# 想用服务器上的真实数据调样式（只起前端即可）：
#   WORKHUB_API=http://<服务器地址>:8099 bash deploy/dev-java.sh web

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 本地环境变量（数据库密码、部署目标等），不进仓库
if [ -f "$ROOT/deploy/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/deploy/.env"
  set +a
fi

DB_URL_SHOWN="${WORKHUB_DB_URL:-jdbc:mysql://127.0.0.1:3306/workhub}"
DB_USER_SHOWN="${WORKHUB_DB_USER:-root}"

NODE="${WORKHUB_NODE:-node}"
API_PORT="${WORKHUB_PORT:-8098}"
WEB_PORT="${PORT:-5180}"
DATA_DIR="$ROOT/.devdata"
JAR="$ROOT/server-java/target/workhub-java.jar"
API_TARGET="${WORKHUB_API:-http://127.0.0.1:$API_PORT}"

# MSYS 路径 → Windows 路径（Java 不认 /d/... 这种写法）
win_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# ---------------------------------------------------------------------------
# 找 java：Git Bash 里直接调 `java` 会段错误
#   （PATH 上那个 Oracle javapath 垫片在 MSYS 下跑不起来，rc=139），
#   所以必须落到真正的 java.exe 上。这里按候选顺序逐个实测，能用才选。
# ---------------------------------------------------------------------------
find_java() {
  if [ -n "${WORKHUB_JAVA:-}" ]; then
    printf '%s' "$WORKHUB_JAVA"
    return 0
  fi
  local cands=()
  [ -n "${JAVA_HOME:-}" ] && cands+=("$JAVA_HOME/bin/java.exe")
  local g
  for g in "/d/Program Files/java"*/bin/java.exe \
           "/c/Program Files/Java"/*/bin/java.exe \
           "/d/Program Files/Java"/*/bin/java.exe \
           "/c/Program Files/Eclipse Adoptium"/*/bin/java.exe \
           "/c/Program Files/Microsoft"/jdk*/bin/java.exe; do
    [ -x "$g" ] && cands+=("$g")
  done
  [ -n "${JDK_HOME:-}" ] && cands+=("$JDK_HOME/bin/java.exe")
  local c
  for c in "${cands[@]}"; do
    if "$c" -version >/dev/null 2>&1; then printf '%s' "$c"; return 0; fi
  done
  # 最后才试 PATH 上的 java
  if command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1; then
    command -v java
    return 0
  fi
  return 1
}

port_busy() {
  netstat -ano 2>/dev/null | grep ":$1" | grep -iq 'listening'
}

port_pids() {
  netstat -ano 2>/dev/null | grep ":$1" | grep -i 'listening' | awk '{print $NF}' | sort -u
}

kill_port() {
  local n=0
  for pid in $(port_pids "$1"); do
    if command -v taskkill >/dev/null 2>&1; then
      taskkill //F //PID "$pid" >/dev/null 2>&1 && n=$((n + 1)) || true
    else
      kill "$pid" 2>/dev/null && n=$((n + 1)) || true
    fi
  done
  echo "$n"
}

build_jar() {
  # Windows 会锁住正在运行的 jar：这时 spring-boot:repackage 改名失败，
  # mvn 会在 target/ 里留下一个几十 KB 的**假包**，把原本能用的产物覆盖掉。
  # 所以打包前先确认后端没在跑（含 IDEA 里起的那个，按端口找得到）。
  if port_busy "$API_PORT"; then
    echo "[build] 端口 $API_PORT 上有后端在运行，jar 被锁住会导致打包失败并留下假包"
    echo "        请先执行：bash deploy/dev-java.sh stop"
    exit 1
  fi
  echo "[build] 编译打包 server-java → $JAR"
  ( cd "$ROOT/server-java" && mvn -q -DskipTests package )
  local size
  size="$(ls -lh "$JAR" | awk '{print $5}')"
  # 兜底自检：正常 fat jar 约 24MB，只有几十 KB 说明是 repackage 失败的半成品
  if [ "$(stat -c%s "$JAR" 2>/dev/null || echo 0)" -lt 1000000 ]; then
    echo "[build] 打包结果异常（$size），疑似只有 classes 没有依赖，请检查上面的 Maven 输出"
    exit 1
  fi
  echo "[build] 完成：$size"
}

start_api() {
  if port_busy "$API_PORT"; then
    echo "[api] 端口 $API_PORT 已在监听，跳过启动"
    echo "      若是上次留下的进程，执行：bash deploy/dev-java.sh stop"
    return
  fi
  local java_bin
  if ! java_bin="$(find_java)"; then
    echo "[api] 找不到可用的 java（需要 JDK 21+）"
    echo "      可以显式指定：WORKHUB_JAVA='/d/Program Files/java25/bin/java.exe' bash deploy/dev-java.sh api"
    exit 1
  fi
  if [ ! -f "$JAR" ]; then
    echo "[api] 还没打包，先执行：bash deploy/dev-java.sh build"
    exit 1
  fi
  mkdir -p "$DATA_DIR"
  echo "[api] 后端 http://127.0.0.1:$API_PORT   数据目录 $DATA_DIR"
  echo "      数据库 ${DB_URL_SHOWN#jdbc:mysql://}（用户 $DB_USER_SHOWN）"
  if [ -z "${WORKHUB_DB_PASSWORD:-}" ]; then
    echo "      提示：未设置 WORKHUB_DB_PASSWORD。若 MySQL 有密码，请写入 deploy/.env："
    echo "            echo 'WORKHUB_DB_PASSWORD=你的密码' >> deploy/.env"
  fi
  echo "      $java_bin"
  cd "$ROOT"
  # 端口用命令行参数显式指定：宿主环境可能注入 SERVER__PORT 之类的变量，
  # 会把 Spring 的 server.port 顶掉（详见 WorkhubApplication#bindServerEndpoint）。
  exec "$java_bin" -jar "$JAR" \
    --server.port="$API_PORT" \
    --server.address=127.0.0.1 \
    --workhub.base-dir="$(win_path "$DATA_DIR")" \
    --workhub.static-dir="$(win_path "$ROOT/dist")"
}

start_web() {
  if [ ! -d "$ROOT/node_modules" ]; then
    echo "[web] 缺少依赖，请先执行：npm install"
    exit 1
  fi
  if port_busy "$WEB_PORT"; then
    echo "[web] 端口 $WEB_PORT 已在监听，跳过启动"
    echo "      若是上次留下的进程，执行：bash deploy/dev-java.sh stop"
    return
  fi
  echo "[web] 前端 http://localhost:$WEB_PORT   接口转发到 $API_TARGET"
  cd "$ROOT"
  exec env WORKHUB_API="$API_TARGET" PORT="$WEB_PORT" \
    "$NODE" node_modules/vite/bin/vite.js
}

stop_all() {
  local a w
  a="$(kill_port "$API_PORT")"
  w="$(kill_port "$WEB_PORT")"
  echo "[stop] 后端 $API_PORT 结束 $a 个进程，前端 $WEB_PORT 结束 $w 个进程"
  sleep 1
  for p in "$API_PORT" "$WEB_PORT"; do
    if port_busy "$p"; then echo "      端口 $p 仍被占用"; else echo "      端口 $p 已释放"; fi
  done
}

case "${1:-all}" in
  api) start_api ;;
  web) start_web ;;
  build) build_jar ;;
  stop) stop_all ;;
  all)
    start_api &
    api_job=$!
    start_web &
    web_job=$!
    # Ctrl+C 时连同两个子进程一起收走，避免留下占着端口的孤儿进程
    trap 'kill $api_job $web_job 2>/dev/null || true; wait 2>/dev/null || true; exit 0' INT TERM
    wait
    ;;
  *)
    sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
