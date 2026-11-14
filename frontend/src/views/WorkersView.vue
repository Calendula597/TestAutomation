<script setup lang="ts">
import { computed, onMounted, reactive } from "vue";
import {
  cancelJob,
  fetchQueueJobs,
  fetchWorkerDiagnostics,
  fetchWorkers,
  registerWorker,
  triggerDiagnostic,
  type DiagnosticRow,
  type ExecutorCapability,
  type QueueJobRow,
  type WorkerRegistration,
  type WorkerRow,
} from "../api/workers";
import { ApiError } from "../api/http";

const CAPABILITIES: ExecutorCapability[] = [
  "DIAGNOSTIC",
  "API",
  "WEB",
  "ELECTRON",
  "ANDROID",
  "WINDOWS_NATIVE",
  "PERFORMANCE",
  "WHITEBOX",
];

const STATUS_LABELS: Record<string, string> = {
  ONLINE: "在线",
  OFFLINE: "失联",
  NEVER_SEEN: "未连接",
  QUEUED: "排队中",
  RUNNING: "执行中",
  PASSED: "通过",
  ASSERTION_FAILED: "断言失败",
  ERROR: "执行错误",
  CANCELLED: "已取消",
  TIMED_OUT: "已超时",
};

const state = reactive({
  workers: [] as WorkerRow[],
  jobs: [] as QueueJobRow[],
  loading: false,
  error: null as string | null,
  // Registration form.
  name: "",
  capabilities: [] as ExecutorCapability[],
  version: "",
  maxConcurrency: 1,
  submitting: false,
  issuedToken: null as WorkerRegistration | null,
  // Per-worker diagnostics view.
  diagWorkerId: null as number | null,
  diagnostics: [] as DiagnosticRow[],
  diagLoading: false,
  diagSubmitted: false,
  cancellingJobId: null as number | null,
});

const cancellable = (job: QueueJobRow): boolean =>
  job.status === "QUEUED" || job.status === "RUNNING";

const diagWorkerName = computed(
  () =>
    state.workers.find((worker) => worker.id === state.diagWorkerId)?.name ??
    "",
);

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.workers = await fetchWorkers();
    state.jobs = await fetchQueueJobs();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function submitRegister(): Promise<void> {
  if (!state.name.trim() || state.capabilities.length === 0) {
    return;
  }
  state.submitting = true;
  state.error = null;
  try {
    state.issuedToken = await registerWorker({
      name: state.name.trim(),
      capabilities: [...state.capabilities],
      version: state.version.trim() || undefined,
      maxConcurrency: state.maxConcurrency,
    });
    state.name = "";
    state.capabilities = [];
    state.version = "";
    state.maxConcurrency = 1;
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.submitting = false;
  }
}

async function runDiagnostic(worker: WorkerRow): Promise<void> {
  state.error = null;
  state.diagSubmitted = false;
  try {
    await triggerDiagnostic(worker.id);
    state.diagSubmitted = true;
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

async function showDiagnostics(worker: WorkerRow): Promise<void> {
  state.diagWorkerId = worker.id;
  state.diagLoading = true;
  state.error = null;
  try {
    state.diagnostics = await fetchWorkerDiagnostics(worker.id);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.diagLoading = false;
  }
}

async function cancel(job: QueueJobRow): Promise<void> {
  state.cancellingJobId = job.id;
  state.error = null;
  try {
    await cancelJob(job.id);
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.cancellingJobId = null;
  }
}

onMounted(load);
</script>

<template>
  <el-card class="workers-card">
    <template #header>
      <div class="card-header">
        <span>Worker 管理</span>
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

    <h4>注册 Worker</h4>
    <el-form inline class="register-form" @submit.prevent="submitRegister">
      <el-form-item label="名称">
        <el-input
          v-model="state.name"
          placeholder="Worker 名称"
          data-testid="worker-register-name"
        />
      </el-form-item>
      <el-form-item label="能力">
        <el-checkbox-group v-model="state.capabilities">
          <el-checkbox
            v-for="capability in CAPABILITIES"
            :key="capability"
            :value="capability"
            :data-testid="`cap-${capability}`"
          >
            {{ capability }}
          </el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="版本">
        <el-input
          v-model="state.version"
          placeholder="如 0.1.0"
          data-testid="worker-register-version"
        />
      </el-form-item>
      <el-form-item label="并发上限">
        <el-input-number
          v-model="state.maxConcurrency"
          :min="1"
          :max="64"
          data-testid="worker-register-concurrency"
        />
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          :loading="state.submitting"
          data-testid="worker-register-submit"
          @click="submitRegister"
          >注册</el-button
        >
      </el-form-item>
    </el-form>

    <el-alert
      v-if="state.issuedToken"
      type="warning"
      :closable="false"
      show-icon
      title="Worker 凭据仅显示一次，请立即保存"
    >
      <span class="token-value" data-testid="worker-token">{{
        state.issuedToken.token
      }}</span>
    </el-alert>

    <el-table
      v-loading="state.loading"
      :data="state.workers"
      data-testid="workers-table"
    >
      <el-table-column prop="name" label="名称" />
      <el-table-column label="能力" width="280">
        <template #default="{ row }">
          <el-tag
            v-for="capability in row.capabilities"
            :key="capability"
            size="small"
            class="cap-tag"
            >{{ capability }}</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="90" />
      <el-table-column prop="maxConcurrency" label="并发" width="70" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag
            :type="row.status === 'ONLINE' ? 'success' : 'info'"
            size="small"
            >{{ STATUS_LABELS[row.status] ?? row.status }}</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column label="最近心跳" width="180">
        <template #default="{ row }">
          {{ row.lastHeartbeatAt ?? "—" }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button
            size="small"
            type="primary"
            :data-testid="`worker-run-diag-${row.id}`"
            @click="runDiagnostic(row)"
            >运行诊断</el-button
          >
          <el-button
            size="small"
            :data-testid="`worker-view-diag-${row.id}`"
            @click="showDiagnostics(row)"
            >诊断结果</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-alert
      v-if="state.diagSubmitted"
      type="success"
      :closable="false"
      show-icon
      title="诊断任务已提交，等待 Worker 领取执行"
    />

    <template v-if="state.diagWorkerId !== null">
      <h4>{{ diagWorkerName }} 的诊断结果</h4>
      <div v-loading="state.diagLoading" data-testid="diag-list">
        <p v-if="state.diagnostics.length === 0" class="empty-hint">
          尚无诊断记录
        </p>
        <div
          v-for="diagnostic in state.diagnostics"
          :key="diagnostic.id"
          class="diag-block"
        >
          <div class="diag-meta">
            <el-tag
              :type="diagnostic.status === 'PASSED' ? 'success' : 'danger'"
              size="small"
              >{{ diagnostic.status }}</el-tag
            >
            <span class="diag-time">{{ diagnostic.createdAt }}</span>
          </div>
          <div
            v-for="check in diagnostic.checks"
            :key="check.name"
            class="check-line"
          >
            <el-tag :type="check.ok ? 'success' : 'danger'" size="small">
              {{ check.ok ? "正常" : "缺失" }}
            </el-tag>
            <span class="check-name">{{ check.name }}</span>
            <span class="check-detail">{{ check.detail }}</span>
          </div>
        </div>
      </div>
    </template>

    <h4>执行队列</h4>
    <el-table :data="state.jobs" data-testid="jobs-table">
      <el-table-column prop="id" label="任务" width="80" />
      <el-table-column prop="jobType" label="类型" width="120" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag size="small">{{
            STATUS_LABELS[row.status] ?? row.status
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ row.createdAt }}</template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button
            v-if="cancellable(row)"
            size="small"
            type="danger"
            :loading="state.cancellingJobId === row.id"
            :data-testid="`job-cancel-${row.id}`"
            @click="cancel(row)"
            >取消</el-button
          >
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.workers-card {
  max-width: 1080px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.register-form :deep(.el-form-item) {
  margin-right: 16px;
}

.cap-tag {
  margin-right: 4px;
}

.token-value {
  font-family: monospace;
  user-select: all;
}

.diag-block {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 8px;
}

.diag-meta {
  display: flex;
  gap: 12px;
  margin-bottom: 6px;
}

.diag-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.check-line {
  display: flex;
  gap: 10px;
  margin-bottom: 4px;
}

.check-name {
  min-width: 120px;
}

.check-detail {
  color: var(--el-text-color-regular);
}

.empty-hint {
  color: var(--el-text-color-secondary);
}
</style>
