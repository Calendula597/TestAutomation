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
  importOpenApi,
  updateCase,
  type CaseRow,
  type StepRow,
} from "../api/testcases";
import { fetchProject } from "../api/projects";
import {
  commitImport,
  previewImport,
  type ImportPreview,
} from "../api/migration";
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
  import: {
    visible: false,
    file: null as File | null,
    titleColumn: 0,
    priorityColumn: 1,
    stepsColumn: 2,
    preview: null as ImportPreview | null,
    previewing: false,
    committing: false,
    error: null as string | null,
  },
  openapi: {
    visible: false,
    definition: "",
    submitting: false,
    created: null as number | null,
    error: null as string | null,
  },
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

function onImportFileChange(file: File | null): void {
  state.import.file = file;
}

async function runPreview(): Promise<void> {
  if (!state.import.file) {
    ElMessage.warning("请选择 CSV 或 .xlsx 文件");
    return;
  }
  state.import.previewing = true;
  state.import.error = null;
  try {
    state.import.preview = await previewImport(
      projectId.value,
      state.import.file,
      {
        titleColumn: state.import.titleColumn,
        priorityColumn: state.import.priorityColumn,
        stepsColumn: state.import.stepsColumn,
      },
    );
  } catch (error) {
    state.import.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.import.previewing = false;
  }
}

async function runCommit(): Promise<void> {
  if (!state.import.preview) {
    return;
  }
  const validRows = state.import.preview.rows
    .filter((r) => r.errors.length === 0)
    .map((r) => ({ title: r.title, priority: r.priority, steps: r.steps }));
  state.import.committing = true;
  state.import.error = null;
  try {
    const result = await commitImport(projectId.value, validRows);
    ElMessage.success(`已导入 ${result.created} 条草稿`);
    state.import.visible = false;
    state.import.preview = null;
    state.import.file = null;
    await load();
  } catch (error) {
    state.import.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.import.committing = false;
  }
}

function exportCases(format: string): void {
  // Same-origin GET downloads carry the session cookie automatically.
  window.location.href = `/api/v1/projects/${projectId.value}/testcases/export?format=${format}`;
}

/** OpenAPI import creates drafts only; publishing stays with the owner. */
async function runOpenApiImport(): Promise<void> {
  if (!state.openapi.definition.trim()) {
    return;
  }
  state.openapi.submitting = true;
  state.openapi.error = null;
  try {
    const result = await importOpenApi(
      projectId.value,
      state.openapi.definition,
    );
    state.openapi.created = result.created;
    state.openapi.definition = "";
    await load();
  } catch (error) {
    state.openapi.error =
      error instanceof ApiError ? error.message : String(error);
  } finally {
    state.openapi.submitting = false;
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
          <el-button
            size="small"
            data-testid="import-open"
            @click="state.import.visible = true"
          >
            导入
          </el-button>
          <el-button
            size="small"
            data-testid="openapi-open"
            @click="state.openapi.visible = true"
          >
            OpenAPI 导入
          </el-button>
          <el-dropdown size="small" @command="exportCases">
            <el-button size="small" data-testid="export-open">导出</el-button>
            <template #dropdown>
              <el-dropdown-item command="csv">导出 CSV</el-dropdown-item>
              <el-dropdown-item command="xlsx">导出 Excel</el-dropdown-item>
            </template>
          </el-dropdown>
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

    <el-dialog
      v-model="state.import.visible"
      title="导入用例（Excel/CSV）"
      width="720px"
    >
      <el-form label-position="top">
        <el-form-item
          label="文件（UTF-8 CSV 或 .xlsx，≤5MB、≤2000 行，首行为表头）"
        >
          <input
            type="file"
            accept=".csv,.xlsx"
            data-testid="import-file"
            @change="
              (e: Event) =>
                onImportFileChange(
                  (e.target as HTMLInputElement).files?.[0] ?? null,
                )
            "
          />
        </el-form-item>
        <el-form-item label="列映射（0 起始列号）">
          <div class="mapping-row">
            <el-input-number
              v-model="state.import.titleColumn"
              :min="0"
              data-testid="import-title-col"
              placeholder="标题列"
            />
            <el-input-number
              v-model="state.import.priorityColumn"
              :min="0"
              data-testid="import-priority-col"
              placeholder="优先级列"
            />
            <el-input-number
              v-model="state.import.stepsColumn"
              :min="0"
              data-testid="import-steps-col"
              placeholder="步骤列"
            />
          </div>
          <div class="mapping-hint">步骤列每行一条「动作 :: 预期」。</div>
        </el-form-item>
        <el-alert
          v-if="state.import.error"
          type="error"
          :closable="false"
          show-icon
          :title="state.import.error"
        />
      </el-form>

      <template v-if="state.import.preview">
        <el-alert
          :type="
            state.import.preview.rows.every((r) => r.errors.length === 0)
              ? 'success'
              : 'warning'
          "
          :closable="false"
          show-icon
          :title="`共 ${state.import.preview.totalRows} 行，可导入 ${state.import.preview.rows.filter((r) => r.errors.length === 0).length} 行`"
          data-testid="import-summary"
        />
        <el-table
          :data="state.import.preview.rows"
          size="small"
          max-height="320"
          data-testid="import-preview-table"
        >
          <el-table-column label="行号" width="70">
            <template #default="{ row }">{{ row.rowNumber + 1 }}</template>
          </el-table-column>
          <el-table-column prop="title" label="标题" />
          <el-table-column label="错误" width="240">
            <template #default="{ row }">
              <span v-if="row.errors.length === 0" class="ok-text">通过</span>
              <span v-else class="error-text">{{ row.errors.join("；") }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template #footer>
        <el-button @click="state.import.visible = false">取消</el-button>
        <el-button
          :loading="state.import.previewing"
          data-testid="import-preview"
          @click="runPreview"
        >
          预览
        </el-button>
        <el-button
          type="primary"
          :disabled="!state.import.preview"
          :loading="state.import.committing"
          data-testid="import-commit"
          @click="runCommit"
        >
          确认导入草稿
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="state.openapi.visible"
      title="从 OpenAPI 导入草稿"
      width="720px"
    >
      <el-alert
        v-if="state.openapi.created !== null"
        type="success"
        :closable="false"
        show-icon
        :title="`已创建 ${state.openapi.created} 个草稿用例，等待审核发布`"
        data-testid="openapi-result"
      />
      <el-alert
        v-if="state.openapi.error"
        type="error"
        :closable="false"
        show-icon
        :title="state.openapi.error"
      />
      <el-form label-position="top">
        <el-form-item label="OpenAPI 3.x 定义（JSON）">
          <el-input
            v-model="state.openapi.definition"
            type="textarea"
            :rows="12"
            placeholder='{"openapi":"3.0.3","info":{...},"paths":{...}}'
            data-testid="openapi-definition"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="state.openapi.visible = false">关闭</el-button>
        <el-button
          type="primary"
          :loading="state.openapi.submitting"
          :disabled="!state.openapi.definition.trim()"
          data-testid="openapi-submit"
          @click="runOpenApiImport"
        >
          导入为草稿
        </el-button>
      </template>
    </el-dialog>
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

.mapping-row {
  display: flex;
  gap: 8px;
}

.mapping-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ok-text {
  color: var(--el-color-success);
}

.error-text {
  color: var(--el-color-danger);
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
