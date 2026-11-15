# TestAutomation

统一测试管理与多执行器自动化平台（自部署、单团队、中文界面）。产品与实施依据见 [PRD #1](https://github.com/Calendula597/TestAutomation/issues/1)，垂直票据为 Issues #2–#26。

## 结构

```text
backend/          Java 21 + Spring Boot（Maven 多模块，控制面与主 Worker）
frontend/         Vue 3 + TypeScript + Element Plus（中文管理界面）
workers/electron/ 独立 Node.js + Playwright Electron Worker 与演示应用
workers/appium/   Appium 4 + WinCore / UiAutomator2 与 Windows、Android 演示应用
workers/whitebox/ 隔离 Vitest 白盒 Worker 与真实测试/覆盖率演示项目
deploy/           Docker Compose（PostgreSQL + pgvector）
docs/             部署文档、ADR、Agent 协作约定
```

## 快速开始

从干净检出启动数据库、后端和前端，见 [docs/deployment.md](./docs/deployment.md)。实测范围与未验收边界见 [docs/acceptance.md](./docs/acceptance.md)。

## 统一检查

```bash
# 后端（编译 + 测试 + 打包；集成测试使用真实 PostgreSQL 容器）
mvn -B verify                      # 在 backend/ 下执行

# 前端（格式 + lint + 类型 + 边界 + 测试 + 构建）
npm run check                      # 在 frontend/ 下执行

# Electron Worker（Worker 与演示应用语法检查；行为由后端集成测试覆盖）
npm run check                      # 在 workers/electron/ 下执行

# Appium Worker（Node 24、WinCore、UiAutomator2 与高危依赖审计）
npm run check                      # 在 workers/appium/ 下执行

# Whitebox Worker（Worker 语法检查；真实测试与覆盖率由后端集成测试覆盖）
npm run check                      # 在 workers/whitebox/ 下执行
```

各部分检查均在 GitHub Actions CI 中执行；原生 Windows 验收使用独立 Windows 作业。
