#!/usr/bin/env bash
# 工作管理台 · 本地 MySQL 查看工具
#
# 用法（在 Git Bash 中执行）：
#   bash deploy/db-local.sh                 # 概览：连接信息 + 各表行数
#   bash deploy/db-local.sh tables          # 表结构（建表语句）
#   bash deploy/db-local.sh sql "select id, email, created_at from users limit 5"
#   bash deploy/db-local.sh dbs             # 列出 MySQL 上的库
#
# 说明：
#   - 只连本机 MySQL（127.0.0.1:3306，库名 workhub），不碰服务器
#   - 连接参数从 deploy/.env 读：WORKHUB_DB_URL / WORKHUB_DB_USER / WORKHUB_DB_PASSWORD
#     没配密码时会提示你输入（mysql 的交互式密码提示）
#   - 也可以用图形界面：DBX 里的「工作台」连接
#
# 换库/换端口：在 deploy/.env 里写一行，例如
#   WORKHUB_DB_URL=jdbc:mysql://127.0.0.1:3307/workhub

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$ROOT/deploy/.env" ]; then
  # shellcheck disable=SC1090
  set -a; . "$ROOT/deploy/.env"; set +a
fi

# ---- 找 mysql 客户端 --------------------------------------------------------
find_mysql() {
  if [ -n "${WORKHUB_MYSQL:-}" ] && [ -x "${WORKHUB_MYSQL}" ]; then
    printf '%s' "$WORKHUB_MYSQL"; return 0
  fi
  local c
  for c in "/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
           "/c/Program Files/MySQL/MySQL Server 5.7/bin/mysql.exe" \
           "/c/Program Files/MySQL"/*/bin/mysql.exe \
           "/d/Program Files/MySQL"/*/bin/mysql.exe \
           "/c/xampp/mysql/bin/mysql.exe"; do
    [ -x "$c" ] && { printf '%s' "$c"; return 0; }
  done
  command -v mysql 2>/dev/null && return 0
  return 1
}

MYSQL="$(find_mysql)" || {
  echo "找不到 mysql 客户端。" >&2
  echo "可以显式指定：WORKHUB_MYSQL='/c/Program Files/MySQL/MySQL Server 5.7/bin/mysql.exe' bash deploy/db-local.sh" >&2
  exit 1
}

# ---- 解析 JDBC URL -> host / port / db --------------------------------------
JDBC="${WORKHUB_DB_URL:-jdbc:mysql://127.0.0.1:3306/workhub}"
HOSTPORT="${JDBC#jdbc:mysql://}"      # 127.0.0.1:3306/workhub?xxx
HOSTPORT="${HOSTPORT%%\?*}"           # 127.0.0.1:3306/workhub
DBNAME="${HOSTPORT#*/}"               # workhub
HOSTPORT="${HOSTPORT%%/*}"            # 127.0.0.1:3306
DBHOST="${HOSTPORT%%:*}"
DBPORT="${HOSTPORT##*:}"
[ "$DBPORT" = "$DBHOST" ] && DBPORT=3306
DBUSER="${WORKHUB_DB_USER:-root}"

# 密码：有就传，没有留给 mysql 自己提示
MYSQL_ARGS=(-h "$DBHOST" -P "$DBPORT" -u "$DBUSER" --default-character-set=utf8mb4)
if [ -n "${WORKHUB_DB_PASSWORD:-}" ]; then
  MYSQL_ARGS+=("-p${WORKHUB_DB_PASSWORD}")
fi

run_sql() {
  local sql="$1"
  "$MYSQL" "${MYSQL_ARGS[@]}" -D "$DBNAME" --table -e "$sql"
}

case "${1:-info}" in
  info)
    echo "连接    $DBUSER@$DBHOST:$DBPORT/$DBNAME"
    echo "客户端  $MYSQL"
    if [ -z "${WORKHUB_DB_PASSWORD:-}" ]; then
      echo "密码    未在 deploy/.env 配置，下面会提示输入"
    else
      echo "密码    已从 deploy/.env 读取"
    fi
    echo
    if ! "$MYSQL" "${MYSQL_ARGS[@]}" -e "SELECT VERSION() AS mysql_version;" 2>&1; then
      exit 1
    fi
    echo
    echo "各表行数："
    run_sql "
      SELECT 'users' AS 表, COUNT(*) AS 行数 FROM users
      UNION ALL SELECT 'sessions', COUNT(*) FROM sessions
      UNION ALL SELECT 'software_packages', COUNT(*) FROM software_packages
      UNION ALL SELECT 'daily_reports', COUNT(*) FROM daily_reports
      UNION ALL SELECT 'work_logs', COUNT(*) FROM work_logs
      UNION ALL SELECT 'todos', COUNT(*) FROM todos
      UNION ALL SELECT 'documents', COUNT(*) FROM documents"
    ;;

  tables)
    echo "== 表结构 =="
    run_sql "SHOW TABLES;"
    echo
    set +e
    for t in users sessions software_packages daily_reports work_logs todos documents; do
      echo "--- $t ---"
      run_sql "SHOW CREATE TABLE \`$t\`\G" | sed -n '2,$p'
    done
    ;;

  sql)
    if [ $# -lt 2 ]; then
      echo "用法：bash deploy/db-local.sh sql \"select ...\"" >&2
      exit 2
    fi
    out="$("$MYSQL" "${MYSQL_ARGS[@]}" -D "$DBNAME" --table -e "$2" 2>&1)"
    rc=$?
    if [ $rc -ne 0 ]; then
      echo "SQL 执行失败：${out#*ERROR}" >&2
      exit $rc
    fi
    printf '%s\n' "$out"
    ;;

  dbs)
    run_sql "SHOW DATABASES;"
    ;;

  *)
    sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
