#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
接口冒烟测试 —— 打任意一份 WorkHub 后端。

    python deploy/smoke_test.py                        # 默认打 http://127.0.0.1:8098
    python deploy/smoke_test.py http://127.0.0.1:8099  # 打另一个端口/机器

覆盖的关键路径：
  认证 · 注册/重复注册/格式校验/登录/错误密码/会话/改密/登出
  业务 · 待办、工作日志、日报、资料、软件仓库 的 增/查/改/删
  文件 · 裸 body 流式上传 → 下载（token）→ 签名分享下载（无 token）→ 删除
  契约 · 列表返回裸数组、/reports/one 空值时返回裸 null、错误信封 {error:{kind,...}}
  细节 · X-Client-Time 双时钟、密码派生独立复算（需能连到 MySQL，见下）

「密码派生」那两项要读库复算，连接参数从环境变量或 deploy/.env 读
（WORKHUB_DB_URL / WORKHUB_DB_USER / WORKHUB_DB_PASSWORD）。读不到就跳过，不算失败。

所有测试数据在结束时清理（用户、记录、上传的文件），不污染库。
退出码 0 = 全部通过；1 = 有失败项。
"""
import hashlib
import hmac
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8098').rstrip('/')
PBKDF2_ROUNDS = 120000

_passed, _failed = 0, 0


def check(name, cond, extra=''):
    global _passed, _failed
    if cond:
        _passed += 1
        print('  [ok]   ' + name)
    else:
        _failed += 1
        print('  [FAIL] ' + name + (('   <- ' + str(extra)) if extra != '' else ''))
    return cond


def req(method, path, body=None, token=None, raw=None, headers=None):
    """发请求。返回 (status, 解析后的 json 或 bytes, headers, 原始 bytes)。"""
    h = dict(headers or {})
    data = None
    if raw is not None:
        data = raw
        h.setdefault('Content-Type', 'application/octet-stream')
    elif body is not None:
        data = json.dumps(body).encode('utf-8')
        h['Content-Type'] = 'application/json'
    if token:
        h['Authorization'] = 'Bearer ' + token
    r = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            b = resp.read()
    except urllib.error.HTTPError as e:
        b = e.read()
        return _parse(e.code, b, dict(e.headers))
    return _parse(200, b, dict(resp.headers))


def _parse(status, b, hdrs):
    try:
        return status, json.loads(b.decode('utf-8')), hdrs, b
    except Exception:
        return status, b, hdrs, b


def err_kind(j):
    if isinstance(j, dict) and isinstance(j.get('error'), dict):
        return j['error'].get('kind')
    return None


# ---------------------------------------------------------------------------
# 直连 MySQL 读原始行：用于「独立复算 pwd_hash」这类必须绕过接口的断言。
# 连不上就跳过（不算失败）—— 这首先是个接口测试，不该因为本机没配库就跑不了。
# ---------------------------------------------------------------------------
def _find_mysql():
    import glob
    from shutil import which
    cands = []
    if os.environ.get('WORKHUB_MYSQL'):
        cands.append(os.environ['WORKHUB_MYSQL'])
    for pat in (r'C:\Program Files\MySQL\*\bin\mysql.exe',
                r'D:\Program Files\MySQL\*\bin\mysql.exe',
                r'C:\xampp\mysql\bin\mysql.exe'):
        cands.extend(sorted(glob.glob(pat)))
    cands.append(which('mysql'))
    for c in cands:
        if c and os.path.exists(c):
            return c
    return None


def _db_conf():
    """连接参数：环境变量优先，其次 deploy/.env，最后默认值。"""
    env = {}
    envfile = os.path.join(os.path.dirname(os.path.abspath(__file__)), '.env')
    if os.path.exists(envfile):
        with open(envfile, encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#') or '=' not in line:
                    continue
                k, v = line.split('=', 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    jdbc = (os.environ.get('WORKHUB_DB_URL') or env.get('WORKHUB_DB_URL')
            or 'jdbc:mysql://127.0.0.1:3306/workhub')
    hostport = jdbc.split('//', 1)[-1].split('?')[0]        # 127.0.0.1:3306/workhub
    db = hostport.split('/', 1)[1] if '/' in hostport else 'workhub'
    hp = hostport.split('/', 1)[0]
    host, _, port = hp.partition(':')
    return {
        'host': host or '127.0.0.1',
        'port': port or '3306',
        'db': db,
        'user': os.environ.get('WORKHUB_DB_USER') or env.get('WORKHUB_DB_USER') or 'root',
        'password': (os.environ.get('WORKHUB_DB_PASSWORD')
                     or env.get('WORKHUB_DB_PASSWORD') or ''),
    }


def mysql_query(sql):
    """执行查询，返回第一行的字段列表；连不上或出错返回 None。"""
    exe = _find_mysql()
    if not exe:
        return None
    c = _db_conf()
    args = [exe, '-h', c['host'], '-P', str(c['port']), '-u', c['user'],
            '--default-character-set=utf8mb4', '-D', c['db'], '-N', '-B', '-e', sql]
    if c['password']:
        args.append('-p' + c['password'])
    try:
        p = subprocess.run(args, capture_output=True, text=True,
                           encoding='utf-8', errors='replace', timeout=20)
    except Exception:
        return None
    if p.returncode != 0:
        return None
    lines = p.stdout.strip().splitlines()
    return lines[0].split('\t') if lines else None


def mysql_exec(sql):
    """执行一条不带返回值的写语句（清理测试数据用）。连不上返回 False。

    接口层没有「注销账号」能力，跑完测试留下的 smoke-*@test.local 只能这样清掉，
    否则每跑一次就往库里积攒一条垃圾账号。
    """
    exe = _find_mysql()
    if not exe:
        return False
    c = _db_conf()
    args = [exe, '-h', c['host'], '-P', str(c['port']), '-u', c['user'],
            '--default-character-set=utf8mb4', '-D', c['db'], '-e', sql]
    if c['password']:
        args.append('-p' + c['password'])
    try:
        p = subprocess.run(args, capture_output=True, text=True,
                           encoding='utf-8', errors='replace', timeout=20)
    except Exception:
        return False
    return p.returncode == 0


def main():
    print('== 目标 %s ==\n' % BASE)

    # ---------------------------------------------------------------- 健康检查
    print('-- 健康检查 / 配置')
    s, j, _, _ = req('GET', '/api/health')
    check('GET /api/health → 200 {"ok":true}', s == 200 and isinstance(j, dict) and j.get('ok') is True, (s, j))
    storage = j.get('storage') if isinstance(j, dict) else None
    check('health.storage 指向数据目录', bool(storage), j)

    # ---------------------------------------------------------------- 注册校验
    print('\n-- 注册 / 校验')
    stamp = str(int(time.time()))
    email = 'smoke-%s-%s@test.local' % (stamp, uuid.uuid4().hex[:6])
    pwd = 'Smoke#12345'

    s, j, _, _ = req('POST', '/api/auth/register', {'email': email, 'password': pwd})
    check('注册成功 → 200，返回 user + token',
          s == 200 and isinstance(j, dict) and j.get('token') and j.get('user', {}).get('email') == email, (s, j))
    token = j.get('token') if isinstance(j, dict) else None
    uid = j.get('user', {}).get('id') if isinstance(j, dict) else None

    s, j, _, _ = req('POST', '/api/auth/register', {'email': email, 'password': pwd})
    check('重复注册 → 409 already-exists', s == 409 and err_kind(j) == 'already-exists', (s, j))

    s, j, _, _ = req('POST', '/api/auth/register', {'email': 'not-an-email', 'password': pwd})
    check('邮箱格式非法 → 400 invalid-request', s == 400 and err_kind(j) == 'invalid-request', (s, j))

    s, j, _, _ = req('POST', '/api/auth/register',
                     {'email': 'x%s@t.local' % uuid.uuid4().hex[:8], 'password': '123'})
    check('密码短于 6 位 → 400 invalid-request', s == 400 and err_kind(j) == 'invalid-request', (s, j))

    # ---------------------------------------------------------------- 登录 / 会话
    print('\n-- 登录 / 会话')
    s, j, _, _ = req('POST', '/api/auth/login', {'email': email, 'password': pwd})
    check('登录成功 → 200 + token', s == 200 and isinstance(j, dict) and bool(j.get('token')), (s, j))
    if isinstance(j, dict) and j.get('token'):
        token = j['token']

    s, j, _, _ = req('POST', '/api/auth/login', {'email': email, 'password': 'definitely-wrong'})
    check('密码错误 → 401 unauthenticated', s == 401 and err_kind(j) == 'unauthenticated', (s, j))

    s, j, _, _ = req('GET', '/api/auth/me')
    check('未带 token 访问 /me → 401', s == 401 and err_kind(j) == 'unauthenticated', (s, j))

    s, j, _, _ = req('GET', '/api/auth/me', token=token)
    check('带 token 访问 /me → 200 且身份正确',
          s == 200 and isinstance(j, dict) and j.get('user', {}).get('email') == email, (s, j))

    # ---------------------------------------------------------------- 密码算法一致性
    print('\n-- 密码派生（独立复算）')
    row = mysql_query("SELECT pwd_salt, pwd_hash FROM users WHERE email = '%s'"
                      % email.replace("'", "''"))
    if not row or len(row) < 2:
        print('   [skip] 读不到 MySQL —— 配好 deploy/.env 里的 WORKHUB_DB_PASSWORD 后重跑即可启用')
    else:
        salt, stored = row[0], row[1]
        calc = hashlib.pbkdf2_hmac('sha256', pwd.encode('utf-8'),
                                   salt.encode('utf-8'), PBKDF2_ROUNDS).hex()
        check('库中 pwd_hash == PBKDF2-HMAC-SHA256(120000) 独立复算值',
              hmac.compare_digest(calc, stored), 'calc=%s stored=%s' % (calc[:16], stored[:16]))
        check('salt 为 32 位十六进制（token_hex(16) 等价）',
              len(salt) == 32 and all(c in '0123456789abcdef' for c in salt), salt)

    # ---------------------------------------------------------------- 双时钟
    print('\n-- X-Client-Time（业务时间用浏览器时钟）')
    client_time = '2031-12-31T08:30:00'
    s, j, _, _ = req('POST', '/api/todos',
                     {'title': 'smoke-clock', 'detail': 'd', 'priority': 'normal', 'due_date': None},
                     token=token, headers={'X-Client-Time': client_time})
    check('新建待办的 created_at 采用 X-Client-Time',
          s == 200 and isinstance(j, dict) and j.get('created_at') == client_time,
          (s, j.get('created_at') if isinstance(j, dict) else j))
    clock_todo_id = j.get('id') if isinstance(j, dict) else None

    s, j, _, _ = req('POST', '/api/todos',
                     {'title': 'smoke-badclock', 'priority': 'normal'},
                     token=token, headers={'X-Client-Time': 'yesterday'})
    ct = j.get('created_at') if isinstance(j, dict) else None
    check('非法 X-Client-Time 被忽略、回退服务器时间',
          s == 200 and bool(ct) and ct != 'yesterday' and bool(re.match(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$', ct or '')),
          (s, ct))
    bad_clock_todo_id = j.get('id') if isinstance(j, dict) else None

    # ---------------------------------------------------------------- 待办 CRUD
    print('\n-- 待办 todos')
    s, j, _, _ = req('GET', '/api/todos', token=token)
    check('GET /api/todos → 200 且为裸数组', s == 200 and isinstance(j, list), (s, type(j).__name__))

    # 刻意不传 status / priority：必须落到建表里的列默认值（pending / normal）。
    # 曾经的实现"缺字段就显式填空串"，把这两个默认值顶成了非法状态。
    s, j, _, _ = req('POST', '/api/todos', {'title': 'smoke-todo'}, token=token)
    check('POST /api/todos → 200 且回填 id', s == 200 and isinstance(j, dict) and j.get('id'), (s, j))
    todo_id = j.get('id') if isinstance(j, dict) else None
    check('未传 status 时走列默认值 pending',
          isinstance(j, dict) and j.get('status') == 'pending', j)
    check('未传 priority 时走列默认值 normal',
          isinstance(j, dict) and j.get('priority') == 'normal', j)

    s, j, _, _ = req('PUT', '/api/todos/%s' % todo_id, {'status': 'done', 'title': 'smoke-todo-2'}, token=token)
    check('PUT /api/todos/{id} 更新生效',
          s == 200 and isinstance(j, dict) and j.get('status') == 'done' and j.get('title') == 'smoke-todo-2', (s, j))

    s, j, _, _ = req('PUT', '/api/todos/99999999', {'title': 'x'}, token=token)
    check('PUT 不存在的 id → 404 not-found', s == 404 and err_kind(j) == 'not-found', (s, j))

    s, j, _, _ = req('DELETE', '/api/todos/%s' % todo_id, token=token)
    check('DELETE /api/todos/{id} → {ok:true}', s == 200 and isinstance(j, dict) and j.get('ok') is True, (s, j))

    s, j, _, _ = req('GET', '/api/todos', token=token)
    check('删除后列表里不再出现该条',
          isinstance(j, list) and all(r.get('id') != todo_id for r in j), j)

    # ---------------------------------------------------------------- 工作日志 CRUD
    print('\n-- 工作日志 logs')
    s, j, _, _ = req('POST', '/api/logs',
                     {'log_date': '2031-12-30', 'title': 'smoke-log', 'content': 'hello', 'tags': 'a,b'},
                     token=token)
    check('POST /api/logs → 200 且回填 id', s == 200 and isinstance(j, dict) and j.get('id'), (s, j))
    log_id = j.get('id') if isinstance(j, dict) else None

    s, j, _, _ = req('GET', '/api/logs', token=token)
    check('GET /api/logs → 200 且为裸数组', s == 200 and isinstance(j, list), (s, type(j).__name__))

    s, j, _, _ = req('GET', '/api/logs?keyword=smoke-log', token=token)
    check('关键词过滤命中 1 条', s == 200 and isinstance(j, list) and len(j) >= 1, (s, j))

    s, j, _, _ = req('PUT', '/api/logs/%s' % log_id, {'content': 'hello-2'}, token=token)
    check('PUT /api/logs/{id} 更新生效', s == 200 and isinstance(j, dict) and j.get('content') == 'hello-2', (s, j))

    s, j, _, _ = req('DELETE', '/api/logs/%s' % log_id, token=token)
    check('DELETE /api/logs/{id} → 200', s == 200, (s, j))

    # ---------------------------------------------------------------- 日报（upsert + 裸 null）
    print('\n-- 日报 reports')
    rdate = '2031-12-29'
    s, j, _, raw = req('GET', '/api/reports/one?date=%s' % rdate, token=token)
    check('当天无日报 → 200 且响应体是裸 null（前端据此判断新建/编辑）',
          s == 200 and raw.strip() == b'null', (s, raw[:80]))

    s, j, _, _ = req('POST', '/api/reports',
                     {'report_date': rdate, 'done_today': 'A', 'plan_tomorrow': 'B', 'issues': 'C'},
                     token=token)
    check('POST /api/reports 新建 → 200', s == 200 and isinstance(j, dict) and j.get('report_date') == rdate, (s, j))
    rep_id = j.get('id') if isinstance(j, dict) else None

    s, j, _, _ = req('POST', '/api/reports',
                     {'report_date': rdate, 'done_today': 'A2', 'plan_tomorrow': 'B2', 'issues': 'C2'},
                     token=token)
    check('同日期重复保存 = upsert（id 不变、内容更新）',
          s == 200 and isinstance(j, dict) and j.get('id') == rep_id and j.get('done_today') == 'A2', (s, j))

    s, j, _, _ = req('GET', '/api/reports/one?date=%s' % rdate, token=token)
    check('GET /api/reports/one 能取到刚保存的日报', s == 200 and isinstance(j, dict) and j.get('done_today') == 'A2', (s, j))

    s, j, _, _ = req('GET', '/api/reports', token=token)
    check('GET /api/reports → 200 且为裸数组', s == 200 and isinstance(j, list), (s, type(j).__name__))

    s, j, _, _ = req('GET', '/api/reports?from=2031-12-01&to=2031-12-31', token=token)
    check('GET /api/reports 支持 from/to 日期区间过滤',
          s == 200 and isinstance(j, list) and any(r.get('report_date') == rdate for r in j), (s, j))

    s, j, _, _ = req('GET', '/api/reports?from=2099-01-01&to=2099-12-31', token=token)
    check('区间外查不到数据', s == 200 and isinstance(j, list) and len(j) == 0, (s, j))

    s, j, _, _ = req('POST', '/api/reports', {'report_date': 'not-a-date'}, token=token)
    check('日报日期格式非法 → 400 invalid-request', s == 400 and err_kind(j) == 'invalid-request', (s, j))

    s, j, _, _ = req('DELETE', '/api/reports/%s' % rep_id, token=token)
    check('DELETE /api/reports/{id} → 200', s == 200, (s, j))

    # ---------------------------------------------------------------- 资料 CRUD
    print('\n-- 资料 documents')
    s, j, _, _ = req('POST', '/api/documents',
                     {'title': 'smoke-doc', 'category': 'manual', 'note': 'n'}, token=token)
    check('POST /api/documents → 200 且回填 id', s == 200 and isinstance(j, dict) and j.get('id'), (s, j))
    doc_id = j.get('id') if isinstance(j, dict) else None

    s, j, _, _ = req('POST', '/api/documents', {'note': 'no title'}, token=token)
    check('资料缺标题 → 400 invalid-request', s == 400 and err_kind(j) == 'invalid-request', (s, j))

    s, j, _, _ = req('GET', '/api/documents?keyword=smoke-doc', token=token)
    check('GET /api/documents → 200 且为裸数组', s == 200 and isinstance(j, list), (s, type(j).__name__))

    s, j, _, _ = req('POST', '/api/documents/%s/download' % doc_id, token=token)
    check('POST /api/documents/{id}/download 下载计数 +1',
          s == 200 and isinstance(j, dict) and j.get('download_count') == 1, (s, j))

    s, j, _, _ = req('PUT', '/api/documents/%s' % doc_id, {'title': 'smoke-doc-2'}, token=token)
    check('PUT /api/documents/{id} 更新生效', s == 200 and j.get('title') == 'smoke-doc-2', (s, j))

    # ---------------------------------------------------------------- 软件仓库
    print('\n-- 软件仓库 software')
    s, j, _, _ = req('POST', '/api/software',
                     {'name': 'smoke-sw', 'version': '1.0', 'platform': 'windows', 'category': 'tool'},
                     token=token)
    check('POST /api/software → 200 且回填 id', s == 200 and isinstance(j, dict) and j.get('id'), (s, j))
    sw_id = j.get('id') if isinstance(j, dict) else None

    s, j, _, _ = req('POST', '/api/software', {'version': '1.0'}, token=token)
    check('软件缺名称 → 400 invalid-request', s == 400 and err_kind(j) == 'invalid-request', (s, j))

    s, j, _, _ = req('GET', '/api/software', token=token)
    check('GET /api/software → 200 且为裸数组', s == 200 and isinstance(j, list), (s, type(j).__name__))

    s, j, _, _ = req('PUT', '/api/software/%s' % sw_id, {'description': 'desc-2'}, token=token)
    check('PUT /api/software/{id} 更新生效', s == 200 and j.get('description') == 'desc-2', (s, j))

    s, j, _, _ = req('POST', '/api/software/%s/download' % sw_id, token=token)
    check('POST /api/software/{id}/download 下载计数 +1',
          s == 200 and isinstance(j, dict) and j.get('download_count') == 1, (s, j))

    s, j, _, _ = req('DELETE', '/api/software/%s' % sw_id, token=token)
    check('DELETE /api/software/{id} → 200', s == 200, (s, j))

    # ---------------------------------------------------------------- 文件上传 / 下载 / 分享
    print('\n-- 文件 上传/下载/分享/删除')
    payload = bytes((i * 37 + 11) % 251 for i in range(1_500_000))   # ~1.5MB，检验分块流式
    fname = 'smoke-%s.bin' % uuid.uuid4().hex[:8]

    s, j, _, _ = req('POST', '/api/upload?folder=misc&name=%s' % urllib.parse.quote(fname),
                     raw=payload, token=token)
    check('POST /api/upload 裸 body 上传 → 200 且 size 一致',
          s == 200 and isinstance(j, dict) and j.get('size') == len(payload), (s, j))
    rel = j.get('path') if isinstance(j, dict) else None
    check('上传返回 path 位于 misc/ 下', bool(rel) and rel.startswith('misc/'), rel)

    s, j, _, body = req('GET', '/api/download?path=%s' % urllib.parse.quote(rel, safe=''), token=token)
    check('GET /api/download（带 token）→ 200 且字节完全一致',
          s == 200 and isinstance(body, bytes) and body == payload, (s, len(body) if isinstance(body, bytes) else -1))

    s, j, _, body = req('GET', '/api/download?t=%s&path=%s'
                        % (urllib.parse.quote(token, safe=''), urllib.parse.quote(rel, safe='')))
    check('GET /api/download?t=<token> 查询参数也能鉴权（前端下载链接走这个）',
          s == 200 and isinstance(body, bytes) and body == payload, (s, len(body) if isinstance(body, bytes) else -1))

    s, j, _, _ = req('GET', '/api/download?path=%s' % urllib.parse.quote(rel, safe=''))
    check('GET /api/download 无凭证 → 401 unauthenticated', s == 401 and err_kind(j) == 'unauthenticated', (s, j))

    s, j, _, _ = req('GET', '/api/share?path=%s&ttl=600' % urllib.parse.quote(rel, safe=''), token=token)
    check('GET /api/share → 200 且返回签名 url',
          s == 200 and isinstance(j, dict) and str(j.get('url', '')).startswith('/api/download?'), (s, j))
    share_url = j.get('url') if isinstance(j, dict) else None

    s, j, _, body = req('GET', share_url)
    check('用分享链接（不带 token）下载 → 200 且字节一致',
          s == 200 and isinstance(body, bytes) and body == payload, (s, len(body) if isinstance(body, bytes) else -1))

    tampered = share_url.replace('&s=', '&s=x') if share_url else ''
    s, j, _, _ = req('GET', tampered)
    check('分享签名被篡改 → 403 permission-denied', s == 403 and err_kind(j) == 'permission-denied', (s, j))

    s, j, _, _ = req('GET', '/api/download?u=%s&e=1&s=deadbeef&path=%s'
                     % (uid, urllib.parse.quote(rel, safe='')))
    check('分享链接已过期 → 410 expired', s == 410 and err_kind(j) == 'expired', (s, j))

    s, j, _, _ = req('GET', '/api/share?path=misc/__not_exist__.bin', token=token)
    check('分享不存在的文件 → 404 not-found', s == 404 and err_kind(j) == 'not-found', (s, j))

    s, j, _, _ = req('GET', '/api/download?path=misc/__not_exist__.bin', token=token)
    check('下载不存在的文件 → 404 not-found', s == 404 and err_kind(j) == 'not-found', (s, j))

    # 路径穿越防护
    s, j, _, _ = req('POST', '/api/upload?folder=%s&name=x.bin' % urllib.parse.quote('../../etc'),
                     raw=b'x', token=token)
    if s == 200 and isinstance(j, dict):
        escaped = not j.get('path', '').startswith('misc/')
        check('folder 传 ../.. 被净化（未逃出 misc/）', not escaped, j)
        req('DELETE', '/api/files?path=%s' % urllib.parse.quote(j['path'], safe=''), token=token)
    else:
        check('folder 传 ../.. 被拒绝或净化', s in (400, 403), (s, j))

    s, j, _, _ = req('DELETE', '/api/files?path=%s' % urllib.parse.quote(rel, safe=''), token=token)
    check('DELETE /api/files → removed=1', s == 200 and isinstance(j, dict) and j.get('removed') == 1, (s, j))

    s, j, _, _ = req('GET', '/api/download?path=%s' % urllib.parse.quote(rel, safe=''), token=token)
    check('删除后再下载 → 404 not-found', s == 404 and err_kind(j) == 'not-found', (s, j))

    s2, j2, _, _ = req('POST', '/api/upload?folder=misc&name=smoke2-%s.bin' % uuid.uuid4().hex[:6],
                       raw=b'abc', token=token)
    rel2 = j2.get('path') if isinstance(j2, dict) else None
    s3, j3, _, _ = req('POST', '/api/upload?folder=misc&name=smoke3-%s.bin' % uuid.uuid4().hex[:6],
                       raw=b'abcd', token=token)
    rel3 = j3.get('path') if isinstance(j3, dict) else None
    s, j, _, _ = req('DELETE', '/api/files?path=%s&path=%s'
                     % (urllib.parse.quote(rel2 or '', safe=''), urllib.parse.quote(rel3 or '', safe='')),
                     token=token)
    check('DELETE /api/files 支持重复 path 参数（一次删多个）',
          s == 200 and isinstance(j, dict) and j.get('removed') == 2, (s, j, rel2, rel3))

    # ---------------------------------------------------------------- 改密
    print('\n-- 修改密码')
    s, j, _, _ = req('POST', '/api/auth/password', {'old': 'wrong-one', 'new': 'NewPass#98765'}, token=token)
    check('原密码错误 → 400 invalid-request', s == 400 and err_kind(j) == 'invalid-request', (s, j))

    s, j, _, _ = req('POST', '/api/auth/password', {'old': pwd, 'new': '123'}, token=token)
    check('新密码短于 6 位 → 400 invalid-request', s == 400 and err_kind(j) == 'invalid-request', (s, j))

    s, j, _, _ = req('POST', '/api/auth/password',
                     {'old': pwd, 'new': 'NewPass#98765', 'oldPassword': pwd, 'newPassword': 'NewPass#98765'},
                     token=token)
    check('改密成功 → 200', s == 200, (s, j))

    s, j, _, _ = req('POST', '/api/auth/login', {'email': email, 'password': 'NewPass#98765'})
    check('用新密码可以登录', s == 200 and isinstance(j, dict) and j.get('token'), (s, j))
    if isinstance(j, dict) and j.get('token'):
        token = j['token']

    s, j, _, _ = req('POST', '/api/auth/login', {'email': email, 'password': pwd})
    check('旧密码已失效', s == 401, (s, j))

    # ---------------------------------------------------------------- 错误信封 / 登出
    print('\n-- 错误信封 / 登出 / 清理')
    s, j, _, _ = req('GET', '/api/definitely-not-here')
    check('未知接口 → 404 且带标准错误信封',
          s == 404 and isinstance(j, dict) and isinstance(j.get('error'), dict)
          and 'kind' in j['error'] and 'message' in j['error'], (s, j))

    s, j, _, _ = req('POST', '/api/auth/logout', {}, token=token)
    check('POST /api/auth/logout → 200', s == 200, (s, j))

    s, j, _, _ = req('GET', '/api/auth/me', token=token)
    check('登出后原 token 失效 → 401', s == 401, (s, j))

    # 清理临时记录（换个新 token 再删）
    s, j, _, _ = req('POST', '/api/auth/login', {'email': email, 'password': 'NewPass#98765'})
    tok2 = j.get('token') if isinstance(j, dict) else None
    if tok2:
        for path, rid in (('/api/documents', doc_id),
                          ('/api/todos', clock_todo_id),
                          ('/api/todos', bad_clock_todo_id)):
            if rid:
                req('DELETE', '%s/%s' % (path, rid), token=tok2)
        req('POST', '/api/auth/logout', {}, token=tok2)
    # 账号也一并清掉（接口没有「注销」能力，只能直接删库）
    removed = bool(uid) and mysql_exec(
        "DELETE FROM sessions WHERE user_id = '%s'; DELETE FROM users WHERE id = '%s';"
        % (uid, uid))
    check('测试数据已清理（资料/待办已删，%s）'
          % ('账号也已从库中删除' if removed else '账号删除需能连 MySQL，本次跳过'), True)

    # ---------------------------------------------------------------- 汇总
    print('\n' + '=' * 56)
    print('通过 %d 项，失败 %d 项' % (_passed, _failed))
    if _failed:
        print('存在失败项，请逐条核对。')
    print('=' * 56)
    return 1 if _failed else 0


if __name__ == '__main__':
    sys.exit(main())
