# 部署与本地启动

本页描述如何从干净检出启动数据库、Java 后端、Chromium 和 Vue 前端。

## 前置条件

| 工具 | 版本要求 | 说明 |
| --- | --- | --- |
| Docker + Compose v2 | 当前稳定版 | 仅用于运行 PostgreSQL（含 pgvector） |
| JDK | 21+（本机更高版本也可） | 工程按 Java 21 字节码编译；CI 固定 Temurin 21 |
| Maven | 3.9+ | |
| Node.js | 24 LTS（`.nvmrc`） | 本机 25 也可运行；CI 固定 24 |
| npm | 11+ | 依赖版本全部精确锁定 |

## 1. 启动数据库

```bash
cd deploy
cp .env.example .env        # 可选；默认端口 5432，密码见 .env.example
docker compose up -d db
```

镜像按 digest 锁定为 PostgreSQL 16.15 + pgvector（禁止浮动标签）。

## 2. 安装 Chromium 并启动后端

```bash
cd backend
mvn -B -pl platform-server exec:java@playwright-install
mvn -B spring-boot:run -pl platform-server
```

Playwright Java 版本锁定为 1.65.0，安装命令只下载与其匹配的 Chromium。执行用例时不会自动下载浏览器；缺少浏览器或依赖时会返回明确的执行错误。若使用已有 Chrome/Chromium，可跳过安装命令并设置 `APP_WORKER_CHROMIUM_PATH` 为可执行文件绝对路径。

环境变量（可选，默认值对应上面的 Compose 配置）：

| 变量 | 默认值 |
| --- | --- |
| `APP_DB_URL` | `jdbc:postgresql://localhost:5432/test_platform` |
| `APP_DB_USER` | `testplatform` |
| `APP_DB_PASSWORD` | `testplatform_dev` |
| `APP_WORKER_CHROMIUM_PATH` | 空；使用 Playwright 配套 Chromium |
| `APP_EVIDENCE_DIR` | `./evidence` |
| `APP_EXECUTION_API_TIMEOUT_SECONDS` | `120` |
| `APP_EXECUTION_WEB_TIMEOUT_SECONDS` | `120` |
| `APP_EXECUTION_ELECTRON_TIMEOUT_SECONDS` | `120` |

若修改了 `POSTGRES_HOST_PORT`，同步修改 `APP_DB_URL` 端口。生产部署必须将 `APP_EVIDENCE_DIR` 放到持久卷；截图、Trace 和下载文件可能含业务数据，只能通过项目授权接口读取。

## 3. 启动前端

```bash
cd frontend
npm ci
npm run dev
```

打开 <http://localhost:5173>，页面“系统健康”展示真实的后端 API 与数据库健康状态（含延迟、数据库版本、pgvector 迁移后的连接）。开发服务器将 `/api` 代理到 `http://localhost:8080`，可用 `VITE_API_PROXY_TARGET` 覆盖。

## 4. 启动独立 Electron Worker（可选）

先在“Worker 管理”注册声明 `ELECTRON` 能力的 Worker，并保存只显示一次的凭据，然后运行：

```bash
cd workers/electron
npm ci
export CONTROL_PLANE_URL=http://127.0.0.1:8080
export WORKER_TOKEN=替换为注册凭据
npm start
```

`npm ci` 会通过项目 `postinstall` 安装锁定版本的 Electron。默认下载源不可访问时，可执行 `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/ npm ci`；也可用 `ELECTRON_EXECUTABLE_PATH` 指定已有 Electron 可执行文件。Linux 无图形桌面时改用 `xvfb-run -a npm start`。`WORKER_ONCE=1` 可让 Worker 处理一个任务后退出。

当前验收应用是仓库内演示 Electron 应用。渲染器只能通过受控 IPC 保存笔记，主进程只暴露 `getSavedNotes` 测试钩子；这是实验性能力，不支持原生系统对话框，也不接受任意 JavaScript 或任意应用路径。

## 5. 启动 WinCore 执行环境（Windows 10+）

在 Windows 主机执行以下命令安装 Appium 4、WinCore 和固定 Node 24，然后启动 Appium 服务：

```bash
cd workers/appium
export APPIUM_HOME=当前工作区/workers/appium/.appium
npm ci
npm start
```

服务默认监听 `127.0.0.1:4723`。主 Worker 使用 `APP_WORKER_APPIUM_BASE_URL` 指向该服务，并通过 `APPIUM_HOME` 读取 WinCore 的真实版本。演示 WinForms 应用由系统签名的 PowerShell 宿主启动，可用 `APP_WORKER_WINDOWS_DEMO_SCRIPT` 指定脚本绝对路径；`APP_WORKER_POWERSHELL_PATH` 可覆盖宿主路径。

WinCore 直接操作 Windows UI Automation，不使用已弃用的 Windows Driver/WinAppDriver。报告记录应用类型、WinCore 版本、步骤失败原因和截图。原生 Windows 验收只在 Windows 主机运行，Linux CI 不会将其标记为已验证。

## 6. 统一检查

```bash
mvn -B verify          # backend/：单元 + 集成测试（真实 PostgreSQL、Chromium 和 Electron）
npm run check          # frontend/：格式 + lint + 类型 + 边界 + 测试 + 构建
npm run check          # workers/electron/：Worker 与演示应用语法检查
```

后端 Electron 集成测试要求先执行 `workers/electron/npm ci`；Linux 还需要 `xvfb-run -a mvn -B verify`。

## 故障排查

- **健康页显示“异常”，数据库一栏报错**：数据库未启动或端口不一致。确认 `docker compose ps` 中 `db` 为 healthy，并核对 `APP_DB_URL`。数据库停止时健康接口约 3 秒内返回 DOWN（池连接超时已设为 3s），页面如实展示错误。
- **后端启动失败（Flyway 连接错误）**：后端启动要求数据库已就绪；先 `docker compose up -d db` 再启动后端。
- **健康页显示“后端不可达”**：后端未启动或代理目标不对；检查 8080 端口与 `VITE_API_PROXY_TARGET`。
- **端口冲突**：5432 被占用时在 `deploy/.env` 设置 `POSTGRES_HOST_PORT`；8080 冲突时以 `SERVER_PORT` 环境变量调整后端端口并同步代理目标。
- **pgvector 扩展缺失**：V1 迁移会自动启用；只有官方 pgvector 镜像带该扩展，不要换成普通 postgres 镜像。
- **Web 用例提示 Chromium 不存在或无法启动**：先运行 `mvn -B -pl platform-server exec:java@playwright-install`，或检查 `APP_WORKER_CHROMIUM_PATH` 是否指向真实可执行文件。运行时不会自动补装 Firefox/WebKit，也不会把缺失浏览器伪装成执行成功。
- **执行记录有结果但证据下载 404**：检查 `APP_EVIDENCE_DIR` 是否为持久目录，以及 Worker 与 API 服务是否使用同一证据卷；证据元数据中的存储键不能指向卷外文件。
