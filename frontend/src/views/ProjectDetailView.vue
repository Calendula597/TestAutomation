<script setup lang="ts">
import { computed, onMounted, reactive, watch } from "vue";
import { useRoute } from "vue-router";
import {
  addMember,
  changeMemberRole,
  fetchMembers,
  fetchProject,
  removeMember,
  type MemberRow,
  type ProjectRow,
} from "../api/projects";
import { ApiError } from "../api/http";

const route = useRoute();
const projectId = computed(() => Number(route.params.id));

const ROLE_OPTIONS = [
  { value: "OWNER", label: "项目负责人" },
  { value: "MEMBER", label: "测试成员" },
  { value: "VIEWER", label: "只读成员" },
];

const state = reactive({
  project: null as ProjectRow | null,
  members: [] as MemberRow[],
  loading: false,
  error: null as string | null,
  addUsername: "",
  addRole: "VIEWER" as "OWNER" | "MEMBER" | "VIEWER",
  busyUserId: null as number | null,
});

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.project = await fetchProject(projectId.value);
    state.members = await fetchMembers(projectId.value);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function submitAdd(): Promise<void> {
  state.error = null;
  try {
    await addMember(projectId.value, state.addUsername, state.addRole);
    state.addUsername = "";
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

async function changeRole(
  member: MemberRow,
  role: "OWNER" | "MEMBER" | "VIEWER",
): Promise<void> {
  state.error = null;
  state.busyUserId = member.userId;
  try {
    await changeMemberRole(projectId.value, member.userId, role);
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.busyUserId = null;
  }
}

async function remove(member: MemberRow): Promise<void> {
  state.error = null;
  state.busyUserId = member.userId;
  try {
    await removeMember(projectId.value, member.userId);
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.busyUserId = null;
  }
}

onMounted(load);
watch(projectId, load);
</script>

<template>
  <el-card class="detail-card">
    <template #header>
      <div class="card-header">
        <span>
          {{ state.project?.name ?? "项目详情" }}
          <el-tag
            v-if="state.project"
            :type="state.project.status === 'ACTIVE' ? 'success' : 'info'"
            class="status-tag"
          >
            {{ state.project.status === "ACTIVE" ? "进行中" : "已归档" }}
          </el-tag>
        </span>
        <div>
          <router-link
            :to="`/projects/${projectId}/requirements`"
            class="entry-link"
            >需求</router-link
          >
          <router-link
            :to="`/projects/${projectId}/testcases`"
            class="entry-link"
            >功能用例</router-link
          >
          <router-link :to="`/projects/${projectId}/plans`" class="entry-link"
            >测试计划</router-link
          >
          <router-link
            :to="`/projects/${projectId}/environments`"
            class="entry-link"
            >环境配置</router-link
          >
          <router-link
            :to="`/projects/${projectId}/executions`"
            class="entry-link"
            >执行记录</router-link
          >
          <router-link
            :to="`/projects/${projectId}/coverage`"
            class="entry-link"
            >覆盖矩阵</router-link
          >
          <el-button :loading="state.loading" @click="load">刷新</el-button>
        </div>
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
      :data="state.members"
      data-testid="members-table"
    >
      <el-table-column prop="username" label="用户名" />
      <el-table-column label="角色" width="220">
        <template #default="{ row }">
          <el-select
            :model-value="row.role"
            size="small"
            :disabled="state.busyUserId === row.userId"
            :data-testid="`member-role-${row.username}`"
            @change="
              (role: string) =>
                changeRole(row, role as 'OWNER' | 'MEMBER' | 'VIEWER')
            "
          >
            <el-option
              v-for="option in ROLE_OPTIONS"
              :key="option.value"
              :value="option.value"
              :label="option.label"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button
            size="small"
            type="danger"
            :loading="state.busyUserId === row.userId"
            :data-testid="`member-remove-${row.username}`"
            @click="remove(row)"
          >
            移除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-form inline class="add-member-form" @submit.prevent="submitAdd">
      <el-form-item label="添加成员">
        <el-input
          v-model="state.addUsername"
          placeholder="用户名"
          data-testid="member-add-username"
        />
      </el-form-item>
      <el-form-item label="角色">
        <el-select
          v-model="state.addRole"
          data-testid="member-add-role"
          style="width: 140px"
        >
          <el-option
            v-for="option in ROLE_OPTIONS"
            :key="option.value"
            :value="option.value"
            :label="option.label"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          data-testid="member-add-submit"
          @click="submitAdd"
          >添加</el-button
        >
      </el-form-item>
    </el-form>
  </el-card>
</template>

<style scoped>
.detail-card {
  max-width: 860px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.status-tag {
  margin-left: 8px;
}

.entry-link {
  margin-right: 16px;
  color: var(--el-color-primary);
  text-decoration: none;
}

.add-member-form {
  margin-top: 16px;
}
</style>
