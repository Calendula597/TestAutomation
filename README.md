# TestAutomation

统一测试管理与多执行器自动化平台（自部署、单团队、中文界面）。产品与实施依据见 [PRD #1](https://github.com/Calendula597/TestAutomation/issues/1)，垂直票据为 Issues #2–#26。

## 结构

```text
backend/          Java 21 + Spring Boot（Maven 多模块，控制面与主 Worker）
frontend/         Vue 3 + TypeScript + Element Plus（中文管理界面）
workers/electron/ 独立 Node.js + Playwright Electron Worker 与演示应用
deploy/           Docker Compose（PostgreSQL + pgvector）
docs/             部署文档、ADR、Agent 协作约定
```

## 快速开始

从干净检出启动数据库、后端和前端，见 [docs/deployment.md](./docs/deployment.md)。

## 统一检查

```bash
# 后端（编译 + 测试 + 打包；集成测试使用真实 PostgreSQL 容器）
mvn -B verify                      # 在 backend/ 下执行

# 前端（格式 + lint + 类型 + 边界 + 测试 + 构建）
npm run check                      # 在 frontend/ 下执行

# Electron Worker（Worker 与演示应用语法检查；行为由后端集成测试覆盖）
npm run check                      # 在 workers/electron/ 下执行
```

三部分检查同样在 GitHub Actions CI 中执行（`.github/workflows/ci.yml`）。
