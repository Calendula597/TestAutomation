<script setup lang="ts">
import { onMounted, reactive } from "vue";
import { useRouter } from "vue-router";
import {
  archiveProject,
  createProject,
  fetchProjects,
  type ProjectRow,
} from "../api/projects";
import { ApiError } from "../api/http";

const router = useRouter();

const ROLE_LABELS: Record<string, string> = {
  OWNER: "项目负责人",
  MEMBER: "测试成员",
  VIEWER: "只读成员",
};

const state = reactive({
  projects: [] as ProjectRow[],
  loading: false,
  error: null as string | null,
  creating: false,
  createError: null as string | null,
});

const createForm = reactive({
  visible: false,
  name: "",
  description: "",
});

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.projects = await fetchProjects();
  } catch (error) {
    state.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function submitCreate(): Promise<void> {
  state.createError = null;
  try {
    state.creating = true;
    await createProject(createForm.name, createForm.description);
    createForm.visible = false;
    createForm.name = "";
    createForm.description = "";
    await load();
  } catch (error) {
    state.createError =
      error instanceof ApiError ? error.message : String(error);
  } finally {
    state.creating = false;
  }
}

async function archive(project: ProjectRow): Promise<void> {
  state.error = null;
  try {
    await archiveProject(project.id);
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

onMounted(load);
</script>

<template>
  <el-card class="projects-card">
    <template #header>
      <div class="card-header">
        <span>项目</span>
        <div>
          <el-button :loading="state.loading" @click="load">刷新</el-button>
          <el-button
            type="primary"
            data-testid="project-create"
            @click="createForm.visible = true"
          >
            新建项目
          </el-button>
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
      :data="state.projects"
      data-testid="projects-table"
    >
      <el-table-column prop="name" label="名称">
        <template #default="{ row }">
          <el-link type="primary" @click="router.push(`/projects/${row.id}`)">{{
            row.name
          }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === "ACTIVE" ? "进行中" : "已归档" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="我的角色" width="120">
        <template #default="{ row }">{{
          ROLE_LABELS[row.myRole] ?? row.myRole
        }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button
            v-if="row.myRole === 'OWNER' && row.status === 'ACTIVE'"
            size="small"
            :data-testid="`project-archive-${row.id}`"
            @click="archive(row)"
          >
            归档
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createForm.visible" title="新建项目" width="420px">
      <el-form label-position="top" @submit.prevent="submitCreate">
        <el-form-item label="项目名称">
          <el-input
            v-model="createForm.name"
            data-testid="project-name"
            maxlength="100"
          />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            data-testid="project-description"
          />
        </el-form-item>
        <el-alert
          v-if="state.createError"
          type="error"
          :closable="false"
          show-icon
          :title="state.createError"
          data-testid="project-create-error"
        />
      </el-form>
      <template #footer>
        <el-button @click="createForm.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="state.creating"
          data-testid="project-submit"
          @click="submitCreate"
        >
          创建
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.projects-card {
  max-width: 860px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
