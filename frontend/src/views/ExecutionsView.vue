<script setup lang="ts">
import { computed, onMounted, reactive, watch } from "vue";
import { useRoute } from "vue-router";
import { fetchCases, type CaseRow } from "../api/testcases";
import {
  fetchEnvironments,
  type EnvironmentSummary,
} from "../api/environments";
import {
  fetchExecutionDetail,
  fetchExecutions,
  triggerExecution,
  type ExecutionDetail,
  type ExecutionRow,
} from "../api/executions";
import { ApiError } from "../api/http";

const STATUS_LABELS: Record<string, string> = {
  QUEUED: "排队中",
  RUNNING: "执行中",
  PASSED: "通过",
  ASSERTION_FAILED: "断言失败",
  ERROR: "执行错误",
  CANCELLED: "已取消",
  TIMED_OUT: "已超时",
};

const route = useRoute();
const projectId = computed(() => Number(route.params.id));

const state = reactive({
  executions: [] as ExecutionRow[],
  apiCases: [] as CaseRow[],
  environments: [] as EnvironmentSummary[],
  loading: false,
  error: null as string | null,
  selectedCaseId: null as number | null,
  selectedEnvId: null as number | null,
  triggering: false,
  submitted: false,
  detail: null as ExecutionDetail | null,
});

const passedAssertions = (step: {
  assertions: { passed: boolean }[];
}): number => step.assertions.filter((a) => a.passed).length;

function evidenceUrl(jobId: number, evidenceId: string): string {
  return `/api/v1/projects/${projectId.value}/executions/${jobId}/evidence/${evidenceId}`;
}

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    const [executions, cases, environments] = await Promise.all([
      fetchExecutions(projectId.value),
      fetchCases(projectId.value),
      fetchEnvironments(projectId.value),
    ]);
    state.executions = executions;
    state.apiCases = cases.filter(
      (testCase) =>
        (testCase.caseType === "API" ||
          testCase.caseType === "WEB" ||
          testCase.caseType === "ELECTRON") &&
        testCase.status === "READY",
    );
    state.environments = environments;
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function submitTrigger(): Promise<void> {
  if (state.selectedCaseId === null || state.selectedEnvId === null) {
    return;
  }
  state.triggering = true;
  state.error = null;
  state.submitted = false;
  try {
    await triggerExecution(projectId.value, {
      caseId: state.selectedCaseId,
      environmentId: state.selectedEnvId,
    });
    state.submitted = true;
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.triggering = false;
  }
}

async function open(jobId: number): Promise<void> {
  state.error = null;
  try {
    state.detail = await fetchExecutionDetail(projectId.value, jobId);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

const caseTitle = computed(() => {
  const detail = state.detail;
  if (!detail) {
    return "";
  }
  const version = detail.caseVersionId ?? 0;
  const testCase = state.apiCases.find((c) => c.id === version);
  return testCase ? testCase.title : `用例版本 ${version}`;
});

onMounted(load);
watch(projectId, load);
</script>

<template>
  <el-card class="exec-card">
    <template #header>
      <div class="card-header">
        <span>执行记录</span>
        <router-link :to="`/projects/${projectId}`" class="entry-link"
          >返回项目</router-link
        >
      </div>
    </template>

    <el-alert
      v-if="state.error"
      type="error"
      :closable="false"
      show-icon
      :title="state.error"
    />

    <h4>发起执行</h4>
    <el-form inline @submit.prevent="submitTrigger">
      <el-form-item label="API / Web / Electron 用例">
        <el-radio-group v-model="state.selectedCaseId">
          <el-radio-button
            v-for="testCase in state.apiCases"
            :key="testCase.id"
            :value="testCase.id"
            :data-testid="`exec-case-${testCase.id}`"
          >
            {{ testCase.title }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="环境">
        <el-radio-group v-model="state.selectedEnvId">
          <el-radio-button
            v-for="environment in state.environments"
            :key="environment.id"
            :value="environment.id"
            :data-testid="`exec-env-${environment.id}`"
          >
            {{ environment.name }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          :loading="state.triggering"
          data-testid="exec-trigger"
          @click="submitTrigger"
          >发起执行</el-button
        >
      </el-form-item>
    </el-form>
    <el-alert
      v-if="state.submitted"
      type="success"
      :closable="false"
      show-icon
      title="执行任务已提交，等待 Worker 领取执行"
    />

    <el-table
      v-loading="state.loading"
      :data="state.executions"
      data-testid="executions-table"
    >
      <el-table-column prop="id" label="任务" width="80" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag
            :type="
              row.status === 'PASSED'
                ? 'success'
                : row.status === 'ASSERTION_FAILED' || row.status === 'ERROR'
                  ? 'danger'
                  : 'info'
            "
            size="small"
            >{{ STATUS_LABELS[row.status] ?? row.status }}</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column label="用例版本" width="120">
        <template #default="{ row }">v{{ row.caseVersionId }}</template>
      </el-table-column>
      <el-table-column label="环境" width="100">
        <template #default="{ row }">
          {{
            state.environments.find((e) => e.id === row.environmentId)?.name ??
            row.environmentId
          }}
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ row.createdAt }}</template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button
            size="small"
            :data-testid="`exec-open-${row.id}`"
            @click="open(row.id)"
            >查看</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <template v-if="state.detail">
      <el-divider />
      <div data-testid="exec-detail">
        <h3 class="detail-title">
          执行 #{{ state.detail.id }} — {{ caseTitle }}（{{
            STATUS_LABELS[state.detail.status] ?? state.detail.status
          }}）
        </h3>
        <el-alert
          v-if="state.detail.jobType === 'ELECTRON'"
          type="warning"
          :closable="false"
          show-icon
          data-testid="electron-boundary"
          title="实验性：主进程验证仅使用应用显式测试钩子；不支持原生系统对话框。"
        />
        <div
          v-for="attempt in state.detail.attempts"
          :key="attempt.attemptNumber"
          class="attempt-block"
        >
          <div class="attempt-meta">
            <el-tag
              :type="attempt.status === 'PASSED' ? 'success' : 'danger'"
              size="small"
              >尝试 #{{ attempt.attemptNumber }} ·
              {{ STATUS_LABELS[attempt.status] ?? attempt.status }}</el-tag
            >
          </div>
          <template v-if="attempt.result">
            <p v-if="attempt.result.reason" class="reason-line">
              原因：{{ attempt.result.reason }}
            </p>
            <div
              v-if="attempt.result.steps?.length"
              class="section"
              data-testid="web-steps"
            >
              <span class="section-label">Web 步骤</span>
              <el-table :data="attempt.result.steps" size="small">
                <el-table-column label="步骤" min-width="190">
                  <template #default="{ row }">
                    <div>{{ row.index + 1 }}. {{ row.action }}</div>
                    <div class="step-selector">{{ row.selector || "—" }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="110">
                  <template #default="{ row }">
                    <el-tag
                      :type="row.status === 'PASSED' ? 'success' : 'danger'"
                      size="small"
                      >{{ STATUS_LABELS[row.status] ?? row.status }}</el-tag
                    >
                  </template>
                </el-table-column>
                <el-table-column label="预期 / 实际" min-width="180">
                  <template #default="{ row }">
                    <div>预期：{{ row.expected ?? "—" }}</div>
                    <div>实际：{{ row.actual ?? "—" }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="错误" min-width="150">
                  <template #default="{ row }">{{ row.error || "—" }}</template>
                </el-table-column>
                <el-table-column label="证据" min-width="150">
                  <template #default="{ row }">
                    <a
                      v-for="entry in row.evidence"
                      :key="entry.id"
                      class="evidence-link"
                      :data-testid="`evidence-${entry.id}`"
                      :href="evidenceUrl(state.detail!.id, entry.id)"
                      >{{ entry.kind }} · {{ entry.filename }}</a
                    >
                  </template>
                </el-table-column>
                <el-table-column
                  prop="durationMs"
                  label="耗时(ms)"
                  width="100"
                />
              </el-table>
            </div>
            <div
              v-if="attempt.result.evidence?.length"
              class="section"
              data-testid="run-evidence"
            >
              <span class="section-label">执行证据</span>
              <a
                v-for="entry in attempt.result.evidence"
                :key="entry.id"
                class="evidence-link"
                :data-testid="`evidence-${entry.id}`"
                :href="evidenceUrl(state.detail!.id, entry.id)"
                >{{ entry.kind }} · {{ entry.filename }}</a
              >
            </div>
            <!-- Chained / data-row runs report per-row results. -->
            <div
              v-if="attempt.result.rows && attempt.result.rows.length"
              class="section"
            >
              <span class="section-label">数据行与步骤</span>
              <div
                v-for="row in attempt.result.rows"
                :key="row.rowIndex"
                class="chain-row"
              >
                <div class="row-meta">
                  <el-tag
                    :type="row.status === 'PASSED' ? 'success' : 'danger'"
                    size="small"
                    >行 {{ row.rowIndex }} ·
                    {{ STATUS_LABELS[row.status] ?? row.status }}</el-tag
                  >
                  <span class="row-values">{{
                    Object.entries(row.values)
                      .map(([k, v]) => `${k}=${v}`)
                      .join("，") || "无参数"
                  }}</span>
                </div>
                <el-table :data="row.steps" size="small" class="steps-table">
                  <el-table-column prop="name" label="步骤" width="140" />
                  <el-table-column label="状态" width="120">
                    <template #default="{ row: step }">
                      <el-tag
                        :type="step.status === 'PASSED' ? 'success' : 'danger'"
                        size="small"
                        >{{ STATUS_LABELS[step.status] ?? step.status }}</el-tag
                      >
                    </template>
                  </el-table-column>
                  <el-table-column label="断言" width="110">
                    <template #default="{ row: step }">
                      {{ passedAssertions(step) }}/{{ step.assertions.length }}
                    </template>
                  </el-table-column>
                  <el-table-column label="提取变量" width="180">
                    <template #default="{ row: step }">
                      {{
                        step.extract
                          .map(
                            (e: { variable: string; value: string }) =>
                              `${e.variable}=${e.value}`,
                          )
                          .join("，") || "—"
                      }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="durationMs" label="耗时(ms)" />
                </el-table>
              </div>
            </div>
            <div v-if="attempt.result.request" class="section">
              <span class="section-label">请求（脱敏）</span>
              <div class="kv">
                <span class="k">{{ attempt.result.request.method }}</span>
                <span class="v">{{ attempt.result.request.url }}</span>
              </div>
              <div
                v-for="(value, name) in attempt.result.request.headers"
                :key="name"
                class="kv"
              >
                <span class="k">{{ name }}</span>
                <span class="v">{{ value }}</span>
              </div>
            </div>
            <div v-if="attempt.result.response" class="section">
              <span class="section-label">响应（脱敏）</span>
              <div class="kv">
                <span class="k">状态码</span>
                <span class="v">{{ attempt.result.response.statusCode }}</span>
              </div>
              <div class="kv">
                <span class="k">响应体</span>
                <span class="v body-text">{{
                  attempt.result.response.body
                }}</span>
              </div>
              <p
                v-if="attempt.result.response.bodyTruncated"
                class="truncated-hint"
              >
                响应体已截断
              </p>
            </div>
            <div v-if="attempt.result.assertions?.length" class="section">
              <span class="section-label">断言</span>
              <el-table
                :data="attempt.result.assertions"
                size="small"
                data-testid="assertions-table"
              >
                <el-table-column prop="type" label="类型" width="150" />
                <el-table-column prop="path" label="路径" width="140" />
                <el-table-column prop="expected" label="期望" />
                <el-table-column prop="actual" label="实际" />
                <el-table-column label="结果" width="90">
                  <template #default="{ row }">
                    <el-tag
                      :type="row.passed ? 'success' : 'danger'"
                      size="small"
                      >{{ row.passed ? "通过" : "失败" }}</el-tag
                    >
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <p class="duration-line">
              耗时 {{ attempt.result.durationMs }} ms
              <template v-if="attempt.result.redirects?.length">
                · 重定向：{{ attempt.result.redirects.join("、") }}
              </template>
            </p>
          </template>
          <p v-else class="empty-hint">尚无执行结果</p>
        </div>
      </div>
    </template>
  </el-card>
</template>

<style scoped>
.exec-card {
  max-width: 1080px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.entry-link {
  color: var(--el-color-primary);
  text-decoration: none;
}

.detail-title {
  margin: 8px 0;
}

.attempt-block {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 12px;
}

.attempt-meta {
  margin-bottom: 8px;
}

.reason-line {
  color: var(--el-color-danger);
}

.section {
  margin-bottom: 10px;
}

.section-label {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 4px;
}

.step-selector {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  word-break: break-all;
}

.evidence-link {
  display: block;
  margin-bottom: 4px;
}

.chain-row {
  margin-bottom: 10px;
}

.row-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}

.row-values {
  color: var(--el-text-color-regular);
}

.steps-table {
  margin-bottom: 4px;
}

.kv {
  display: flex;
  gap: 12px;
}

.k {
  min-width: 120px;
  color: var(--el-text-color-secondary);
}

.v.body-text {
  word-break: break-all;
  max-height: 120px;
  overflow: hidden;
}

.truncated-hint {
  color: var(--el-color-warning);
  font-size: 12px;
}

.duration-line {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.empty-hint {
  color: var(--el-text-color-secondary);
}
</style>
