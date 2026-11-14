<script setup lang="ts">
import { onMounted, reactive, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  createPlan,
  fetchPlan,
  fetchPlans,
  fetchPublishedVersions,
  fetchRuns,
  startRun,
  type PlanDetail,
  type PlanRow,
  type PublishedVersionRow,
} from "../api/plans";
import { fetchProjects, type ProjectRow } from "../api/projects";
import { ApiError } from "../api/http";

const route = useRoute();
const router = useRouter();

const state = reactive({
  projects: [] as ProjectRow[],
  projectId: null as number | null,
  plans: [] as PlanRow[],
  detail: null as PlanDetail | null,
  runs: [] as { id: number; status: string; executedBy: string }[],
  published: [] as PublishedVersionRow[],
  loading: false,
  error: null as string | null,
  form: {
    visible: false,
    name: "",
    selectedVersions: [] as number[],
    submitting: false,
  },
});

async function loadProjects(): Promise<void> {
  try {
    state.projects = await fetchProjects();
    if (state.projects.length > 0 && state.projectId === null) {
      state.projectId = state.projects[0].id;
    }
  } catch (error) {
    state.error = error instanceof Error ? error.message : String(error);
  }
}

async function loadPlans(): Promise<void> {
  if (state.projectId === null) {
    return;
  }
  state.loading = true;
  state.error = null;
  try {
    state.plans = await fetchPlans(state.projectId);
    state.detail = null;
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function openPlan(planId: number): Promise<void> {
  if (state.projectId === null) {
    return;
  }
  state.error = null;
  try {
    state.detail = await fetchPlan(state.projectId, planId);
    state.runs = await fetchRuns(state.projectId, planId);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

async function loadPublished(): Promise<void> {
  if (state.projectId === null) {
    return;
  }
  try {
    state.published = await fetchPublishedVersions(state.projectId);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

async function submitCreate(): Promise<void> {
  if (state.projectId === null) {
    return;
  }
  state.form.submitting = true;
  state.error = null;
  try {
    await createPlan(state.projectId, {
      name: state.form.name,
      caseVersionIds: state.form.selectedVersions,
    });
    state.form.visible = false;
    state.form.name = "";
    state.form.selectedVersions = [];
    await loadPlans();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.form.submitting = false;
  }
}

async function beginRun(planId: number): Promise<void> {
  if (state.projectId === null) {
    return;
  }
  state.error = null;
  try {
    const { runId } = await startRun(state.projectId, planId);
    await router.push(
      `/projects/${state.projectId}/plans/${planId}/runs/${runId}`,
    );
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

function openRun(planId: number, runId: number): void {
  if (state.projectId === null) {
    return;
  }
  router.push(`/projects/${state.projectId}/plans/${planId}/runs/${runId}`);
}

onMounted(async () => {
  await loadProjects();
  await loadPlans();
});
watch(
  () => state.projectId,
  () => {
    loadPlans();
    loadPublished();
  },
);
watch(
  () => route.params.id,
  () => {
    state.projectId = Number(route.params.id) || null;
  },
);
</script>

<template>
  <el-card class="plans-card">
    <template #header>
      <div class="card-header">
        <span>手工测试计划</span>
        <div class="toolbar">
          <el-select
            v-model="state.projectId"
            size="small"
            style="width: 180px"
            data-testid="plan-project-select"
          >
            <el-option
              v-for="p in state.projects"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
          <el-button
            size="small"
            type="primary"
            :disabled="state.projects.length === 0"
            data-testid="plan-create"
            @click="
              state.form.visible = true;
              loadPublished();
            "
          >
            新建计划
          </el-button>
          <el-button size="small" :loading="state.loading" @click="loadPlans"
            >刷新</el-button
          >
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
    <el-empty
      v-else-if="!state.loading && state.plans.length === 0"
      description="尚无测试计划"
    />

    <el-table
      v-else
      v-loading="state.loading"
      :data="state.plans"
      data-testid="plans-table"
    >
      <el-table-column prop="name" label="计划名称">
        <template #default="{ row }">
          <el-link
            type="primary"
            data-testid="plan-open"
            @click="openPlan(row.id)"
          >
            {{ row.name }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" />
    </el-table>

    <el-dialog v-model="state.form.visible" title="新建测试计划" width="560px">
      <el-alert
        v-if="state.published.length === 0"
        type="info"
        :closable="false"
        title="请先在用例中心发布 READY 版本，再创建计划。"
      />
      <el-form label-position="top" @submit.prevent="submitCreate">
        <el-form-item label="计划名称">
          <el-input
            v-model="state.form.name"
            data-testid="plan-name"
            maxlength="100"
          />
        </el-form-item>
        <el-form-item label="用例版本（按选择顺序执行，仅 READY 版本）">
          <el-select
            v-model="state.form.selectedVersions"
            multiple
            style="width: 100%"
            data-testid="plan-versions"
          >
            <el-option
              v-for="v in state.published"
              :key="v.versionId"
              :label="`${v.caseTitle}（v${v.version}）`"
              :value="v.versionId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="state.form.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="state.form.submitting"
          data-testid="plan-submit"
          @click="submitCreate"
        >
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="!!state.detail"
      :title="state.detail?.name ?? '计划详情'"
      width="640px"
      @close="state.detail = null"
    >
      <el-table
        v-if="state.detail"
        :data="state.detail.items"
        size="small"
        data-testid="plan-items-table"
      >
        <el-table-column label="顺序" width="70">
          <template #default="{ row }">{{ row.orderIndex }}</template>
        </el-table-column>
        <el-table-column prop="caseTitle" label="用例" />
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.caseVersion }}</template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px">
        <el-button
          type="primary"
          data-testid="run-start"
          @click="state.detail && beginRun(state.detail.id)"
        >
          开始执行
        </el-button>
      </div>
      <h5 class="section-title">历史运行</h5>
      <el-table :data="state.runs" size="small" data-testid="runs-table">
        <el-table-column prop="id" label="运行 ID" width="90" />
        <el-table-column prop="status" label="状态" width="130" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-link
              v-if="state.detail"
              type="primary"
              @click="openRun(state.detail.id, row.id)"
            >
              查看
            </el-link>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.plans-card {
  max-width: 860px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}

.section-title {
  margin: 16px 0 8px;
}
</style>
