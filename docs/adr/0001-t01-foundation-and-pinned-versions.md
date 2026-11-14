# ADR 0001：T01 基础工程与锁定版本

- 状态：已接受
- 日期：2026-11-14
- 关联：[PRD #1](https://github.com/Calendula597/TestAutomation/issues/1)、[T01 #2](https://github.com/Calendula597/TestAutomation/issues/2)

## 背景

T01 要求以 Java/Maven + Vue 结构替换通用 TypeScript 示例，从干净检出可启动数据库、后端与前端，健康页展示真实 API 与数据库结果，所有检查在本地与 CI 通过，并锁定依赖补丁版本。PRD 规定：先验证兼容性再锁定精确版本，禁止浮动版本与 `latest` 标签。

## 决策

1. **Java 21 为目标字节码**：`maven.compiler.release=21`。本机 JDK 25 / CI Temurin 21 均可编译与运行，PRD 的 Java 21 目标不依赖具体本机 JDK。
2. **Spring Boot 4.1.2（锁定补丁）**：截至 2026-11-14 的最新稳定线；4.2 仍为 RC，不采用。Boot 4 的结构性变化已按需适配：Flyway 自动配置移入 `spring-boot-flyway` 模块；`TestRestTemplate` 移除，测试改用 `RestClient` + `@LocalServerPort`。
3. **Testcontainers 2.0.5（官方 BOM）**：Boot 4 不再管理其版本。2.x 模块坐标更名（`testcontainers-postgresql`、`testcontainers-junit-jupiter`），`PostgreSQLContainer` 不再泛型。
4. **PostgreSQL 16.15 + pgvector 镜像按 digest 锁定**（`pgvector/pgvector:pg16@sha256:7b822b0a…`，2026-11-14 本机验证）；Compose 为唯一数据库交付方式；Testcontainers 测试使用 `pgvector/pgvector:pg16` 标签（CI 拉取，主版本固定 16）。V1 迁移启用 `vector` 扩展并有集成测试覆盖。
5. **健康接口为自定义 `/api/v1/health`**：返回 200 与明确的状态体（UP/DOWN、延迟、数据库版本），前端据此渲染；数据库故障绝不伪装成成功。不使用 Actuator 端点，避免暴露面与前端契约耦合。
6. **前端版本精确锁定（无 `^` 区间）**：Vue 3.5.45、vue-router 5.5.1、Element Plus 2.14.8、Vite 8.3.8、TypeScript 6.0.3、Vitest 4.1.12、jsdom 26.1.0、eslint 10.15.0 + eslint-plugin-vue 10.12.1 + typescript-eslint 8.72.2、dependency-cruiser 17.4.3、Node ≥24（`.nvmrc` 24，CI 24）。选型依据 peer 约束验证：typescript-eslint 8.72.2 要求 TS <6.1（排除 TS 7）；Vitest 5 / jsdom 30+ 的 engines 不含 Node 25，为本地非 LTS 环境选用兼容线。
7. **格式化归 Prettier**：`eslint-config-prettier` 10.1.8 置于 ESLint 配置末位，关闭与 Prettier 冲突的 vue 样式规则。
8. **MyBatis-Plus 暂不引入**：T01 无业务表；首个持久化票据（T02/T03）验证其 Boot 4 兼容性后再锁定版本。当前持久化访问仅 `JdbcTemplate`。
9. **持久化业务模块划分**：`backend/` Maven 多模块（当前 `platform-server`），前端 `frontend/` 为单一 deep module，边界由 `lint:boundaries` 强制。

## 后果

- 升级必须显式变更锁定版本并重新验证（含 digest、peer 约束、CI）。
- Testcontainers 测试镜像随 pg16 标签漂移补丁版本，但主版本锁定；Compose 生产口径由 digest 完全固定。
- Element Plus 全量导入带来约 1 MB 构建（gzip 330 kB）；后续票据按需优化，不阻塞 T01。
