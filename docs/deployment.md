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
| `APP_BACKUP_DIR` | `./backups` |
| `APP_EXECUTION_SCHEDULE_SWEEP_MS` | `1000` |
| `APP_EXECUTION_API_TIMEOUT_SECONDS` | `120` |
| `APP_EXECUTION_WEB_TIMEOUT_SECONDS` | `120` |
| `APP_EXECUTION_ELECTRON_TIMEOUT_SECONDS` | `120` |
| `APP_EXECUTION_WINDOWS_NATIVE_TIMEOUT_SECONDS` | `120` |
| `APP_EXECUTION_PERFORMANCE_TIMEOUT_SECONDS` | `60` |
| `APP_EXECUTION_PERFORMANCE_MAX_VUS` | `8` |
| `APP_EXECUTION_PERFORMANCE_MAX_DURATION_SECONDS` | `30` |

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
powershell.exe -NoProfile -ExecutionPolicy Bypass -File demo/build-native.ps1
export APP_WORKER_WINDOWS_DEMO_EXECUTABLE="$PWD/demo/bin/NativeDemo.exe"
export APPIUM_HOME=当前工作区/workers/appium/.appium
npm ci
npm start
```

服务默认监听 `127.0.0.1:4723`。主 Worker 使用 `APP_WORKER_APPIUM_BASE_URL` 指向该服务，并通过 `APPIUM_HOME` 读取 WinCore 的真实版本。默认演示是 .NET Framework 编译的原生 WinForms EXE，使用 `STAThread` 和标准消息循环，可用 `APP_WORKER_WINDOWS_DEMO_EXECUTABLE` 指定绝对路径。旧版 PowerShell 演示可通过 `APP_WORKER_WINDOWS_DEMO_SCRIPT` 回退，`APP_WORKER_POWERSHELL_PATH` 可覆盖宿主路径。

WinCore 直接操作 Windows UI Automation，不使用已弃用的 Windows Driver/WinAppDriver。报告记录应用类型、WinCore 版本、步骤失败原因和截图。原生 Windows 验收只在 Windows 主机运行，Linux CI 不会将其标记为已验证。

## 6. 统一检查

```bash
mvn -B verify          # backend/：单元 + 集成测试（真实 PostgreSQL、Chromium 和 Electron）
npm run check          # frontend/：格式 + lint + 类型 + 边界 + 测试 + 构建
npm run check          # workers/electron/：Worker 与演示应用语法检查
```

后端 Electron 集成测试要求先执行 `workers/electron/npm ci`；Linux 还需要 `xvfb-run -a mvn -B verify`。

## 7. 受限性能压测

性能用例只能由项目负责人创建、发布和发起，普通成员不能执行。每次发起还必须单独确认；并发、阶段数和总时长受 `APP_EXECUTION_PERFORMANCE_MAX_VUS` 与 `APP_EXECUTION_PERFORMANCE_MAX_DURATION_SECONDS` 限制，只允许环境白名单中的 HTTP/HTTPS 目标，不支持分布式压测。

默认通过 Docker 运行固定镜像 `grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276`，先执行：

```bash
docker pull grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276
```

也可用 `APP_WORKER_K6_PATH` 指定本地 k6 可执行文件，或用 `APP_WORKER_K6_IMAGE` 覆盖经过审核的镜像。Linux 下按宿主机 UID/GID 运行容器并设置 `HOME=/tmp`，不提升为 root，确保能写入本次执行专属证据目录。报告保存 k6 原始脚本、控制台输出和 `summary-export`，并据其中的真实计数、错误率及 P90/P95/P99 判定项目阈值。测试本机服务时，容器通过 `host.docker.internal` 访问宿主。

## 8. 白盒执行与通用报告导入

白盒 Worker 在独立目录中复制固定 `demo-js` 项目并运行 Vitest 5.1.0 + V8，不执行用户提供的 Shell 命令。注册 `WHITEBOX` 能力 Worker 后运行：

```bash
cd workers/whitebox
export CONTROL_PLANE_URL=http://127.0.0.1:8080
export WORKER_TOKEN=替换为注册凭据
export EVIDENCE_DIR=与后端相同的证据目录
npm start
```

每次运行分别安装依赖并保存原始测试结果、覆盖率、工具输出和 `vitest.config.js`。语句、分支、函数覆盖率与排除规则均来自真实执行；`src/legacy.js` 的排除必须与版本化配置一致。`TEST_FAILED`、`COVERAGE_FAILED` 和 `TOOL_ERROR` 分别对应测试失败、覆盖不达标和工具错误。

其他语言仅提供通用报告导入，不执行原工具：`POST /api/v1/projects/{projectId}/report-imports` 接收 `language`、`filename` 和 `content`，原始文件可在项目内授权下载，不按 TS/JS 覆盖率规则判定。

## 9. Android 模拟器执行（Windows 主机）

只使用专用测试模拟器，不要在日常设备上运行这些会清空应用的验收。iOS 不在当前验收范围内。需要 JDK（设置 `JAVA_HOME`）、Docker 以外的 Android SDK，以及 Node 24。SDK 命令行工具必须位于 `cmdline-tools/latest/bin`，否则生成的 AVD 路径会错误。

```bash
export ANDROID_SDK_ROOT=/绝对路径/tools/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0" "emulator" \
  "system-images;android-34;google_apis;x86_64"
printf 'no\n' | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager.bat" \
  create avd -n test-automation-api34 \
  -k 'system-images;android-34;google_apis;x86_64' -d pixel_6 --force
"$ANDROID_SDK_ROOT/emulator/emulator.exe" -avd test-automation-api34 \
  -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -memory 2048
```

另开终端安装固定版本的 Node、Appium 与驱动。`node` 和 `npm` 都在项目内锁定；第一条命令只引导安装，第二条完成安装并强制校验 Appium 的实际运行时为 Node 24.15+。推荐先用版本管理器选择 Node 24；若系统安装器是 Node 25，可能出现引擎警告，但不能绕过安装脚本的运行时校验。检查和启动使用项目内 Node 24/npm。

```bash
cd workers/appium
npm ci --ignore-scripts
npm ci   # postinstall 强制校验 Appium 的实际运行时为 Node 24.15+
node_modules/node/bin/node.exe node_modules/npm/bin/npm-cli.js run check
export APPIUM_HOME="$PWD/.appium"
node_modules/node/bin/node.exe node_modules/npm/bin/npm-cli.js start
```

在仓库根目录构建原生演示 APK（不是 Chromium 或 Web 容器）：

```bash
export JAVA_HOME=/绝对路径/jdk
powershell.exe -NoProfile -ExecutionPolicy Bypass \
  -File workers/appium/demo/android/build.ps1 -Sdk "$ANDROID_SDK_ROOT"
export APP_WORKER_ANDROID_DEMO_APK="$PWD/workers/appium/demo/android/build/demo.apk"
```

创建 Android 用例时，`caseType=ANDROID`，执行配置必须明确包含 `app=demo-android`、`deviceSerial`、`reset=true` 和步骤。支持 `FILL`、`CLICK`、`ASSERT_TEXT`、`WAIT`，定位值为无障碍 `contentDescription`。执行会强制重装、清空并重启演示应用，同一设备由数据库锁互斥，结束时删除 Appium 会话并清理应用。报告记录设备型号、Android 版本、UiAutomator2 版本和真实截图；断连是 `ERROR`，不是业务断言失败。

专用模拟器启动完成后，可运行真实验收。测试自己启动回环 `4724` Appium 服务，并在结束时关闭该模拟器；不要并发运行其他 Android 验收。

```bash
cd backend
mvn -B -pl platform-server -Dandroid.e2e=true \
  -Dandroid.sdk="$ANDROID_SDK_ROOT" -Dtest=AndroidExecutionApiIntegrationTest test
```

## 10. 导出、保留与备份恢复

执行详情页可导出 HTML、JUnit XML、Excel、CSV 和原始引擎输出。所有导出都经过项目权限检查；原始输出使用执行器已经脱敏的结果，JUnit 的时间单位为秒。

项目负责人可通过 `GET/PUT /api/v1/projects/{projectId}/evidence-policy` 查看或设置 1–365 天的证据保留期，默认 30 天。每个文件按其执行所属项目独立计算保留期。清理只删除附件，保留执行摘要、不可变用例版本和审计；`evidence_pruned` 记录清理结果，已清理证据下载返回 410。系统每小时自动清理，管理员也可调用 `POST /api/v1/system/evidence-retention/sweep` 立即执行。

管理员通过 `POST /api/v1/system/backups` 创建 ZIP，包含 PostgreSQL 的 `pg_dump` 输出与证据目录。备份与恢复使用当前数据库容器自带、版本一致的客户端，避免嵌套拉取工具镜像或混用不同主版本；SQL 与错误日志分开，并校验备份完整性。通过 `POST /api/v1/system/restore` 提交返回的 `filename`，系统会恢复到**新的独立数据库和附件目录**，不覆盖正在运行的环境。

按接口返回的数据库名和 `evidenceDir` 启动隔离实例，使用原部署的密钥主密钥：

```bash
export APP_DB_URL=jdbc:postgresql://localhost:5432/恢复接口返回的数据库名
export APP_DB_USER=testplatform
export APP_DB_PASSWORD=原数据库密码
export APP_EVIDENCE_DIR=/绝对路径/恢复接口返回的evidenceDir
export APP_SECRETS_MASTER_KEY=原部署主密钥
export APP_BACKUP_DIR=/绝对路径/backups
mvn -B spring-boot:run -pl platform-server   # backend/
```

然后使用备份中的账号登录，实际查询历史执行并下载证据。生产环境请持久化并限制 `APP_BACKUP_DIR`、`APP_EVIDENCE_DIR` 的访问权限，并保证后端可以通过 Docker 定位当前 PostgreSQL 容器。测试已验证空数据库与附件目录恢复、独立应用登录和证据读取，而不只验证 ZIP 存在。

## 11. 定时与 CI 触发

项目“定时与 CI”页面可创建、启停计划。Cron 为**秒、分、时、日、月、周**六段格式，必须明确 IANA 时区（例如 `Asia/Shanghai`）。计划固定已发布用例版本和环境，默认禁止与上一个排队中或运行中的任务重叠。相同用例、环境、Cron 和时区的重复计划会被拒绝；数据库原子更新下一次执行时间防止并发重复触发。定时压测仍要求项目负责人明确确认。

项目负责人可签发仅含 `EXECUTION_TRIGGER` 和/或 `EXECUTION_READ` 的项目级令牌，必须设置不超过 365 天的到期时间。原始令牌只显示一次，服务端只保存 SHA-256 摘要，可随时撤销。CI 令牌不能登录浏览器、调用 Worker 协议或越权项目；CI 压测不允许绕过单独确认。

```bash
curl -X POST http://127.0.0.1:8080/api/v1/ci/v1/projects/1/executions \
  -H "Authorization: Bearer $CI_TOKEN" \
  -H "Idempotency-Key: $BUILD_ID" \
  -H 'Content-Type: application/json' \
  -d '{"caseId":1,"environmentId":1}'
curl http://127.0.0.1:8080/api/v1/ci/v1/projects/1/executions/返回的任务ID \
  -H "Authorization: Bearer $CI_READ_TOKEN"
```

同一幂等键和请求返回原任务，不重复执行；同一键对应不同请求返回 409。触发与查询作用域分开，过期或撤销返回 401，作用域不足返回 403，跨项目资源返回 404。查询返回实际的 `PASSED`、`ASSERTION_FAILED`、`ERROR`、`CANCELLED` 或 `TIMED_OUT`，不会把工具错误变成测试断言失败。

## 12. 发布验收与边界

完整实测范围、命令、结果和未验收清单见 [acceptance.md](./acceptance.md)。真实设备、桌面和引擎验收不能由缺少环境的跳过测试替代。GitHub Actions 覆盖通用后端、前端和独立 Windows 作业；Android 使用已准备模拟器的主机进行显式验收。Windows 作业在运行器的临时目录启动原生 PostgreSQL 17 + pgvector 0.8.7（UTF-8），避免 Linux 容器限制；生产部署仍使用文档中固定 digest 的 PostgreSQL 16.15 镜像。

## 故障排查

- **健康页显示“异常”，数据库一栏报错**：数据库未启动或端口不一致。确认 `docker compose ps` 中 `db` 为 healthy，并核对 `APP_DB_URL`。数据库停止时健康接口约 3 秒内返回 DOWN（池连接超时已设为 3s），页面如实展示错误。
- **后端启动失败（Flyway 连接错误）**：后端启动要求数据库已就绪；先 `docker compose up -d db` 再启动后端。
- **健康页显示“后端不可达”**：后端未启动或代理目标不对；检查 8080 端口与 `VITE_API_PROXY_TARGET`。
- **端口冲突**：5432 被占用时在 `deploy/.env` 设置 `POSTGRES_HOST_PORT`；8080 冲突时以 `SERVER_PORT` 环境变量调整后端端口并同步代理目标。
- **pgvector 扩展缺失**：V1 迁移会自动启用；只有官方 pgvector 镜像带该扩展，不要换成普通 postgres 镜像。
- **Web 用例提示 Chromium 不存在或无法启动**：先运行 `mvn -B -pl platform-server exec:java@playwright-install`，或检查 `APP_WORKER_CHROMIUM_PATH` 是否指向真实可执行文件。运行时不会自动补装 Firefox/WebKit，也不会把缺失浏览器伪装成执行成功。
- **执行记录有结果但证据下载 404**：检查 `APP_EVIDENCE_DIR` 是否为持久目录，以及 Worker 与 API 服务是否使用同一证据卷；证据元数据中的存储键不能指向卷外文件。
