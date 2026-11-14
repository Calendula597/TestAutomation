# 部署与本地启动

本页描述如何从干净检出启动数据库、Java 后端和 Vue 前端（T01 验收）。

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

## 2. 启动后端

```bash
cd backend
mvn -B spring-boot:run -pl platform-server
```

环境变量（可选，默认值对应上面的 Compose 配置）：

| 变量 | 默认值 |
| --- | --- |
| `APP_DB_URL` | `jdbc:postgresql://localhost:5432/test_platform` |
| `APP_DB_USER` | `testplatform` |
| `APP_DB_PASSWORD` | `testplatform_dev` |

若修改了 `POSTGRES_HOST_PORT`，同步修改 `APP_DB_URL` 端口。

## 3. 启动前端

```bash
cd frontend
npm ci
npm run dev
```

打开 <http://localhost:5173>，页面“系统健康”展示真实的后端 API 与数据库健康状态（含延迟、数据库版本、pgvector 迁移后的连接）。开发服务器将 `/api` 代理到 `http://localhost:8080`，可用 `VITE_API_PROXY_TARGET` 覆盖。

## 4. 统一检查

```bash
mvn -B verify          # backend/：单元 + 集成测试（Testcontainers 真实 PostgreSQL）
npm run check          # frontend/：格式 + lint + 类型 + 边界 + 测试 + 构建
```

## 故障排查

- **健康页显示“异常”，数据库一栏报错**：数据库未启动或端口不一致。确认 `docker compose ps` 中 `db` 为 healthy，并核对 `APP_DB_URL`。数据库停止时健康接口约 3 秒内返回 DOWN（池连接超时已设为 3s），页面如实展示错误。
- **后端启动失败（Flyway 连接错误）**：后端启动要求数据库已就绪；先 `docker compose up -d db` 再启动后端。
- **健康页显示“后端不可达”**：后端未启动或代理目标不对；检查 8080 端口与 `VITE_API_PROXY_TARGET`。
- **端口冲突**：5432 被占用时在 `deploy/.env` 设置 `POSTGRES_HOST_PORT`；8080 冲突时以 `SERVER_PORT` 环境变量调整后端端口并同步代理目标。
- **pgvector 扩展缺失**：V1 迁移会自动启用；只有官方 pgvector 镜像带该扩展，不要换成普通 postgres 镜像。
