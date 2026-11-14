<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import { logout } from "./api/auth";
import { session } from "./stores/session";

const router = useRouter();

const authed = computed(() => session.username !== null);

async function doLogout(): Promise<void> {
  try {
    await logout();
  } finally {
    session.clear();
    await router.push("/login");
  }
}
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <span class="brand">统一测试平台</span>
      <nav class="nav">
        <router-link to="/projects">项目</router-link>
        <router-link to="/knowledge">知识库</router-link>
        <router-link to="/health">系统健康</router-link>
        <router-link v-if="session.isAdmin" to="/users">用户管理</router-link>
        <router-link v-if="session.isAdmin" to="/workers"
          >Worker 管理</router-link
        >
      </nav>
      <div class="spacer" />
      <template v-if="authed">
        <span class="whoami" data-testid="whoami">{{ session.username }}</span>
        <el-button size="small" data-testid="logout" @click="doLogout"
          >退出登录</el-button
        >
      </template>
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

.nav {
  display: flex;
  gap: 16px;
}

.nav a {
  color: var(--el-text-color-primary);
  text-decoration: none;
}

.nav a.router-link-active {
  color: var(--el-color-primary);
}

.spacer {
  flex: 1;
}

.whoami {
  color: var(--el-text-color-secondary);
}
</style>
