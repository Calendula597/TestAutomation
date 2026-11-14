<script setup lang="ts">
import { reactive } from "vue";
import { useRouter } from "vue-router";
import { login } from "../api/auth";
import { session } from "../stores/session";

const router = useRouter();

const form = reactive({
  username: "",
  password: "",
  error: null as string | null,
  loading: false,
});

async function submit(): Promise<void> {
  form.loading = true;
  form.error = null;
  try {
    const me = await login(form.username, form.password);
    session.setFrom(me);
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
      <span>登录</span>
    </template>

    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="用户名">
        <el-input
          v-model="form.username"
          name="username"
          data-testid="login-username"
          autocomplete="username"
        />
      </el-form-item>
      <el-form-item label="密码">
        <el-input
          v-model="form.password"
          name="password"
          type="password"
          data-testid="login-password"
          autocomplete="current-password"
          show-password
        />
      </el-form-item>
      <el-alert
        v-if="form.error"
        type="error"
        :closable="false"
        show-icon
        :title="form.error"
        data-testid="login-error"
      />
      <el-button
        type="primary"
        native-type="submit"
        :loading="form.loading"
        data-testid="login-submit"
      >
        登录
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
