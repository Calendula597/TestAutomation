<script setup lang="ts">
// Locale is applied globally in main.ts.
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <span class="brand">统一测试平台</span>
      <nav class="nav">
        <router-link to="/health">系统健康</router-link>
      </nav>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}

.header {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.brand {
  font-size: 18px;
  font-weight: 600;
}

.nav a {
  color: var(--el-text-color-primary);
  text-decoration: none;
  margin-right: 16px;
}

.nav a.router-link-active {
  color: var(--el-color-primary);
}
</style>
