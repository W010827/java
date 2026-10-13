#!/usr/bin/env python3
"""
WorkHub 旧版 SQLite → MySQL 迁移 SQL 生成器

只做一件事：把旧 SQLite 库里的业务数据导出成一串 INSERT 语句（写 stdout）。
不连接、不修改任何数据库 —— 导入由你手动执行。迁移是一次性动作，
看得见 SQL 比"自动跑一遍"更让人放心。

用法（在能读到 workhub.db 的机器上执行，服务器上也可以）：

    # 1) 生成 SQL（只读源库）
    python3 deploy/migrate-sqlite-to-mysql.py /var/lib/workhub/workhub.db \\
        > /tmp/workhub-migrate.sql

    # 2) 建库建表（幂等，重复执行不改已有数据）
    mysql -uroot -p'密码' --default-character-set=utf8mb4 < deploy/mysql-schema.sql

    # 3) 导入
    mysql -uroot -p'密码' --default-character-set=utf8mb4 workhub < /tmp/workhub-migrate.sql

    # 4) 文件里含密码哈希，迁完销毁
    shred -u /tmp/workhub-migrate.sql

迁移完建议核对（三条都要过）：
  * 每张表 SQLite 与 MySQL 的 COUNT(*) 一致
  * users 的 pwd_salt/pwd_hash 逐字节相同（用 md5 比对，别打印原文）
  * information_schema.TABLES 里各表 AUTO_INCREMENT 等于原最大 id + 1

设计要点：
  * 显式带上主键 id，保证 MySQL 自增序列接着原值往下走，不会覆盖已有记录
  * 值全部按 MySQL 规则转义（反斜杠、单引号、控制字符），中文靠
    --default-character-set=utf8mb4 保证，不依赖客户端默认字符集
  * 表清单写死，只迁业务表（sqlite_sequence 这类 SQLite 内部表跳过）
"""

import sqlite3
import sys

# 与 deploy/mysql-schema.sql 里的表一一对应，顺序即导入顺序
TABLES = ['users', 'sessions', 'software_packages',
          'daily_reports', 'work_logs', 'todos', 'documents']


def esc(v):
    """把 Python 值转成 MySQL 字面量。"""
    if v is None:
        return 'NULL'
    if isinstance(v, bool):
        return '1' if v else '0'
    if isinstance(v, (int, float)):
        return repr(v)
    s = str(v)
    s = (s.replace('\\', '\\\\')
          .replace("'", "\\'")
          .replace('\n', '\\n')
          .replace('\r', '\\r')
          .replace('\0', '\\0')
          .replace('\x1a', '\\Z'))
    return "'" + s + "'"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    src = sys.argv[1]

    conn = sqlite3.connect('file:%s?mode=ro' % src, uri=True)
    out = sys.stdout
    out.write('-- 由 deploy/migrate-sqlite-to-mysql.py 生成\n')
    out.write('-- 源库：%s\n\n' % src)
    out.write('SET NAMES utf8mb4;\n\n')

    total = 0
    for t in TABLES:
        cols = [r[1] for r in conn.execute('PRAGMA table_info("%s")' % t)]
        if not cols:
            sys.stderr.write('  跳过不存在的表：%s\n' % t)
            continue
        rows = conn.execute('SELECT * FROM "%s"' % t).fetchall()
        out.write('-- %s：%d 行\n' % (t, len(rows)))
        collist = ', '.join('`%s`' % c for c in cols)
        for row in rows:
            out.write('INSERT INTO `%s` (%s) VALUES (%s);\n'
                      % (t, collist, ', '.join(esc(v) for v in row)))
        out.write('\n')
        total += len(rows)

    out.write('-- 合计 %d 行\n' % total)
    conn.close()
    sys.stderr.write('已生成 %d 行 INSERT\n' % total)


if __name__ == '__main__':
    main()
