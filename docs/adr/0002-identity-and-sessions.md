# ADR 0002：身份与会话（T02）实现决策

- 状态：已接受
- 日期：2026-11-14
- 关联：[PRD #1](https://github.com/Calendula597/TestAutomation/issues/1)、[T02 #3](https://github.com/Calendula597/TestAutomation/issues/3)、[ADR 0001](./0001-t01-foundation-and-pinned-versions.md)

## 背景

T02 要求：一次性初始化管理员（无默认账号）、HttpOnly 会话 Cookie + CSRF、退出与禁用用户使会话失效、审计不含敏感值。PRD 指定服务端会话与 MyBatis-Plus。

## 决策与验证过程

1. **Spring Session JDBC 持久化会话**：Boot 4 将 JDBC 会话自动配置拆分到 `spring-boot-session-jdbc` 模块（仅引 `spring-session-jdbc` 不会自动配置）；表结构由 Flyway V2 管理；禁用用户时按 `PRINCIPAL_NAME` 索引删除全部会话；新增每分钟过期会话清理任务。
2. **CSRF 采用 Spring Security 7 的 `csrf().spa()`**：手写 6.x 的 `CookieCsrfTokenRepository` + 委托 handler 组合在 7.x 下持续报 invalid token。实测发现根因：`CsrfToken.getToken()` 返回 BREACH 编码值，而 cookie 存 raw 值；SPA 必须把 **cookie 原始值** 放入 `X-XSRF-TOKEN` 头。前端 `apiSend` 统一实现；`GET /api/v1/auth/csrf` 仅触发 cookie 写入，响应体不再暴露编码 token。另注意：`/error` 转发必须 permitAll，否则错误响应被安全链改写为 401。
3. **MyBatis-Plus 3.5.18.1（Boot 4 starter）**：实体 `role`/`status` 存 String，枚举转换放在 `UserPrincipal` 与 DTO 边界。原因：`@EnumValue` 组合处理器在 Boot 4 starter 装配下对 PG `setObject` 不生效（运行时实测），配置 `default-enum-type-handler` 亦无效；String + 边界枚举是最小可靠方案。
4. **初始化即登录**：一次性初始化成功后服务端建立会话（`SecurityContextRepository.saveContext`），前端因此持有真实会话而非仅内存状态。该缺口在浏览器 E2E 中发现（初始化后 `/api/v1/auth/me` 401）。
5. **登录失败统一消息**："用户名或密码不正确，或账号已被禁用"，防用户名枚举；`LOGIN_FAILED` 审计记录输入用户名（非凭据）。
6. **无默认账号**：空库才允许 `POST /api/v1/system/initialize`（409 之后），首个账号固定 ADMIN；后续用户由 ADMIN 通过 `POST /api/v1/system/users` 创建。

## 验证

- 集成测试 4 项（真实 PG 容器）：一次性初始化（含弱密码拒绝、二次 409、响应建立 HttpOnly 会话）、CSRF 强制（缺头 403）、登录 HttpOnly cookie、禁用用户会话失效 + 重新登录拒绝、退出失效、审计动作齐全且不含任何密码。
- 浏览器 E2E：守卫重定向 → 初始化 → 自动登录 → 页面创建用户 → UI 禁用 → 受影响会话实时 401 → 退出登录。
