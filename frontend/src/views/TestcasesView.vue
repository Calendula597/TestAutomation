<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from "vue";
import { useRoute } from "vue-router";
import { AgGridVue } from "ag-grid-vue3";
import {
  AllCommunityModule,
  ModuleRegistry,
  themeQuartz,
} from "ag-grid-community";
import type {
  CellClassParams,
  CellValueChangedEvent,
  CheckboxSelectionCallbackParams,
  EditableCallbackParams,
  GridApi,
  GridOptions,
  RowSelectionOptions,
} from "ag-grid-community";
import { ElMessage } from "element-plus";
import {
  batchUpdateCases,
  fetchCases,
  updateCase,
  type CaseRow,
  type StepRow,
} from "../api/testcases";
import { fetchProject } from "../api/projects";
import {
  fetchReviewDiff,
  fetchReviewVersions,
  publishCase,
  type DiffResponse,
  type ReviewVersionRow,
} from "../api/review";
import { ApiError } from "../api/http";

ModuleRegistry.registerModules([AllCommunityModule]);

const route = useRoute();
const projectId = computed(() => Number(route.params.id));

const PRIORITY_LABELS: Record<string, string> = {
  HIGH: "高",
  MEDIUM: "中",
  LOW: "低",
};
const PRIORITY_VALUES = ["HIGH", "MEDIUM", "LOW"];

const gridApi = ref<GridApi | null>(null);
const state = reactive({
  cases: [] as CaseRow[],
  loading: false,
  error: null as string | null,
  expanded: null as CaseRow | null,
  steps: [] as StepRow[],
  stepSaving: false,
  selectedIds: [] as number[],
  batchPriority: "HIGH",
  batching: false,
  conflict: null as string | null,
  groupBy: "" as "" | "priority" | "requirementTitle",
  myRole: "" as string,
  reviewCase: null as CaseRow | null,
  reviewDiff: null as DiffResponse | null,
  reviewBasis: "",
  reviewLoading: false,
  reviewVersions: [] as ReviewVersionRow[],
  reviewPublishing: false,
});

const GROUP_LABELS: Record<string, string> = {
  priority: "优先级",
  requirementTitle: "关联需求",
};

/** Custom grouping on AG Grid Community: sort into groups and insert
 * group-header rows; the grid stays fully community-licensed. */
const displayRows = computed(() => {
  if (!state.groupBy) {
    return state.cases;
  }
  const groups = new Map<string, CaseRow[]>();
  for (const item of state.cases) {
    const raw = String(item[state.groupBy] ?? "未设置");
    const key =
      state.groupBy === "priority" ? (PRIORITY_LABELS[raw] ?? raw) : raw;
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key)!.push(item);
  }
  const rows: Array<CaseRow | { headerRow: boolean; label: string }> = [];
  for (const [key, list] of groups) {
    rows.push({
      headerRow: true,
      label: `${GROUP_LABELS[state.groupBy]}：${key}（${list.length}）`,
    });
    rows.push(...list);
  }
  return rows;
});

const rowSelection = ref<RowSelectionOptions>({
  mode: "multiRow",
  checkboxes: true,
  headerCheckbox: true,
});

const columnDefs = ref([
  {
    field: "id",
    headerName: "ID",
    width: 70,
    sortable: true,
    checkboxSelection: (p: CheckboxSelectionCallbackParams) =>
      !p.data?.headerRow,
  },
  {
    field: "title",
    headerName: "标题",
    flex: 2,
    minWidth: 200,
    sortable: true,
    filter: "agTextColumnFilter",
    editable: (p: EditableCallbackParams) => !p.data?.headerRow,
  },
  {
    field: "priority",
    headerName: "优先级",
    width: 110,
    sortable: true,
    filter: true,
    editable: (p: EditableCallbackParams) => !p.data?.headerRow,
    cellEditor: "agSelectCellEditor",
    cellEditorParams: { values: PRIORITY_VALUES },
    valueFormatter: (p: { value: string }) =>
      PRIORITY_LABELS[p.value] ?? p.value,
    cellClass: (p: CellClassParams) =>
      p.value === "HIGH"
        ? "priority-high"
        : p.value === "LOW"
          ? "priority-low"
          : "",
  },
  {
    field: "requirementTitle",
    headerName: "关联需求",
    flex: 1,
    minWidth: 140,
    sortable: true,
    filter: true,
  },
  { field: "status", headerName: "状态", width: 100, filter: true },
  { field: "version", headerName: "版本", width: 90 },
  {
    field: "steps",
    headerName: "步骤",
    width: 120,
    sortable: false,
    filter: false,
    editable: false,
    valueFormatter: (p: { data?: { headerRow?: boolean } }) =>
      p.data?.headerRow ? "" : "编辑步骤",
  },
  {
    field: "review",
    headerName: "审核",
    width: 100,
    sortable: false,
    filter: false,
    editable: false,
    valueFormatter: (p: { data?: { headerRow?: boolean } }) =>
      p.data?.headerRow || state.myRole !== "OWNER" ? "" : "审核发布",
  },
]);

const gridOptions: GridOptions = {
  theme: themeQuartz,
  pagination: true,
  paginationPageSize: 50,
  rowSelection: rowSelection.value,
  enableCellTextSelection: true,
  suppressRowClickSelection: true,
  animateRows: true,
  getRowId: (p) => (p.data.headerRow ? `h-${p.data.label}` : String(p.data.id)),
  onGridReady: (params) => {
    gridApi.value = params.api;
  },
  onSelectionChanged: () => {
    state.selectedIds = (gridApi.value?.getSelectedNodes() ?? [])
      .map((node) => node.data.id as number)
      .filter((id) => Number.isFinite(id));
  },
  onCellClicked: (event) => {
    if (event.data?.headerRow) {
      return;
    }
    if (event.colDef.field === "steps") {
      expandSteps(event.data as CaseRow);
    }
    if (event.colDef.field === "review") {
      openReview(event.data as CaseRow);
    }
  },
  onCellValueChanged: (event: CellValueChangedEvent) => {
    if (event.colDef.field === "title" || event.colDef.field === "priority") {
      saveRow(event.data as CaseRow);
    }
  },
};

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.cases = await fetchCases(projectId.value);
  } catch (error) {
    state.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function saveRow(row: CaseRow): Promise<void> {
  state.conflict = null;
  try {
    const saved = await updateCase(projectId.value, row.id, {
      title: row.title,
      priority: row.priority,
      requirementId: row.requirementId ?? null,
      expectedVersion: row.version,
      steps: row.steps,
    });
    replaceRow(saved);
    ElMessage.success("已保存");
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      state.conflict = error.message;
      ElMessage.error(error.message);
    } else {
      ElMessage.error(error instanceof Error ? error.message : String(error));
    }
    await load();
  }
}

function replaceRow(saved: CaseRow): void {
  const index = state.cases.findIndex((c) => c.id === saved.id);
  if (index >= 0) {
    state.cases.splice(index, 1, saved);
    gridApi.value?.applyTransaction({ update: [saved] });
  }
}

function expandSteps(row: CaseRow): void {
  state.expanded = row;
  state.steps = row.steps.map((s) => ({ ...s }));
  state.conflict = null;
}

async function openReview(row: CaseRow): Promise<void> {
  state.reviewCase = row;
  state.reviewDiff = null;
  state.reviewBasis = "";
  state.reviewVersions = [];
  state.reviewLoading = true;
  state.conflict = null;
  try {
    state.reviewDiff = await fetchReviewDiff(projectId.value, row.id);
    state.reviewVersions = await fetchReviewVersions(projectId.value, row.id);
  } catch (error) {
    state.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.reviewLoading = false;
  }
}

async function submitPublish(): Promise<void> {
  if (!state.reviewCase) {
    return;
  }
  state.reviewPublishing = true;
  state.conflict = null;
  try {
    const result = await publishCase(projectId.value, state.reviewCase.id, {
      basis: state.reviewBasis,
      expectedVersion: state.reviewCase.version,
    });
    ElMessage.success(`已发布 v${result.version}（READY）`);
    state.reviewCase = null;
    await load();
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      state.conflict = error.message;
      ElMessage.error(error.message);
      await load();
    } else {
      ElMessage.error(error instanceof Error ? error.message : String(error));
    }
  } finally {
    state.reviewPublishing = false;
  }
}

async function loadRole(): Promise<void> {
  try {
    const project = await fetchProject(projectId.value);
    state.myRole = project.myRole;
  } catch {
    state.myRole = "";
  }
}

function addStep(): void {
  state.steps.push({ action: "", testData: "", expected: "" });
}

function removeStep(index: number): void {
  state.steps.splice(index, 1);
}

function moveStep(index: number, delta: number): void {
  const target = index + delta;
  if (target < 0 || target >= state.steps.length) {
    return;
  }
  const [row] = state.steps.splice(index, 1);
  state.steps.splice(target, 0, row);
}

async function saveSteps(): Promise<void> {
  if (!state.expanded) {
    return;
  }
  state.stepSaving = true;
  state.conflict = null;
  try {
    const saved = await updateCase(projectId.value, state.expanded.id, {
      title: state.expanded.title,
      priority: state.expanded.priority,
      requirementId: state.expanded.requirementId ?? null,
      expectedVersion: state.expanded.version,
      steps: state.steps,
    });
    replaceRow(saved);
    state.expanded = saved;
    state.steps = saved.steps.map((s) => ({ ...s }));
    ElMessage.success(`已保存为 v${saved.version}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      state.conflict = error.message;
      ElMessage.error(error.message);
      await load();
    } else {
      ElMessage.error(error instanceof Error ? error.message : String(error));
    }
  } finally {
    state.stepSaving = false;
  }
}

async function runBatchUpdate(): Promise<void> {
  if (state.selectedIds.length === 0) {
    ElMessage.warning("请先勾选要修改的用例");
    return;
  }
  state.batching = true;
  state.conflict = null;
  try {
    const { results } = await batchUpdateCases(
      projectId.value,
      state.selectedIds,
      { priority: state.batchPriority },
    );
    const conflicts = results.filter((r) => r.status === 409);
    if (conflicts.length > 0) {
      state.conflict = `${conflicts.length} 行保存冲突（他人已修改），其余已保存；请刷新后重试冲突行`;
      ElMessage.error(state.conflict);
    } else {
      ElMessage.success(`已更新 ${results.length} 行`);
    }
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : String(error));
  } finally {
    state.batching = false;
  }
}

onMounted(() => {
  load();
  loadRole();
});
watch(projectId, () => {
  state.expanded = null;
  state.reviewCase = null;
  load();
  loadRole();
});
onBeforeUnmount(() => {
  gridApi.value?.destroy();
});
</script>

<template>
  <el-card class="cases-card">
    <template #header>
      <div class="card-header">
        <span>功能用例</span>
        <div class="toolbar">
          <el-select
            v-model="state.groupBy"
            size="small"
            style="width: 140px"
            data-testid="group-select"
          >
            <el-option label="不分组" value="" />
            <el-option label="按优先级分组" value="priority" />
            <el-option label="按关联需求分组" value="requirementTitle" />
          </el-select>
          <el-select
            v-model="state.batchPriority"
            size="small"
            style="width: 110px"
          >
            <el-option label="设为高优先级" value="HIGH" />
            <el-option label="设为中优先级" value="MEDIUM" />
            <el-option label="设为低优先级" value="LOW" />
          </el-select>
          <el-button
            size="small"
            type="primary"
            :loading="state.batching"
            data-testid="batch-apply"
            @click="runBatchUpdate"
          >
            批量修改选中行（{{ state.selectedIds.length }}）
          </el-button>
          <el-button size="small" :loading="state.loading" @click="load"
            >刷新</el-button
          >
        </div>
      </div>
    </template>

    <el-alert
      v-if="state.conflict"
      type="warning"
      :closable="true"
      show-icon
      :title="state.conflict"
      data-testid="conflict-alert"
    />
    <el-alert
      v-if="state.error"
      type="error"
      :closable="false"
      show-icon
      :title="state.error"
    />

    <AgGridVue
      class="ag-theme-quartz cases-grid"
      :column-defs="columnDefs"
      :row-data="displayRows"
      :grid-options="gridOptions"
      :loading="state.loading"
    />

    <div v-if="state.expanded" class="steps-panel" data-testid="steps-panel">
      <div class="panel-title">
        <span
          >编辑步骤 — {{ state.expanded.title }}（v{{
            state.expanded.version
          }}）</span
        >
        <el-button size="small" @click="state.expanded = null">收起</el-button>
      </div>

      <el-table :data="state.steps" size="small" data-testid="steps-table">
        <el-table-column label="#" width="50">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="操作步骤">
          <template #default="{ row }">
            <el-input v-model="row.action" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="测试数据">
          <template #default="{ row }">
            <el-input v-model="row.testData" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="预期结果">
          <template #default="{ row }">
            <el-input v-model="row.expected" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ $index }">
            <el-button
              size="small"
              :disabled="$index === 0"
              @click="moveStep($index, -1)"
              >上移</el-button
            >
            <el-button
              size="small"
              :disabled="$index === state.steps.length - 1"
              @click="moveStep($index, 1)"
            >
              下移
            </el-button>
            <el-button size="small" type="danger" @click="removeStep($index)"
              >删除</el-button
            >
          </template>
        </el-table-column>
      </el-table>

      <div class="panel-actions">
        <el-button size="small" @click="addStep">添加步骤</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="state.stepSaving"
          @click="saveSteps"
        >
          保存步骤
        </el-button>
      </div>
    </div>

    <div
      v-if="state.reviewCase"
      class="review-panel"
      data-testid="review-panel"
    >
      <div class="panel-title">
        <span>审核发布 — {{ state.reviewCase.title }}</span>
        <el-button size="small" @click="state.reviewCase = null"
          >收起</el-button
        >
      </div>

      <div v-loading="state.reviewLoading">
        <h5 class="section-title">草稿与上一发布版本对比</h5>
        <div
          v-if="state.reviewDiff"
          class="diff-grid"
          data-testid="review-diff"
        >
          <div class="diff-side">
            <strong
              >上一发布版本（{{
                state.reviewDiff.previousStatus === "NONE" ? "无" : "READY"
              }}）</strong
            >
            <div v-if="state.reviewDiff.previous">
              <div>标题：{{ state.reviewDiff.previous.title }}</div>
              <div>
                优先级：{{
                  PRIORITY_LABELS[state.reviewDiff.previous.priority]
                }}
              </div>
              <div>步骤数：{{ state.reviewDiff.previous.steps.length }}</div>
              <ol class="diff-steps">
                <li
                  v-for="s in state.reviewDiff.previous.steps"
                  :key="s.stepOrder"
                >
                  {{ s.action }} → {{ s.expected }}
                </li>
              </ol>
            </div>
            <div v-else>尚未发布过。</div>
          </div>
          <div class="diff-side">
            <strong>当前草稿</strong>
            <div>标题：{{ state.reviewDiff?.current.title }}</div>
            <div>
              优先级：{{
                PRIORITY_LABELS[state.reviewDiff?.current.priority ?? "MEDIUM"]
              }}
            </div>
            <div>步骤数：{{ state.reviewDiff?.current.steps.length }}</div>
            <ol class="diff-steps">
              <li
                v-for="s in state.reviewDiff?.current.steps ?? []"
                :key="s.stepOrder"
              >
                {{ s.action }} → {{ s.expected }}
              </li>
            </ol>
          </div>
        </div>

        <h5 class="section-title">发布历史</h5>
        <el-table
          :data="state.reviewVersions"
          size="small"
          data-testid="review-versions-table"
        >
          <el-table-column label="版本" width="70">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column prop="publishedBy" label="审核人" width="90" />
          <el-table-column prop="publishedAt" label="审核时间" width="180" />
          <el-table-column prop="publishBasis" label="发布依据" />
        </el-table>

        <div
          v-if="state.myRole === 'OWNER'"
          class="publish-box"
          data-testid="publish-box"
        >
          <el-input
            v-model="state.reviewBasis"
            placeholder="发布依据（必填，说明本次审核结论）"
            data-testid="publish-basis"
          />
          <el-button
            type="primary"
            :loading="state.reviewPublishing"
            data-testid="publish-submit"
            @click="submitPublish"
          >
            发布为 READY 版本
          </el-button>
        </div>
        <el-alert
          v-else
          type="info"
          :closable="false"
          title="仅项目负责人可以发布草稿。"
        />
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.cases-card {
  max-width: 1100px;
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

.cases-grid {
  width: 100%;
  height: 420px;
}

.priority-high {
  color: var(--el-color-danger);
  font-weight: 600;
}

.priority-low {
  color: var(--el-text-color-secondary);
}

.steps-panel {
  margin-top: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  padding: 12px;
}

.review-panel {
  margin-top: 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  padding: 12px;
}

.section-title {
  margin: 12px 0 8px;
}

.diff-grid {
  display: flex;
  gap: 16px;
}

.diff-side {
  flex: 1;
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  padding: 8px;
}

.diff-steps {
  margin: 4px 0 0;
  padding-left: 18px;
}

.publish-box {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}

.panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.panel-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
</style>
