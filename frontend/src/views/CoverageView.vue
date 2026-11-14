<script setup lang="ts">
import { onMounted, reactive, watch } from "vue";
import {
  fetchCoverage,
  COVERAGE_STATUS_LABELS,
  COVERAGE_STATUS_TYPES,
  RUN_STATUS_LABELS,
  type CoverageRow,
} from "../api/coverage";
import { fetchProjects, type ProjectRow } from "../api/projects";
import { ApiError } from "../api/http";

const state = reactive({
  projects: [] as ProjectRow[],
  projectId: null as number | null,
  rows: [] as CoverageRow[],
  loading: false,
  error: null as string | null,
  expandedId: null as number | null,
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

async function load(): Promise<void> {
  if (state.projectId === null) return;
  state.loading = true;
  state.error = null;
  try {
    state.rows = await fetchCoverage(state.projectId);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

function toggle(row: CoverageRow): void {
  state.expandedId =
    state.expandedId === row.requirementId ? null : row.requirementId;
}

onMounted(async () => {
  await loadProjects();
  await load();
});
watch(
  () => state.projectId,
  () => load(),
);
</script>

<template>
  <el-card class="coverage-card">
    <template #header>
      <div class="card-header">
        <span>需求覆盖矩阵</span>
        <div class="toolbar">
          <el-select
            v-model="state.projectId"
            size="small"
            style="width: 180px"
            data-testid="coverage-project"
          >
            <el-option
              v-for="p in state.projects"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
          <el-button size="small" :loading="state.loading" @click="load"
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
      v-else-if="!state.loading && state.rows.length === 0"
      description="尚无需求"
    />

    <el-table
      v-else
      v-loading="state.loading"
      :data="state.rows"
      data-testid="coverage-table"
    >
      <el-table-column label="需求（当前版本）">
        <template #default="{ row }">
          {{ row.title }}
          <span class="version-tag">v{{ row.currentVersion }}</span>
        </template>
      </el-table-column>
      <el-table-column label="覆盖状态" width="130">
        <template #default="{ row }">
          <el-tag
            :type="
              COVERAGE_STATUS_TYPES[
                row.status as keyof typeof COVERAGE_STATUS_TYPES
              ]
            "
          >
            {{
              COVERAGE_STATUS_LABELS[
                row.status as keyof typeof COVERAGE_STATUS_LABELS
              ] ?? row.status
            }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="覆盖点" width="90">
        <template #default="{ row }">{{
          row.confirmedCoveragePoints
        }}</template>
      </el-table-column>
      <el-table-column label="待审草稿" width="90">
        <template #default="{ row }">{{ row.pendingDrafts }}</template>
      </el-table-column>
      <el-table-column label="追溯" width="110">
        <template #default="{ row }">
          <el-button
            size="small"
            :data-testid="`coverage-trace-${row.requirementId}`"
            @click="toggle(row)"
          >
            {{ state.expandedId === row.requirementId ? "收起" : "追溯" }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div
      v-for="row in state.rows.filter(
        (r) => r.requirementId === state.expandedId,
      )"
      :key="row.requirementId"
      class="trace-panel"
      :data-testid="`trace-panel-${row.requirementId}`"
    >
      <h5 class="section-title">用例版本与执行追溯 — {{ row.title }}</h5>
      <el-table
        v-if="row.caseVersions.length > 0"
        :data="row.caseVersions"
        size="small"
      >
        <el-table-column label="用例" prop="title" />
        <el-table-column label="发布版本" width="90">
          <template #default="{ row: v }">v{{ v.version }}</template>
        </el-table-column>
        <el-table-column label="最新执行" width="110">
          <template #default="{ row: v }">
            {{ RUN_STATUS_LABELS[v.latestRunStatus] ?? v.latestRunStatus }}
          </template>
        </el-table-column>
        <el-table-column prop="lastExecutedAt" label="执行时间" width="200" />
      </el-table>
      <el-empty v-else description="该需求暂无关联用例版本" :image-size="60" />
    </div>
  </el-card>
</template>

<style scoped>
.coverage-card {
  max-width: 900px;
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

.version-tag {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.trace-panel {
  margin-top: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  padding: 12px;
}

.section-title {
  margin: 0 0 8px;
}
</style>
