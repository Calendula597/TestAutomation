<script setup lang="ts">
import { onMounted, reactive } from "vue";
import { changeUserStatus, fetchUsers, type UserRow } from "../api/auth";
import { ApiError } from "../api/http";

const state = reactive({
  users: [] as UserRow[],
  error: null as string | null,
  loading: false,
  busyId: null as number | null,
});

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.users = await fetchUsers();
  } catch (error) {
    state.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function toggle(user: UserRow): Promise<void> {
  state.busyId = user.id;
  state.error = null;
  try {
    const updated = await changeUserStatus(
      user.id,
      user.status === "ACTIVE" ? "DISABLED" : "ACTIVE",
    );
    const index = state.users.findIndex((u) => u.id === updated.id);
    if (index >= 0) {
      state.users[index] = updated;
    }
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.busyId = null;
  }
}

onMounted(load);
</script>

<template>
  <el-card class="users-card">
    <template #header>
      <div class="card-header">
        <span>用户管理</span>
        <el-button :loading="state.loading" @click="load">刷新</el-button>
      </div>
    </template>

    <el-alert
      v-if="state.error"
      type="error"
      :closable="false"
      show-icon
      :title="state.error"
    />

    <el-table
      v-loading="state.loading"
      :data="state.users"
      data-testid="users-table"
    >
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="role" label="角色" width="100" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'">
            {{ row.status === "ACTIVE" ? "启用" : "禁用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button
            size="small"
            :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
            :loading="state.busyId === row.id"
            :data-testid="`user-toggle-${row.username}`"
            @click="toggle(row)"
          >
            {{ row.status === "ACTIVE" ? "禁用" : "启用" }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.users-card {
  max-width: 720px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
