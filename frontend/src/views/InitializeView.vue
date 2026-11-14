<script setup lang="ts">
import { reactive } from "vue";
import { useRouter } from "vue-router";
import { initializeSystem } from "../api/auth";
import { session } from "../stores/session";

const router = useRouter();

const form = reactive({
  username: "",
  password: "",
  confirm: "",
  error: null as string | null,
  loading: false,
});

async function submit(): Promise<void> {
  if (form.password !== form.confirm) {
    form.error = "两次输入的密码不一致";
    return;
  }
  form.loading = true;
  form.error = null;
  try {
    const user = await initializeSystem(form.username, form.password);
    session.setFrom({ username: user.username, role: user.role });
    await router.push("/health");
  } catch (error) {
    form.error = error instanceof Error ? error.message : String(error);
  } finally {
    form.loading = false;
  }
}
</script>

<template>
  <el-card class="auth-card">
    <template #header>
      <span>初始化系统管理员</span>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="首次部署仅允许创建一个管理员账号；此操作只能执行一次。"
    />

    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="管理员用户名">
        <el-input
          v-model="form.username"
          data-testid="init-username"
          autocomplete="username"
        />
      </el-form-item>
      <el-form-item label="密码（不少于 8 位）">
        <el-input
          v-model="form.password"
          type="password"
          data-testid="init-password"
          autocomplete="new-password"
          show-password
        />
      </el-form-item>
      <el-form-item label="确认密码">
        <el-input
          v-model="form.confirm"
          type="password"
          data-testid="init-confirm"
          autocomplete="new-password"
          show-password
        />
      </el-form-item>
      <el-alert
        v-if="form.error"
        type="error"
        :closable="false"
        show-icon
        :title="form.error"
        data-testid="init-error"
      />
      <el-button
        type="primary"
        native-type="submit"
        :loading="form.loading"
        data-testid="init-submit"
      >
        创建管理员并进入系统
      </el-button>
    </el-form>
  </el-card>
</template>

<style scoped>
.auth-card {
  max-width: 420px;
  margin: 48px auto 0;
}
</style>
