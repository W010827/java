# WorkHub · 个人工作管理平台

一个**完全自建、数据不出内网**的个人工作管理系统：上传常用软件供自己下载、写工作日报、随手记工作过程、管理任务待办、归档文档资料，并在看板上汇总。

- 后端是 **Java / SpringBoot**（`server-java/`，JDK 21 字节码），打包成一个 jar，服务器上只要有 JDK 21 就能跑
- 数据库是 **MySQL**（InnoDB，库名 `workhub`），表结构由后端启动时自动创建
- 前端是 Vite + Vue 3 单页应用，构建后就是一堆静态文件

> 设计取向：**先能长期稳定跑起来，再谈功能**。前端构建产物与后端 jar 都可以离线分发，运行期不依赖任何外部服务。

---

## 目录

- [一、功能模块](#一功能模块)
- [二、技术架构](#二技术架构)
- [三、运行环境](#三运行环境)
- [四、目录结构](#四目录结构)
- [五、本地开发](#五本地开发)
- [六、部署上线](#六部署上线)
- [七、环境变量](#七环境变量)
- [八、数据模型](#八数据模型)
- [九、接口一览](#九接口一览)
- [十、备份与恢复](#十备份与恢复)
- [十一、安全设计](#十一安全设计)
- [十二、设计权衡与已知问题](#十二设计权衡与已知问题)
- [十三、常见问题](#十三常见问题)

---

## 快速开始（本地跑起来）

```bash
npm install                                   # 首次执行一次
mysql -uroot -p < deploy/mysql-schema.sql     # 建库建表（不执行也行，后端启动时会自动建）
echo 'WORKHUB_DB_PASSWORD=你的密码' >> deploy/.env
bash deploy/dev-java.sh                       # 同时起后端 8098 + 前端 5180，Ctrl+C 一起退出
```

浏览器打开 **http://localhost:5180**，自己注册一个账号就能用。

> 详细说明见 [五、本地开发](#五本地开发)；部署到服务器见 [六、部署上线](#六部署上线)。

---

## 一、功能模块

| 模块 | 路由 | 主要能力 |
|---|---|---|
| 数据看板 | `/dashboard` | 软件数、日报连续天数、待办完成率、资料总量；ECharts 图表 |
| 软件仓库 | `/software` | 上传软件安装包（支持超大文件、**后台上传**）、按名称/分类检索、下载计数、生成临时下载链接 |
| 工作日报 | `/reports` | 按日期一页式填写「今日完成 / 明日计划 / 存在问题」，同一日期唯一（改就是更新） |
| 工作记录 | `/logs` | 按天记录过程与结论，支持标签与关键词搜索 |
| 任务待办 | `/todos` | 标题、详情、优先级、截止日期、完成状态切换 |
| 文件资料库 | `/documents` | 文档归档（**后台上传**），带分类、标签、备注，下载计数 |

**设计上刻意保持简单**：一个菜单对应一个 `.vue` 文件，加一个模块就是加一个文件 + 一条路由 + 一张表，不需要动框架层。

### 上传是「后台」的

上传不绑定在弹窗或页面上，而是走一个全局队列（`src/uploads.js`）：

- 点「上传」后**弹窗立即关闭**，上传在后台继续，用户可以立刻切菜单、点别的地方
- 右下角有一个**上传面板**常驻显示进度、速度、预计剩余时间，可收起
- 队列与页面解耦：切走再切回来照常，记录写好后列表自动刷新
- 支持**取消**和**重试**（File 还在内存里，不用重新选文件）；有任务在传时关闭页签会提示
- 上传成功后才写业务表，写表失败会自动删掉刚上传的文件，不留孤儿文件

---

## 二、技术架构

### 整体链路

```
                         浏览器（内网任意一台电脑）
                                   │
                                   │  HTTP :8099
                                   ▼
                    ┌──────────────────────────────┐
                    │   Nginx  :8099               │
                    │   ├─ /           静态前端      │  ← /var/www/workhub
                    │   ├─ /assets/    长缓存       │
                    │   └─ /api/       反代         │
                    └──────────────┬───────────────┘
                                   │  http://127.0.0.1:8098
                                   ▼
                    ┌──────────────────────────────┐
                    │  SpringBoot（systemd: workhub）│
                    │  内嵌 Tomcat 10               │
                    │  ├─ MySQL (InnoDB)            │  ← 127.0.0.1:3306/workhub
                    │  └─ 本地磁盘文件存储            │  ← /var/lib/workhub/storage/
                    └──────────────────────────────┘
```

- 前端与后端**同源**：页面和接口都由同一台 Nginx 提供，所以没有跨域、不需要 CORS、不需要配域名白名单。
- 后端只监听 `127.0.0.1`，**从外部无法直连**，必须经 Nginx。
- 整条链路**没有一次外部网络请求**。

### 技术选型

| 层 | 选型 | 版本 | 为什么 |
|---|---|---|---|
| 前端框架 | Vue 3 | `^3.5.13` | 组合式 API，单文件组件结构清晰 |
| 构建工具 | Vite | `^5.4.11` | 构建产物小（`dist` 约 2.0 MB）、配置极简 |
| UI 组件 | Element Plus | `^2.8.8` | 表格 / 表单 / 弹窗 / 上传全套齐 |
| 图表 | ECharts | `^5.5.1` | 看板可视化 |
| 路由 | vue-router | `^4.4.5` | **hash 模式**（见下方说明） |
| 后端框架 | SpringBoot | `3.5.3` | 内嵌 Tomcat，打包成单个 jar；JDK 21 目标字节码 |
| 数据访问 | JdbcTemplate + HikariCP | Spring 自带 | 手写 SQL，接口是自由 JSON，用 ORM 反而绕 |
| 数据库 | MySQL | 5.7 / 8.0 | InnoDB 事务；JDBC 驱动钉在 8.0.33（9.x 已不保证兼容 5.7） |
| 文件存储 | 本地磁盘 | — | 按用户 + 业务分目录 |
| 密码哈希 | 自实现 PBKDF2-HMAC-SHA256 | — | 12 万轮、16 字节随机盐，按 RFC 2898 手写以避开各 JDK 的 `char[]` 编码差异 |
| Web 服务器 | Nginx | 系统自带 | 静态文件 + 反向代理 |
| 进程管理 | systemd | 系统自带 | 崩溃自动重启、开机自启、日志归集 |

**没有引入的东西（刻意）**：Node 运行时（服务器上不需要，前端在本地构建好再传 dist）、Docker、Redis、对象存储、任何云服务 SDK。

### 前端路由为什么用 hash 模式

用 `createWebHashHistory()`，地址形如 `http://host:8099/#/software`。这样**静态托管下刷新任意页面都不会 404**，Nginx 侧不用配任何 `try_files` 回退规则也能正常工作（配置里仍保留了回退，双保险）。

### 认证流程

```
POST /api/auth/login  {email, password}
        │
        ├─ 查 users 表，用 pwd_salt + pwd_hash 校验 PBKDF2
        ├─ 生成 token（secrets.token_hex）写入 sessions 表，30 天有效
        └─ 返回 {token, user}
                │
浏览器把 token 存 localStorage（key: workhub.token）
                │
后续请求带 Authorization: Bearer <token>
        │
后端按 token → user_id → 所有查询加 WHERE owner_id = ? 过滤
```

**数据隔离靠 `owner_id`**：每个业务表的每条记录都带 `owner_id`，所有读/改/删都拼在同一个 SQL 条件里（`WHERE id = ? AND owner_id = ?`），不存在"查得到但不该看到"的路径。

---

## 三、运行环境

### 服务器端（生产）

| 项 | 要求 | 本次实机 |
|---|---|---|
| 操作系统 | 任意主流 Linux | **银河麒麟 V10**（Kylin） |
| JDK | **21 及以上**（后端跑的是 JDK 21 字节码） | 21（`/usr/local/jdk-21`） |
| MySQL | **5.7 及以上** | 5.7.x / 8.0 均可 |
| Nginx | 1.x | 系统自带 |
| systemd | 任意 | 系统自带 |
| 磁盘 | 视上传文件量 | 数据目录 `/var/lib/workhub` |
| 网络 | **不需要外网** | 纯内网即可运行 |

> ⚠️ 后端打成 jar 后**只依赖 JDK**，不需要 Maven、不需要 Node、不需要联网拉包。jar 在本地构建好再传上去即可。
>
> 💡 数据库可以是本机的 MySQL，也可以指向已有的 MySQL 实例 —— 用 `WORKHUB_DB_URL` 换地址就行。

### 开发端（本机）

| 项 | 要求 |
|---|---|
| Node.js | **18 或以上**（构建前端用） |
| JDK | **21 或以上**（编译后端用；更高的版本如 25 也能编译，产物仍是 21 字节码） |
| Maven | 3.8+（打包后端 jar） |
| 包管理器 | npm（随 Node 附带） |
| 可选 | Git Bash（Windows 下执行脚本用） |

### 端口

本应用**只需要两个端口**，两个都可以按需修改：

| 端口 | 用途 | 监听地址 | 想改哪里 |
|---|---|---|---|
| `8098` | 应用后端 | 仅 `127.0.0.1`，外部无法直连 | 环境变量 `WORKHUB_PORT`（记得同步改 Nginx 的 `proxy_pass`） |
| `8099` | 对外入口（Nginx） | `0.0.0.0` | `deploy/nginx-workhub.conf` 里的 `listen` |

> ⚠️ **部署到一台已经跑着其他服务的机器上前，先确认这两个端口是空的**：
>
> ```bash
> ss -lnt | grep -E ':(8098|8099)'      # 有输出说明已被占用
> ```
>
> 若已被占用，改上面两处配置换个端口即可 —— **不要去动机器上其他服务的端口**。同理，本应用的 Nginx 配置是独立的 `conf.d` 文件，与既有站点互不干扰。

---

## 四、目录结构

```
workhub/
├── index.html                  入口 HTML
├── package.json                前端依赖与脚本
├── vite.config.js              Vite 配置（base: '/'，端口 5180，/api 代理到后端）
│
├── src/                        前端源码（约 4900 行）
│   ├── main.js                 应用入口
│   ├── App.vue                 根组件
│   ├── router.js               路由表 + 登录拦截
│   ├── cloud.js                HTTP 底座：token、错误分类、上传进度、大文件流式上传
│   ├── uploads.js              ★ 全局后台上传队列（脱离弹窗/页面，支持取消与重试）
│   ├── api.js                  业务数据访问层（softwareApi / reportApi / logApi / todoApi / docApi / storageApi）
│   ├── utils.js                日期与体积格式化、速度与剩余时长、错误文案
│   ├── styles.css              全局样式
│   ├── components/
│   │   ├── AppLayout.vue       侧边菜单 + 顶栏 + 退出登录 + 挂载上传面板
│   │   ├── UploadTray.vue      右下角后台上传进度面板
│   │   ├── FilePicker.vue      文件选择
│   │   └── Icon.vue            内联 SVG 图标集
│   └── views/                  ★ 一个菜单 = 一个文件
│       ├── LoginView.vue
│       ├── DashboardView.vue
│       ├── SoftwareView.vue
│       ├── ReportsView.vue
│       ├── LogsView.vue
│       ├── TodosView.vue
│       └── DocumentsView.vue
│
├── server-java/                后端（SpringBoot + MySQL）
│   ├── pom.xml                 SpringBoot 3.5.3 + mysql-connector-j 8.0.33，编译目标 JDK 21
│   ├── src/main/resources/application.yml   数据源、上传体积、日志
│   └── src/main/java/com/workhub/
│       ├── WorkhubApplication.java   入口（含端口防覆盖处理）
│       ├── core/               配置、密码、数据库建表、存储、时间
│       ├── service/            登录注册的领域逻辑
│       ├── web/                拦截器、错误信封、静态资源
│       └── api/                34 个接口
│
└── deploy/
    ├── dev-java.sh             ★ 本地开发一键启动（前端 + SpringBoot）
    ├── mysql-schema.sql        建库建表脚本（也可粘进 DBX 执行）
    ├── smoke_test.py           接口冒烟测试（71 项断言，覆盖全部 34 个接口）
    ├── deploy.sh               一键部署脚本（frontend / backend / all / status）
    ├── db-local.sh             本地 MySQL 查库工具（概览 / 表结构 / sql / 库列表）
    ├── workhub.service         systemd 单元（服务器上跑 jar）
    └── nginx-workhub.conf      Nginx 站点配置
```

> **`.env`（不在上面的清单里，因为它是隐藏文件）**：本地配置，放数据库密码与部署目标服务器。
> 已被 `.gitignore` 排除，**不要提交**。

### 想改代码改哪里

| 想改什么 | 改哪个文件 |
|---|---|
| **本地跑起来看效果** | `bash deploy/dev-java.sh`（见「五、本地开发」） |
| 页面长相、交互 | `src/views/<模块>View.vue` |
| 左侧菜单、标题 | `src/components/AppLayout.vue`、`src/router.js` 的 `meta.title` |
| 新增一个模块 | 加 `src/views/XxxView.vue` + `router.js` 一条路由 + `api.js` 一组方法 + 后端一张表 |
| 接口地址、请求头 | `src/api.js`（业务语义）、`src/cloud.js`（HTTP 细节） |
| 本地开发的接口地址 | `vite.config.js` 里的 `apiTarget`（或用环境变量 `WORKHUB_API` 覆盖） |
| 上传队列 / 进度面板 | `src/uploads.js`（队列与状态机）、`src/components/UploadTray.vue`（面板外观） |
| 后端接口逻辑 | `server-java/src/main/java/com/workhub/api/*.java` |
| 后端路由 | 各 `XxxApi.java` 上的 `@RequestMapping` / `@GetMapping` 等注解 |
| 数据库表结构 | `server-java/.../core/Db.java` 里的 `SCHEMA`（改完删库重启即可重建；同步改 `deploy/mysql-schema.sql`） |
| 数据库连接 | `server-java/src/main/resources/application.yml` 的 `spring.datasource`，或环境变量 |
| 查看数据库 | DBX 里的「本机」连接，或 `bash deploy/db-local.sh` |
| 端口、反代、上传体积 | `deploy/nginx-workhub.conf` |
| 密码算法 / 分享签名 | `server-java/.../core/Crypto.java`、`core/Storage.java` |

> ⚠️ **服务器上的 jar 与 `/var/www/workhub/` 是运行副本，不要在服务器上直接改**。所有修改都在本地 `D:\workhub` 完成，再通过部署脚本推上去，否则下次部署会覆盖掉。

---

## 五、本地开发

本地环境与线上**完全隔离**：业务数据落在本地 MySQL 的 `workhub` 库，上传的文件落在 `.devdata/`，随便改、随便删，碰不到服务器上的真实数据。

### 前置：准备本地 MySQL

后端连的是 MySQL，库名 `workhub`。如果本机还没建库，先做这三步：

```bash
# 1) 建库建表（不执行也行，后端启动时会自动建表）
mysql -uroot -p < deploy/mysql-schema.sql

# 2) 把密码写进 deploy/.env（该文件已被 .gitignore 排除，不会提交）
echo 'WORKHUB_DB_PASSWORD=你的密码' >> deploy/.env

# 3) 确认连得上
bash deploy/db-local.sh
```

换库名、换端口、或连别的机器上的 MySQL，在 `deploy/.env` 里加一行即可：

```
WORKHUB_DB_URL=jdbc:mysql://192.168.1.201:3306/workhub
WORKHUB_DB_USER=root
WORKHUB_DB_PASSWORD=xxxxx
```

> ⚠️ **`.env` 是被 shell `source` 加载的，行首有 `#` 就等于没配。** 从注释模板里改的时候很容易只改值、忘了删 `#`，结果密码读成空串，启动时报
> `Access denied for user 'root'@'localhost' (using password: NO)` —— 注意那个 `NO`，它说明**根本没读到密码**，不是密码错。
>
> 改完用 `bash deploy/db-local.sh` 验证，它会明确打印「密码 已从 deploy/.env 读取」。

### 一键启动（推荐）

```bash
npm install              # 首次执行一次
bash deploy/dev-java.sh  # 同时起后端(8098) + 前端(5180)，Ctrl+C 一起退出
```

然后浏览器打开 **http://localhost:5180**。

改 `src/` 下的任何文件都会**即时生效，不用手动刷新**。默认账号自己注册一个就行。

也可以只起其中一个：

```bash
bash deploy/dev-java.sh api     # 只起后端 http://127.0.0.1:8098
bash deploy/dev-java.sh web     # 只起前端 http://localhost:5180
bash deploy/dev-java.sh build   # 只重新编译打包 jar
bash deploy/dev-java.sh stop    # 端口没退干净时强制结束
```

需要 **JDK 21 或更高**（本机是 JDK 25）。脚本会自动找可用的 `java`；找不到时手动指定：

```bash
WORKHUB_JAVA='/d/Program Files/java25/bin/java.exe' bash deploy/dev-java.sh api
```

> ⚠️ **Git Bash 里直接敲 `java` 会段错误（退出码 139）**。PATH 上那个 Oracle `javapath` 垫片在 MSYS 下跑不起来，必须用真实的 `java.exe` 完整路径 —— 脚本里已经帮你绕过了。

> ⚠️ **改完后端代码要先 `stop` 再 `build`**。Windows 会锁住正在运行的 jar，直接用 `mvn package` 会失败，并留下一个几十 KB 的假包。

### 在 IDEA 里跑

`server-java/` 是标准 Maven 工程，`File → Open` 选这个目录即可。

**直接点 Run 就能起来，不需要在运行配置里填任何环境变量。** 后端启动时会自动把仓库根的
`deploy/.env` 当作配置文件读进来（`application.yml` 里的 `spring.config.import`），
MySQL 密码从那里取。

只需要确认运行配置里的工作目录：

| 项 | 值 |
|---|---|
| Working directory | `D:\workhub`（留默认的 `server-java` 会让 `.devdata` 建到子目录里） |

> 该项已写进 `.idea/workspace.xml`（值为 `$PROJECT_DIR$/..`）。若 IDEA 弹出
> 「配置文件被外部修改」，选 **Reload**；没弹就 `File → Reload All from Disk`。

**为什么以前会报 `Access denied for user 'root'@'localhost' (using password: NO)`**

IDEA 直接 Run 不会执行 `dev-java.sh` 里的 `source deploy/.env`，于是
`${WORKHUB_DB_PASSWORD:}` 解析成空串，等于用空密码连 MySQL。现在由
`spring.config.import` 兜住：`./deploy/.env` 和 `../deploy/.env` 两条相对路径都试，
工作目录是项目根还是 `server-java` 都能命中。注意环境变量优先级仍高于配置文件，
所以脚本启动和服务器上 systemd 的注入方式都不受影响。

### 接口冒烟测试

改完代码可以自己复跑，71 项断言覆盖全部 34 个接口：

```bash
python deploy/smoke_test.py http://127.0.0.1:8098
```

### 想用服务器上的真实数据调样式

只起前端，把接口指向线上：

```bash
WORKHUB_API=http://<服务器地址>:8099 bash deploy/dev-java.sh web
```

页面在本地跑、数据来自服务器，登录用你平时的账号。改动只发生在浏览器侧，不会写回服务器。

### 代理是怎么配的

`vite.config.js` 里已经把 `/api` 转发到后端，默认目标是 `http://127.0.0.1:8098`，可用环境变量 `WORKHUB_API` 覆盖。

前端所有请求（含上传、下载、生成分享链接）都是 `/api/` 相对路径，所以代理只需要这一条规则。

### 手工启动（不想用脚本）

```bash
# 后端（先确认 deploy/.env 里配了数据库密码）
"/d/Program Files/java25/bin/java.exe" -jar server-java/target/workhub-java.jar \
  --server.port=8098 \
  --workhub.base-dir=D:/workhub/.devdata \
  --workhub.static-dir=D:/workhub/dist

# 前端
npm run dev
```

### 本地数据在哪

| 内容 | 路径 |
|---|---|
| 业务数据（账号、待办、日报…） | 本地 MySQL 的 `workhub` 库 |
| 上传的文件 | `.devdata/storage/` |
| 分享签名密钥 | `.devdata/secret.key` |

后两项在 `.gitignore` 中排除。想彻底清空重来：先 `DROP DATABASE workhub;`，再删掉 `.devdata/` 目录。

### 构建

```bash
npm run build      # 构建产物输出到 dist/
npm run preview    # 本地预览构建产物
```


---

## 六、部署上线

### 方式一：一键脚本（推荐）

先指定目标服务器（只需一次）：

```bash
export WORKHUB_SERVER=root@192.168.1.10                              # 当前终端有效
# 或写进 deploy/.env 长期生效（该文件已被 .gitignore 排除，不会提交）
printf 'WORKHUB_SERVER=root@192.168.1.10\n' > deploy/.env
```

然后在 **Git Bash** 中执行，脚本会自动构建、上传、重启、验证：

```bash
bash deploy/deploy.sh status      # 只看当前运行状态，不做任何改动
bash deploy/deploy.sh frontend    # 只更新前端（改了 src/ 或 index.html）
bash deploy/deploy.sh backend     # 只更新后端（改了 server-java/，会先在本地 Maven 打包）
bash deploy/deploy.sh all         # 前后端一起更新
```

脚本做的事：

- **前端**：`node` 执行 `vite build` → 打到服务器 `/var/www/workhub.new` → 原子切换（旧目录改名保留为 `workhub.bak-<时间戳>`）→ 只保留最近一次备份
- **后端**：本地 `mvn package` → jar 传到 `/opt/workhub/workhub-java.jar.new` 再原子改名（避免边传边启动读到半截文件）→ 同步 systemd 单元 → `daemon-reload` → `restart`
- **两者结束后统一验证**：首页 HTTP 状态、`/api/health`、未登录访问业务接口应返回 401

前置条件：本机已配置**免密 SSH** 到目标服务器；`PATH` 中有 `node`（构建前端）和 `mvn`（打包后端）。这两个命令也可用环境变量 `WORKHUB_NODE` / `WORKHUB_MVN` 覆盖。

### 方式二：手工部署

```bash
# 前端
npm run build
tar czf - -C dist . | ssh root@<服务器IP> \
  'rm -rf /var/www/workhub/*; tar xzf - -C /var/www/workhub; chown -R root:nginx /var/www/workhub'

# 后端（本地先打包）
cd server-java && mvn -q -DskipTests package && cd ..
scp server-java/target/workhub-java.jar root@<服务器IP>:/opt/workhub/
ssh root@<服务器IP> 'systemctl restart workhub'
```

### 首次部署需要准备

```bash
# 服务器上创建数据目录（后端会自动建表；MySQL 库需先存在或让后端自动建）
mkdir -p /var/lib/workhub /var/www/workhub /opt/workhub

# 数据库密码（不要写进仓库里的任何文件）
printf 'WORKHUB_DB_PASSWORD=你的密码\n' > /etc/workhub.env
chmod 600 /etc/workhub.env

# 建库（可选，后端启动时也会自动建）
mysql -uroot -p < deploy/mysql-schema.sql

# 放置 systemd 单元并启用
cp deploy/workhub.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now workhub

# 放置 Nginx 配置
cp deploy/nginx-workhub.conf /etc/nginx/conf.d/workhub.conf
nginx -t && systemctl reload nginx
```

> ⚠️ `deploy/workhub.service` 里 `ExecStart` 写的是 `/usr/local/jdk-21/bin/java`。服务器上 JDK 装在别的位置时改这一行。

### 常用运维命令

```bash
systemctl status workhub          # 查看状态
systemctl restart workhub         # 重启
journalctl -u workhub -n 50       # 看日志
tail -f /var/log/workhub.log      # 实时日志
nginx -t && systemctl reload nginx
```

数据库相关的几条（在本地 Git Bash 里执行，详见第八节的「数据库在哪、怎么打开」）：

```bash
bash deploy/db-local.sh                                  # 本地库概况：连接信息 + 各表行数
bash deploy/db-local.sh sql "select count(*) from software_packages"

# 线上库：登录服务器做一份逻辑备份（推荐，见第十节）
mysqldump -uroot -p --single-transaction --routines workhub > /root/workhub-$(date +%F).sql
```

---

## 七、环境变量

后端全部配置项均可通过环境变量覆盖（Java 侧的默认值在 `server-java/src/main/resources/application.yml`）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `WORKHUB_BASE` | `/var/lib/workhub` | 数据根目录（上传文件、签名密钥） |
| `WORKHUB_STATIC` | `/var/www/workhub` | 前端静态文件目录 |
| `WORKHUB_HOST` | `127.0.0.1` | 监听地址（**不要改成 0.0.0.0**，对外由 Nginx 承担） |
| `WORKHUB_PORT` | `8098` | 监听端口 |
| `WORKHUB_DB_URL` | `jdbc:mysql://127.0.0.1:3306/workhub?...` | 数据库连接串（整串覆盖，可换主机/端口/库名） |
| `WORKHUB_DB_USER` | `root` | 数据库账号 |
| `WORKHUB_DB_PASSWORD` | 空 | 数据库密码。**建议写在 `deploy/.env`**（已 gitignore），不要进仓库 |

代码内常量（需改代码，不常用。位置：`server-java/.../core/AppProps.java`）：

| 常量 | 值 | 说明 |
|---|---|---|
| `CHUNK` | 256 KB | 流式读写分块大小 |
| `TOKEN_TTL_SECONDS` | 30 天 | 登录会话有效期 |
| `SHARE_TTL_SECONDS` | 600 秒 | 分享链接默认有效期 |
| `PBKDF2_ROUNDS` | 120000 | 密码哈希轮数（**改动会让所有已有密码失效**） |
| `MAX_JSON_BODY` | 4 MB | JSON 请求体上限（文件上传走流式，不受此限制） |
| `ALLOWED_FOLDERS` | `software` / `docs` / `misc` | 允许写入的业务目录白名单 |

另有两个**只作用于脚本**的变量（后端程序本身不读它们）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `WORKHUB_SERVER` | 无（必填） | 部署目标，形如 `root@192.168.1.10`；也可写进 `deploy/.env` |
| `WORKHUB_NODE` | `node` | 构建前端用的 Node 命令 |
| `WORKHUB_JAVA` | 自动探测 | 跑后端用的 `java.exe` 完整路径（Git Bash 下必须给完整路径） |
| `WORKHUB_MYSQL` | 自动探测 | `mysql` 客户端完整路径（`db-local.sh` 用） |

---

## 八、数据模型

MySQL，InnoDB 引擎，库名 `workhub`，字符集 `utf8mb4`。共 7 张表：

| 表 | 说明 | 关键约束 |
|---|---|---|
| `users` | 账号 | `email` 唯一，密码存 `pwd_salt` + `pwd_hash`（不存明文） |
| `sessions` | 登录会话 | `token` 主键，`expires_at` 控制有效期 |
| `software_packages` | 软件仓库 | `owner_id` + 文件路径、文件名、大小、下载次数 |
| `daily_reports` | 工作日报 | **`UNIQUE(owner_id, report_date)`** —— 一人一天一条，重复保存即更新 |
| `work_logs` | 工作记录 | `log_date` / `title` / `content` / `tags` |
| `todos` | 任务待办 | `priority`、`due_date`、`status`(pending/done)、`done_at` |
| `documents` | 文件资料库 | 分类、标签、备注、mime、下载次数 |

> 💡 **时间字段是 `VARCHAR` 存的 ISO 字符串**（如 `2026-10-02T15:38:01`），不是 `DATETIME`。
> 这样接口返回的 `created_at` 仍是字符串、字典序等于时间序，前端一个字都不用改。

文件落盘规则（数据库已经不在这个目录里了）：

```
/var/lib/workhub/
├── secret.key          分享链接签名密钥（首次启动自动生成，权限 600）
└── storage/
    └── <用户id>/
        ├── software/   软件安装包
        ├── docs/       文档资料
        └── misc/       其他
```

所有业务表都建了 `owner_id` 索引，列表查询默认按时间倒序。

### 数据库在哪、怎么打开

数据在 **MySQL** 的 `workhub` 库里。

| | 位置 |
|---|---|
| 生产数据库 | MySQL 的 `workhub` 库（服务器上） |
| 生产附件 | `/var/lib/workhub/storage/<用户id>/`（在磁盘上，不在数据库里） |
| 本地开发库 | 本地 MySQL 的 `workhub` 库（**本机测试数据，与线上无关**） |

命令行查库（连接参数从 `deploy/.env` 读）：

```bash
bash deploy/db-local.sh                          # 概览：连接信息 + 各表行数
bash deploy/db-local.sh tables                   # 表结构
bash deploy/db-local.sh sql "select id, email, created_at from users"
```

也可以直接用 mysql 客户端：

```bash
"/c/Program Files/MySQL/MySQL Server 5.7/bin/mysql" -uroot -p workhub
```

### 用 DBX 管理数据库

本机装了 **DBX**（`D:\Program Files\DBX\dbx.exe`），MySQL 是它的原生类型 —— 能浏览数据、改表结构、建索引、备份/还原。

**加连接**：

1. 打开 DBX → 新建连接（也可在浏览器地址栏敲 `dbx://connection/new` 直达）
2. 类型选 **MySQL**
3. 主机 `127.0.0.1`、端口 `3306`、用户 `root`、密码填上
4. 保存后左侧列表出现这个连接，展开选 `workhub` 库

> 本机已加过一个名字叫 **「本机」** 的 MySQL 连接（就是这台机器的 3306），直接展开就能用 ——
> 连接 id `ab91e558-0284-4de6-9b2c-695bc0494d05`（AI 侧直接用它查库，不用每次重新找）。已实测能查到 `workhub` 的 7 张表。
>
> 早先那个指向 `.devdata/workhub.db` 的 SQLite 连接（名字「工作台」）已随迁库废弃，
> 本地那份 SQLite 库文件也已在整理时删除 —— DBX 里这个连接可以直接移除。

> ⚠️ **DBX 的 MCP 有全局只读模式**（DBX 设置 → MCP），默认开着。开着的时候 AI 只能查询，**不能新建连接、也不能写数据** —— 对「随州应急」这类生产库留着更安全。想让我能加连接或执行写操作，需要自己临时关掉。

线上库同理，主机填服务器地址即可。

> 不要直接在生产库上 `update` / `delete` 改数据。业务表都靠 `owner_id` 做隔离、靠接口层维护字段（如日报的 `report_date` 唯一约束），绕过接口改容易留下不一致的数据。要改就用页面改。

---

## 九、接口一览

全部挂在 `/api/` 前缀下，鉴权用 `Authorization: Bearer <token>`。

### 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册（邮箱 + 密码） |
| POST | `/api/auth/login` | 登录，返回 token |
| POST | `/api/auth/logout` | 退出，销毁会话 |
| POST | `/api/auth/password` | 修改密码 |
| GET | `/api/auth/me` | 取当前登录用户 |

### 业务（均为标准 CRUD）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET / POST | `/api/software` | 软件列表（支持 `keyword` / `category`）/ 新增 |
| PUT / DELETE | `/api/software/{id}` | 修改 / 删除 |
| POST | `/api/software/{id}/download` | 下载计数 +1 |
| GET | `/api/reports` | 日报列表（`from` / `to` / `limit`） |
| GET | `/api/reports/one?date=` | 取指定日期日报 |
| POST | `/api/reports` | 保存日报（按日期 upsert） |
| DELETE | `/api/reports/{id}` | 删除 |
| GET / POST | `/api/logs` | 工作记录列表（`keyword` / `limit`）/ 新增 |
| PUT / DELETE | `/api/logs/{id}` | 修改 / 删除 |
| GET / POST | `/api/todos` | 待办列表 / 新增 |
| PUT / DELETE | `/api/todos/{id}` | 修改（含完成状态）/ 删除 |
| GET / POST | `/api/documents` | 资料列表 / 新增 |
| PUT / DELETE | `/api/documents/{id}` | 修改 / 删除 |
| POST | `/api/documents/{id}/download` | 下载计数 +1 |

### 文件

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/upload?folder=&name=` | **流式**上传，先写 `.part` 再 `os.replace` 落位 |
| GET | `/api/download?path=` | 直接下载（需登录态） |
| GET | `/api/share?path=&ttl=` | 生成**带签名**的临时链接 |
| DELETE | `/api/files?path=` | 删除文件 |
| GET | `/api/health` | 健康检查 |

**大文件怎么处理的**：上传时按 `Content-Length` 循环 `rfile.read(min(CHUNK, remaining))` 直接写盘，不整份读进内存；Nginx 侧 `client_max_body_size 0`（不限体积）且 `proxy_request_buffering off`（不落盘缓冲）。**实测 280 MiB 文件上传 HTTP 200、81 秒、3.6 MB/s，服务端 md5 与本地逐字节一致。**

---

## 十、备份与恢复

要备份两样东西：**MySQL 的 `workhub` 库** + **上传的文件目录**。

```bash
# 1) 数据库：逻辑备份。--single-transaction 走一致性快照，不锁表、不用停服
mysqldump -uroot -p --single-transaction --routines --triggers workhub > /root/workhub-db-$(date +%F).sql

# 2) 上传的文件 + 分享签名密钥
tar czf /root/workhub-files-$(date +%F).tar.gz -C /var/lib workhub
```

恢复：

```bash
# 1) 数据库
mysql -uroot -p -e "DROP DATABASE IF EXISTS workhub; CREATE DATABASE workhub DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -uroot -p workhub < /root/workhub-db-XXXX-XX-XX.sql

# 2) 文件（secret.key 也在这个包里）
systemctl stop workhub
tar xzf /root/workhub-files-XXXX-XX-XX.tar.gz -C /var/lib
systemctl start workhub
```

> ⚠️ **`secret.key` 必须一起备份**。丢了它，所有已发出去的分享链接会全部失效（账号密码不受影响，因为不依赖它）。
>
> 💡 想定期自动备份，把上面两条写成脚本挂 cron 即可。

---

## 十一、安全设计

| 项 | 做法 |
|---|---|
| 密码存储 | PBKDF2-SHA256，**12 万轮**，每用户 16 字节随机盐；数据库里没有明文或可逆密文 |
| 会话 | 服务端 `sessions` 表签发随机 token，30 天有效，退出即删除记录 |
| 数据隔离 | 所有查询强制拼 `owner_id` 条件，不依赖前端传参决定归属 |
| 路径穿越 | 上传路径由服务端拼接，目录名走 `ALLOWED_FOLDERS` 白名单 + `SAFE_SEG` 正则清洗 |
| 临时分享 | 下载链接带 HMAC 签名 + 有效期（默认 600 秒），篡改签名即拒绝 |
| SQL 注入 | 全部使用参数化查询；表名/字段名来自代码内固定白名单，不接受用户输入 |
| LIKE 通配 | 用户关键词先清洗 `%` `_` `\` 再拼 `%kw%` |
| 监听面 | 后端只绑 `127.0.0.1`，外部只能通过 Nginx 的 8099 进入 |
| 进程加固 | systemd 启用 `NoNewPrivileges=true`、`PrivateTmp=true`、`ProtectSystem=full`，除数据/日志目录外系统目录只读 |
| 对外暴露 | **无任何公网入口**，只能在网内访问 |

---

## 十二、设计权衡与已知问题

### 1. 服务器时钟不准，应用层做了规避

部署机的系统时钟比实际日期**慢约 13.6 天**（`chronyd` 在运行但时钟未跳变，RTC 与系统时间也不一致）。

处理方式：

- **业务时间**（`created_at` / `updated_at`）优先采用请求头 `X-Client-Time`，即**使用者电脑的时间**，所以页面上看到的时间是正确的；没带该头时回退服务器时间。
- **会话与分享链接的有效期**一律用 `server_now()` —— 签发和校验必须落在同一个时钟上，否则会自相矛盾。

实测对照：浏览器注册的账号 `created_at = 2026-10-01T10:53:09`（正确），`curl` 不带头的账号 `2026-09-17T19:52:28`（服务器时间）。机制确认生效。

> 治本命令（需评估对其他服务日志时间戳的影响后再执行）：`chronyc makestep`

### 2. 忘记密码只能在服务器上处理

系统没有邮件服务，所以没有"找回密码"流程。最省事的是清空重来：

```bash
systemctl stop workhub
mysql -uroot -p -e "DROP DATABASE workhub;"
systemctl start workhub      # 启动时会自动重建空库空表
```

> ⚠️ 这会**同时清空所有业务数据**。只删账号、保留数据意义不大（旧数据因找不到归属用户而不可见）。
>
> 真要只删一个账号，可以 `DELETE FROM users WHERE email = '...';` —— 但该用户名下的数据行会变成"孤儿"。

### 3. 其他

- **删除软件/资料记录会同时删除磁盘文件**（前端逻辑里一并清理）。
- **单人使用为主**：功能上是多用户（注册、隔离都做了），但没有角色权限、没有管理员、没有用户管理界面。
- **登录状态存在 `localStorage`**：隐私模式下会退化为仅内存保存，刷新需重新登录。

---

## 十三、常见问题

**Q：访问 8099 打不开？**

按顺序查三层：

```bash
systemctl is-active nginx workhub     # 1. 两个服务是否在跑
ss -lnt | grep -E ':(8098|8099)'      # 2. 端口是否在监听
curl -s http://127.0.0.1:8098/api/health   # 3. 后端本身是否正常（绕过 Nginx）
curl -s http://127.0.0.1:8099/api/health   # 4. 经 Nginx 是否正常
```

若 8098 通、8099 不通 → 问题在 Nginx；若都不通 → 问题在后端，看 `journalctl -u workhub -n 50`。

**Q：页面空白或报 404？**

前端产物没上传，或 `/var/www/workhub` 里缺 `index.html`。重新执行 `bash deploy/deploy.sh frontend`。

**Q：上传大文件失败？**

检查两处：Nginx 的 `client_max_body_size 0` 和 `proxy_request_buffering off` 是否生效（改完要 `nginx -t && systemctl reload nginx`）；以及磁盘剩余空间 `df -h /var`。

**Q：改完 Nginx 配置重启后端口没监听？**

reload 有短暂延迟，等 1~2 秒再确认。

**Q：接口全部返回 401？**

token 过期（30 天）或已被清理，重新登录即可。若刚登录也 401，检查浏览器是否拦截了 `Authorization` 头，或 `localStorage` 是否被清理。

**Q：`ssh -T git@github.com` 返回退出码 1，是失败了吗？**

不是。GitHub 不提供 shell，**鉴权成功也返回 1**。判断标准是输出里有没有 `Hi <用户名>! You've successfully authenticated`。

**Q：数据库在哪？怎么直接看数据？**

在 **MySQL 的 `workhub` 库**里，没有文件可以直接打开。三种看法：

1. **DBX**（图形界面，推荐）—— 见第八节的「用 DBX 管理数据库」
2. **命令行** —— `bash deploy/db-local.sh`（本地）或 `bash deploy/db-local.sh sql "select ..."`
3. **mysql 客户端** —— `mysql -uroot -p workhub`，然后 `select * from todos;`

需要导出成 Excel 时，用 `SELECT ... INTO OUTFILE`，或者干脆在 DBX 里右键结果集导出 CSV。

**Q：部署脚本报 `Could not create directory '/c/Users/xxx/.ssh'`，接着 `Permission denied (publickey)`？**

这是 **Windows 用户名含中文**引起的（例如 `C:\Users\王杰`）：SSH 按本地代码页转换用户目录得到乱码路径，于是**找不到私钥** —— 和服务器配置无关，密钥本身是好的。

`deploy.sh` 已内置规避：检测到 `$HOME` 含非 ASCII 字符时，自动把密钥复制到纯英文路径 `C:\wbssh\.ssh` 并用 `-i` 显式指定。如果是手工敲 ssh 命令遇到同样问题，照这样写即可：

```bash
ssh -i /c/wbssh/.ssh/id_ed25519 \
    -o UserKnownHostsFile=/c/wbssh/.ssh/known_hosts \
    -o IdentitiesOnly=yes \
    root@192.168.1.x
```

---

## 许可

个人自用项目，未附开源许可证。如需使用请自行判断。
