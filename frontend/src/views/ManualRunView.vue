<script setup lang="ts">
import { computed, onMounted, reactive, watch } from "vue";
import { useRoute } from "vue-router";
import {
  completeRun,
  fetchRun,
  fetchRunReport,
  recordRunItem,
  type RunDetail,
  type RunItemRow,
} from "../api/plans";
import { ApiError } from "../api/http";

const route = useRoute();
const projectId = computed(() => Number(route.params.id));
const planId = computed(() => Number(route.params.planId));
const runId = computed(() => Number(route.params.runId));

const STATUS_OPTIONS = [
  { value: "PASSED", label: "通过", type: "success" },
  { value: "FAILED", label: "失败", type: "danger" },
  { value: "BLOCKED", label: "阻塞", type: "warning" },
  { value: "SKIPPED", label: "跳过", type: "info" },
] as const;

const STATUS_LABELS: Record<string, string> = {
  PENDING: "待执行",
  PASSED: "通过",
  FAILED: "失败",
  BLOCKED: "阻塞",
  SKIPPED: "跳过",
};

const state = reactive({
  run: null as RunDetail | null,
  report: null as Awaited<ReturnType<typeof fetchRunReport>> | null,
  loading: false,
  error: null as string | null,
  currentIndex: 0,
  recording: false,
});

const currentItem = computed<RunItemRow | null>(() => {
  if (!state.run || state.run.items.length === 0) {
    return null;
  }
  return state.run.items[
    Math.min(state.currentIndex, state.run.items.length - 1)
  ];
});

const edit = reactive({
  status: "" as string,
  actualResult: "",
  note: "",
  evidenceUrl: "",
});

function selectItem(index: number): void {
  state.currentIndex = index;
  const item = currentItem.value;
  if (item) {
    edit.status = item.status === "PENDING" ? "" : item.status;
    edit.actualResult = item.actualResult ?? "";
    edit.note = item.note ?? "";
    edit.evidenceUrl = item.evidenceUrl ?? "";
  }
}

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.run = await fetchRun(projectId.value, planId.value, runId.value);
    await loadReport();
    selectItem(state.currentIndex);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function loadReport(): Promise<void> {
  state.report = await fetchRunReport(
    projectId.value,
    planId.value,
    runId.value,
  );
}

async function saveRecord(): Promise<void> {
  const item = currentItem.value;
  if (!item || !edit.status) {
    return;
  }
  state.recording = true;
  state.error = null;
  try {
    state.run = await recordRunItem(
      projectId.value,
      planId.value,
      runId.value,
      item.id,
      {
        status: edit.status,
        actualResult: edit.actualResult,
        note: edit.note,
        evidenceUrl: edit.evidenceUrl,
      },
    );
    await loadReport();
    ElMessageSuccess("已记录");
    if (state.currentIndex < state.run.items.length - 1) {
      selectItem(state.currentIndex + 1);
    }
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.recording = false;
  }
}

async function finishRun(): Promise<void> {
  state.error = null;
  try {
    state.run = await completeRun(projectId.value, planId.value, runId.value);
    await loadReport();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

function ElMessageSuccess(message: string): void {
  // Element Plus message is imported lazily to keep the panel lean.
  import("element-plus").then((m) => m.ElMessage.success(message));
}

onMounted(load);
watch([planId, runId], load);
</script>

<template>
  <el-card class="run-card">
    <template #header>
      <div class="card-header">
        <span>
          执行 — {{ state.run?.planName ?? "" }}
          <el-tag
            v-if="state.run"
            :type="state.run.status === 'COMPLETED' ? 'success' : 'primary'"
          >
            {{ state.run.status === "COMPLETED" ? "已完成" : "进行中" }}
          </el-tag>
        </span>
        <div>
          <el-button
            v-if="state.run && state.run.status !== 'COMPLETED'"
            data-testid="run-complete"
            @click="finishRun"
          >
            完成运行
          </el-button>
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

    <div v-if="state.report" class="summary-row" data-testid="run-summary">
      <el-tag type="success">通过 {{ state.report.summary.passed }}</el-tag>
      <el-tag type="danger">失败 {{ state.report.summary.failed }}</el-tag>
      <el-tag type="warning">阻塞 {{ state.report.summary.blocked }}</el-tag>
      <el-tag type="info">跳过 {{ state.report.summary.skipped }}</el-tag>
      <el-tag>待执行 {{ state.report.summary.pending }}</el-tag>
    </div>

    <div v-if="state.run" class="run-layout">
      <div class="item-list">
        <div
          v-for="(item, index) in state.run.items"
          :key="item.id"
          class="item-row"
          :class="{ active: index === state.currentIndex }"
          :data-testid="`run-item-${index}`"
          @click="selectItem(index)"
        >
          <span class="item-order">{{ item.orderIndex }}</span>
          <span class="item-title">{{ item.caseTitle }}</span>
          <el-tag
            size="small"
            :type="
              item.status === 'PASSED'
                ? 'success'
                : item.status === 'FAILED'
                  ? 'danger'
                  : 'info'
            "
          >
            {{ STATUS_LABELS[item.status] }}
          </el-tag>
        </div>
      </div>

      <div v-if="currentItem" class="item-editor">
        <h4>{{ currentItem.caseTitle }}（v{{ currentItem.caseVersion }}）</h4>
        <ol class="steps-display">
          <li v-for="s in currentItem.steps" :key="s.stepOrder">
            {{ s.action }}
            <span v-if="s.testData" class="step-data"
              >［{{ s.testData }}］</span
            >
            → 预期：{{ s.expected }}
          </li>
        </ol>

        <el-form label-position="top" class="record-form">
          <el-form-item label="结果">
            <el-radio-group v-model="edit.status" data-testid="record-status">
              <el-radio
                v-for="option in STATUS_OPTIONS"
                :key="option.value"
                :value="option.value"
                :data-testid="`record-status-${option.value}`"
              >
                {{ option.label }}
              </el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="实际结果">
            <el-input
              v-model="edit.actualResult"
              type="textarea"
              :rows="2"
              data-testid="record-actual"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input
              v-model="edit.note"
              type="textarea"
              :rows="2"
              data-testid="record-note"
            />
          </el-form-item>
          <el-form-item label="证据（链接或说明）">
            <el-input
              v-model="edit.evidenceUrl"
              data-testid="record-evidence"
            />
          </el-form-item>
          <el-button
            type="primary"
            :loading="state.recording"
            :disabled="state.run?.status === 'COMPLETED' || !edit.status"
            data-testid="record-save"
            @click="saveRecord"
          >
            保存记录
          </el-button>
        </el-form>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.run-card {
  max-width: 1000px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.summary-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.run-layout {
  display: flex;
  gap: 16px;
}

.item-list {
  width: 260px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  cursor: pointer;
}

.item-row.active {
  background: var(--el-color-primary-light-9);
}

.item-order {
  width: 24px;
  color: var(--el-text-color-secondary);
}

.item-title {
  flex: 1;
}

.item-editor {
  flex: 1;
}

.steps-display {
  padding-left: 20px;
}

.step-data {
  color: var(--el-text-color-secondary);
}
</style>
