#!/usr/bin/env bash
# 工作管理台 · 一键部署脚本
#
# 用法（在 Git Bash 里执行，或让 WorkBuddy 执行）：
#   bash deploy/deploy.sh frontend   # 只更新前端（改了 src/ 或 index.html）
#   bash deploy/deploy.sh backend    # 只更新后端（改了 server-java/，会先在本地 Maven 打包）
#   bash deploy/deploy.sh all        # 前后端一起更新
#   bash deploy/deploy.sh status     # 只看当前运行状态，不做任何改动
#
# 首次使用需先指定目标服务器（任选一种）：
#   export WORKHUB_SERVER=root@192.168.1.10          # 当前终端有效
#   echo 'WORKHUB_SERVER=root@192.168.1.10' > deploy/.env   # 长期有效，已被 .gitignore 排除
#
# 依赖：本机已配置免密 SSH 到目标服务器；PATH 中有 node（构建前端）与 mvn（打包后端）
#
# 服务器上需要：
#   - JDK 21（默认 /usr/local/jdk-21/bin/java，可用 systemd 单元里的 ExecStart 改）
#   - MySQL 且库 workhub 可连；密码放 /etc/workhub.env（见 deploy/workhub.service 注释）

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 目标服务器：优先环境变量，其次读 deploy/.env（该文件不会提交到仓库）
if [ -f "$ROOT/deploy/.env" ]; then
  # shellcheck disable=SC1090
  . "$ROOT/deploy/.env"
fi
SERVER="${WORKHUB_SERVER:-}"
HOST="${SERVER#*@}"

NODE="${WORKHUB_NODE:-node}"
MVN="${WORKHUB_MVN:-mvn}"
SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=10)

# ---- Windows 中文用户名兼容 ----------------------------------------------
# 用户目录含非 ASCII 字符时（例如 C:\Users\王杰），ssh 无法解析 ~/.ssh：
# 它把路径按本地代码页转换后得到乱码目录名，报 “Could not create directory ...” ，
# 接着因找不到私钥而 Permission denied (publickey)。与服务器配置无关。
# 对策：把密钥复制到纯英文路径，并用 -i / UserKnownHostsFile 显式指定，绕开 ~ 展开。
# 可用 WORKHUB_SSH_DIR 覆盖备用目录（默认 /c/wbssh/.ssh）。
case "$HOME" in
  *[!\ -~]*)   # $HOME 中存在非 ASCII 字符
    KEYDIR="${WORKHUB_SSH_DIR:-/c/wbssh/.ssh}"
    if [ ! -f "$KEYDIR/id_ed25519" ]; then
      mkdir -p "$KEYDIR"
      cp "$HOME"/.ssh/id_* "$KEYDIR/" 2>/dev/null || true
      cp "$HOME"/.ssh/known_hosts "$KEYDIR/" 2>/dev/null || true
      chmod 700 "$KEYDIR" 2>/dev/null || true
      chmod 600 "$KEYDIR"/id_* 2>/dev/null || true
    fi
    if [ -f "$KEYDIR/id_ed25519" ]; then
      SSH_OPTS+=(-i "$KEYDIR/id_ed25519" -o "UserKnownHostsFile=$KEYDIR/known_hosts" -o IdentitiesOnly=yes)
    fi
    ;;
esac

# 过滤掉 SSH 的安全告警横幅，只留有用输出
clean() { grep -v "WARNING\|post-quantum\|openssh.com\|Authorized users only\|known_hosts\|vulnerable" || true; }

action="${1:-}"

build_frontend() {
  echo "==> 构建前端"
  cd "$ROOT"
  "$NODE" node_modules/vite/bin/vite.js build 2>&1 | tail -6
  echo "    产物：$ROOT/dist （$(du -sh dist | cut -f1)）"
}

push_frontend() {
  echo "==> 上传前端到 $SERVER:/var/www/workhub"
  cd "$ROOT"
  tar czf - -C dist . 2>/dev/null | ssh "${SSH_OPTS[@]}" "$SERVER" '
    set -e
    mkdir -p /var/www/workhub.new
    # --warning=no-timestamp：服务器时钟偏慢时，解压会刷一堆「时间戳在未来」警告
    tar xzf - --warning=no-timestamp -C /var/www/workhub.new
    chown -R root:nginx /var/www/workhub.new
    chmod -R a+rX /var/www/workhub.new
    rm -rf /var/www/workhub.bak-*            # 只保留最近一次备份
    mv /var/www/workhub /var/www/workhub.bak-$(date +%Y%m%d-%H%M%S)
    mv /var/www/workhub.new /var/www/workhub
    echo "    已切换，assets $(ls /var/www/workhub/assets | wc -l) 个"
  ' 2>&1 | clean
}

push_backend() {
  echo "==> 构建后端"
  cd "$ROOT/server-java"
  "$MVN" -q -DskipTests package
  local jar="$ROOT/server-java/target/workhub-java.jar"
  if [ ! -f "$jar" ]; then
    echo "    打包失败：找不到 $jar"
    exit 1
  fi
  echo "    产物：$jar （$(ls -lh "$jar" | awk '{print $5}')）"

  echo "==> 上传后端到 $SERVER:/opt/workhub"
  # 先建目录，否则 scp 会失败
  ssh "${SSH_OPTS[@]}" "$SERVER" 'mkdir -p /opt/workhub'
  # 传到 .new 再原子改名：避免边传边启动导致 jar 不完整
  scp "${SSH_OPTS[@]}" "$jar" "$SERVER:/opt/workhub/workhub-java.jar.new" 2>&1 | clean
  scp "${SSH_OPTS[@]}" "$ROOT/deploy/workhub.service" "$SERVER:/opt/workhub/workhub.service" 2>&1 | clean
  ssh "${SSH_OPTS[@]}" "$SERVER" '
    set -e
    mv -f /opt/workhub/workhub-java.jar.new /opt/workhub/workhub-java.jar
    cp -f /opt/workhub/workhub.service /etc/systemd/system/workhub.service
    systemctl daemon-reload
    systemctl restart workhub
    sleep 5
    echo "    服务状态 $(systemctl is-active workhub) / 开机自启 $(systemctl is-enabled workhub)"
    systemctl is-active workhub >/dev/null || journalctl -u workhub -n 20 --no-pager
  ' 2>&1 | clean
}

verify() {
  echo ""
  echo "==> 验证（$HOST）"
  curl -s -o /dev/null -w "    首页      HTTP=%{http_code}  time=%{time_total}s\n" -m 20 "http://$HOST:8099/"
  echo "    健康检查  $(curl -s -m 20 "http://$HOST:8099/api/health")"
  curl -s -o /dev/null -w "    未登录鉴权 HTTP=%{http_code}（应为 401）\n" -m 20 "http://$HOST:8099/api/software"
}

show_status() {
  echo "===== 本地源码 ====="
  echo "  前端  $ROOT/src        （视图 $ROOT/src/views）"
  echo "  后端  $ROOT/server-java （产物 $ROOT/server-java/target/workhub-java.jar）"
  echo ""
  echo "===== 服务器运行状态 ====="
  ssh "${SSH_OPTS[@]}" "$SERVER" '
    echo "  后端服务   $(systemctl is-active workhub) / 开机自启 $(systemctl is-enabled workhub)"
    echo "  nginx      $(systemctl is-active nginx)"
    echo "  端口监听   $(ss -lnt | grep -cE ":(8098|8099)") 条（8098 后端 + 8099 对外，IPv4/IPv6 各计一条）"
    echo "  数据目录   /var/lib/workhub （$(du -sh /var/lib/workhub | cut -f1)）"
  ' 2>&1 | clean
  echo ""
  verify
}

case "$action" in
  frontend|backend|all|status) ;;
  *)
    sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac

if [ -z "$SERVER" ]; then
  cat <<'EOF'
未配置目标服务器。任选一种方式配置后重试：

  1) 当前终端生效
     export WORKHUB_SERVER=root@192.168.1.10

  2) 长期生效（会写入 deploy/.env，该文件已被 .gitignore 排除，不会提交到仓库）
     printf 'WORKHUB_SERVER=root@192.168.1.10\n' > deploy/.env

EOF
  exit 1
fi

case "$action" in
  frontend) build_frontend; push_frontend; verify ;;
  backend)  push_backend;  verify ;;
  all)      build_frontend; push_frontend; push_backend; verify ;;
  status)   show_status ;;
esac

echo ""
echo "完成。访问 http://$HOST:8099/"
