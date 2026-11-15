<script setup lang="ts">
import { computed, onMounted, reactive, watch } from "vue";
import { useRoute } from "vue-router";
import { fetchCases, type CaseRow } from "../api/testcases";
import {
  fetchEnvironments,
  type EnvironmentSummary,
} from "../api/environments";
import {
  createCiToken,
  createSchedule,
  fetchCiTokens,
  fetchSchedules,
  revokeCiToken,
  updateSchedule,
  type CiTokenRow,
  type ScheduleRow,
} from "../api/automation";
import { ApiError } from "../api/http";

const route = useRoute();
const projectId = computed(() => Number(route.params.id));
const expiry = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
expiry.setMinutes(expiry.getMinutes() - expiry.getTimezoneOffset());

const state = reactive({
  cases: [] as CaseRow[],
  environments: [] as EnvironmentSummary[],
  schedules: [] as ScheduleRow[],
  tokens: [] as CiTokenRow[],
  schedule: {
    caseId: null as number | null,
    environmentId: null as number | null,
    name: "",
    cronExpression: "0 0 9 * * *",
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
    allowOverlap: false,
    enabled: true,
  },
  performanceConfirmed: false,
  token: {
    name: "",
    scopes: ["EXECUTION_TRIGGER", "EXECUTION_READ"],
    expiresAt: expiry.toISOString().slice(0, 16),
  },
  issuedToken: "",
  loading: false,
  busy: false,
  error: null as string | null,
  tokenError: null as string | null,
  message: "",
});

const executableCases = computed(() =>
  state.cases.filter(
    (testCase) => testCase.status === "READY" && testCase.caseType !== "MANUAL",
  ),
);
const performanceSelected = computed(
  () =>
    executableCases.value.find(
      (testCase) => testCase.id === state.schedule.caseId,
    )?.caseType === "PERFORMANCE",
);

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    const [cases, environments, schedules] = await Promise.all([
      fetchCases(projectId.value),
      fetchEnvironments(projectId.value),
      fetchSchedules(projectId.value),
    ]);
    state.cases = cases;
    state.environments = environments;
    state.schedules = schedules;
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
  try {
    state.tokens = await fetchCiTokens(projectId.value);
    state.tokenError = null;
  } catch (error) {
    state.tokens = [];
    state.tokenError =
      error instanceof ApiError && error.status === 403
        ? "只有项目负责人可以查看和管理 CI 令牌"
        : error instanceof ApiError
          ? error.message
          : String(error);
  }
}

async function submitSchedule(): Promise<void> {
  if (state.schedule.caseId === null || state.schedule.environmentId === null) {
    state.error = "请选择已发布用例与环境";
    return;
  }
  if (performanceSelected.value && !state.performanceConfirmed) {
    state.error = "定时压测必须由项目负责人明确确认";
    return;
  }
  state.busy = true;
  state.error = null;
  state.message = "";
  try {
    await createSchedule(projectId.value, {
      ...state.schedule,
      caseId: state.schedule.caseId,
      environmentId: state.schedule.environmentId,
      ...(performanceSelected.value ? { confirmed: true } : {}),
    });
    state.message = "定时计划已保存，默认不会重叠执行";
    state.performanceConfirmed = false;
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.busy = false;
  }
}

async function toggleSchedule(row: ScheduleRow): Promise<void> {
  state.error = null;
  try {
    await updateSchedule(projectId.value, row.id, { enabled: !row.enabled });
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

async function submitToken(): Promise<void> {
  state.busy = true;
  state.tokenError = null;
  state.issuedToken = "";
  try {
    const issued = await createCiToken(projectId.value, {
      name: state.token.name,
      scopes: state.token.scopes,
      expiresAt: new Date(state.token.expiresAt).toISOString(),
    });
    state.issuedToken = issued.token;
    state.token.name = "";
    await load();
  } catch (error) {
    state.tokenError =
      error instanceof ApiError ? error.message : String(error);
  } finally {
    state.busy = false;
  }
}

async function revoke(id: number): Promise<void> {
  if (!window.confirm("撤销后该 CI 令牌立即失效，且不能恢复。确认撤销？"))
    return;
  state.tokenError = null;
  try {
    await revokeCiToken(projectId.value, id);
    await load();
  } catch (error) {
    state.tokenError =
      error instanceof ApiError ? error.message : String(error);
  }
}

onMounted(load);
watch(projectId, load);
</script>

<template>
  <div class="automation-page" v-loading="state.loading">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>定时计划</span>
          <div>
            <router-link
              :to="`/projects/${projectId}/executions`"
              class="entry-link"
              >执行记录</router-link
            >
            <router-link :to="`/projects/${projectId}`" class="entry-link"
              >返回项目</router-link
            >
          </div>
        </div>
      </template>
      <el-alert
        v-if="state.error"
        type="error"
        :closable="false"
        :title="state.error"
      />
      <el-alert
        v-if="state.message"
        type="success"
        :closable="false"
        :title="state.message"
      />
      <el-form inline @submit.prevent="submitSchedule">
        <el-form-item label="计划名称">
          <el-input v-model="state.schedule.name" data-testid="schedule-name" />
        </el-form-item>
        <el-form-item label="已发布用例">
          <el-radio-group v-model="state.schedule.caseId">
            <el-radio-button
              v-for="testCase in executableCases"
              :key="testCase.id"
              :value="testCase.id"
              :data-testid="`schedule-case-${testCase.id}`"
            >
              {{ testCase.title }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="环境">
          <el-radio-group v-model="state.schedule.environmentId">
            <el-radio-button
              v-for="environment in state.environments"
              :key="environment.id"
              :value="environment.id"
              :data-testid="`schedule-env-${environment.id}`"
            >
              {{ environment.name }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Cron（秒 分 时 日 月 周）">
          <el-input
            v-model="state.schedule.cronExpression"
            data-testid="schedule-cron"
          />
        </el-form-item>
        <el-form-item label="时区">
          <el-input
            v-model="state.schedule.timeZone"
            data-testid="schedule-timezone"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox
            v-model="state.schedule.allowOverlap"
            data-testid="schedule-overlap"
          >
            允许与未完成的执行重叠
          </el-checkbox>
        </el-form-item>
        <el-form-item v-if="performanceSelected">
          <el-checkbox
            v-model="state.performanceConfirmed"
            data-testid="schedule-performance-confirm"
          >
            我确认按此计划执行受限压测
          </el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="state.busy"
            data-testid="schedule-create"
            @click="submitSchedule"
            >保存计划</el-button
          >
        </el-form-item>
      </el-form>
      <el-table :data="state.schedules" data-testid="schedules-table">
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="cronExpression" label="Cron" min-width="140" />
        <el-table-column prop="timeZone" label="时区" width="150" />
        <el-table-column prop="nextRunAt" label="下次执行" min-width="200" />
        <el-table-column label="重叠" width="90">
          <template #default="{ row }">{{
            row.allowOverlap ? "允许" : "禁止"
          }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{
            row.enabled ? "启用" : "停用"
          }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button
              size="small"
              :data-testid="`toggle-schedule-${row.id}`"
              @click="toggleSchedule(row)"
              >{{ row.enabled ? "停用" : "启用" }}</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header><span>项目 CI 令牌</span></template>
      <el-alert
        v-if="state.tokenError"
        type="warning"
        :closable="false"
        :title="state.tokenError"
      />
      <el-form inline @submit.prevent="submitToken">
        <el-form-item label="令牌名称">
          <el-input v-model="state.token.name" data-testid="token-name" />
        </el-form-item>
        <el-form-item label="最小作用域">
          <el-checkbox-group v-model="state.token.scopes">
            <el-checkbox value="EXECUTION_TRIGGER" data-testid="scope-trigger"
              >触发执行</el-checkbox
            >
            <el-checkbox value="EXECUTION_READ" data-testid="scope-read"
              >查询执行</el-checkbox
            >
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="到期时间（本地）">
          <el-input
            v-model="state.token.expiresAt"
            type="datetime-local"
            data-testid="token-expiry"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="state.busy"
            data-testid="token-create"
            @click="submitToken"
            >签发令牌</el-button
          >
        </el-form-item>
      </el-form>
      <el-alert
        v-if="state.issuedToken"
        type="success"
        :closable="false"
        title="请立即复制令牌，原始令牌只显示一次"
      >
        <code data-testid="issued-token">{{ state.issuedToken }}</code>
      </el-alert>
      <el-table :data="state.tokens" data-testid="tokens-table">
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="prefix" label="前缀" width="140" />
        <el-table-column label="作用域" min-width="220">
          <template #default="{ row }">{{ row.scopes.join("、") }}</template>
        </el-table-column>
        <el-table-column prop="expiresAt" label="到期时间" min-width="200" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{
            row.revokedAt ? "已撤销" : "有效"
          }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="!row.revokedAt"
              size="small"
              type="danger"
              :data-testid="`revoke-token-${row.id}`"
              @click="revoke(row.id)"
              >撤销</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">
        CI 调用必须同时携带 Bearer 令牌与
        Idempotency-Key。令牌不能登录浏览器或调用其他项目。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.automation-page {
  display: grid;
  gap: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.entry-link {
  color: var(--el-color-primary);
  margin-left: 16px;
  text-decoration: none;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
