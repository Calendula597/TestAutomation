<script setup lang="ts">
import { computed, onMounted, reactive, watch } from "vue";
import { useRoute } from "vue-router";
import {
  createEnvironment,
  fetchEnvironment,
  fetchEnvironmentVersions,
  fetchEnvironments,
  updateEnvironment,
  validateTarget,
  type EnvironmentDetail,
  type EnvironmentSummary,
  type EnvironmentVersionRow,
  type TargetValidationResponse,
} from "../api/environments";
import { fetchProject, type ProjectRow } from "../api/projects";
import { ApiError } from "../api/http";

const route = useRoute();
const projectId = computed(() => Number(route.params.id));

const state = reactive({
  project: null as ProjectRow | null,
  list: [] as EnvironmentSummary[],
  loading: false,
  error: null as string | null,
  createName: "",
  creating: false,
  // Selected environment detail.
  detailId: null as number | null,
  detail: null as EnvironmentDetail | null,
  versions: [] as EnvironmentVersionRow[],
  detailLoading: false,
  // Owner edit state; secret values are typed once and sent immediately.
  variables: [] as { key: string; value: string }[],
  newVariable: { key: "", value: "" },
  targets: [] as string[],
  targetInput: "",
  secretEdits: [] as { key: string; value: string; remove: boolean }[],
  newSecret: { key: "", value: "" },
  saving: false,
  savedVersion: null as number | null,
  checkUrl: "",
  checkRedirect: "",
  checking: false,
  checkResult: null as TargetValidationResponse | null,
});

const isOwner = computed(() => state.project?.myRole === "OWNER");

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.project = await fetchProject(projectId.value);
    state.list = await fetchEnvironments(projectId.value);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function submitCreate(): Promise<void> {
  if (!state.createName.trim()) {
    return;
  }
  state.creating = true;
  state.error = null;
  try {
    await createEnvironment(projectId.value, { name: state.createName.trim() });
    state.createName = "";
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.creating = false;
  }
}

async function open(environmentId: number): Promise<void> {
  state.detailId = environmentId;
  state.savedVersion = null;
  state.checkResult = null;
  await reloadDetail();
}

async function reloadDetail(): Promise<void> {
  if (state.detailId === null) {
    return;
  }
  state.detailLoading = true;
  state.error = null;
  try {
    state.detail = await fetchEnvironment(projectId.value, state.detailId);
    state.versions = await fetchEnvironmentVersions(
      projectId.value,
      state.detailId,
    );
    resetEdits(state.detail);
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.detailLoading = false;
  }
}

function resetEdits(detail: EnvironmentDetail): void {
  state.variables = Object.entries(detail.plainVariables).map(
    ([key, value]) => ({
      key,
      value,
    }),
  );
  state.newVariable = { key: "", value: "" };
  state.targets = [...detail.targets];
  state.targetInput = "";
  state.secretEdits = detail.secrets.map((secret) => ({
    key: secret.key,
    value: "",
    remove: false,
  }));
  state.newSecret = { key: "", value: "" };
}

function addVariable(): void {
  if (!state.newVariable.key.trim()) {
    return;
  }
  state.variables.push({
    key: state.newVariable.key.trim(),
    value: state.newVariable.value,
  });
  state.newVariable = { key: "", value: "" };
}

function addTarget(): void {
  const target = state.targetInput.trim();
  if (!target || state.targets.includes(target)) {
    return;
  }
  state.targets.push(target);
  state.targetInput = "";
}

async function save(): Promise<void> {
  if (state.detailId === null || !state.detail) {
    return;
  }
  state.saving = true;
  state.error = null;
  try {
    const plainVariables = Object.fromEntries(
      state.variables
        .filter((variable) => variable.key.trim())
        .map((variable) => [variable.key.trim(), variable.value]),
    );
    const secretWrites: Record<string, string | null> = {};
    for (const edit of state.secretEdits) {
      if (edit.remove) {
        secretWrites[edit.key] = null;
      } else if (edit.value) {
        secretWrites[edit.key] = edit.value;
      }
    }
    if (state.newSecret.key.trim() && state.newSecret.value) {
      secretWrites[state.newSecret.key.trim()] = state.newSecret.value;
    }
    const payload: {
      plainVariables: Record<string, string>;
      targets: string[];
      secretWrites?: Record<string, string | null>;
    } = { plainVariables, targets: [...state.targets] };
    if (Object.keys(secretWrites).length > 0) {
      payload.secretWrites = secretWrites;
    }
    const updated = await updateEnvironment(
      projectId.value,
      state.detailId,
      payload,
    );
    state.savedVersion = updated.currentVersion;
    await reloadDetail();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.saving = false;
  }
}

async function runTargetCheck(): Promise<void> {
  if (state.detailId === null || !state.checkUrl.trim()) {
    return;
  }
  state.checking = true;
  state.error = null;
  state.checkResult = null;
  try {
    state.checkResult = await validateTarget(projectId.value, state.detailId, {
      url: state.checkUrl.trim(),
      redirectUrl: state.checkRedirect.trim() || undefined,
    });
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.checking = false;
  }
}

onMounted(load);
watch(projectId, load);
</script>

<template>
  <el-card class="env-card">
    <template #header>
      <div class="card-header">
        <span>环境配置</span>
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

    <el-table
      v-loading="state.loading"
      :data="state.list"
      data-testid="env-table"
    >
      <el-table-column prop="name" label="环境名称" />
      <el-table-column label="版本" width="100">
        <template #default="{ row }">v{{ row.currentVersion }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button
            size="small"
            :data-testid="`env-open-${row.id}`"
            @click="open(row.id)"
            >查看</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-form
      v-if="isOwner"
      inline
      class="create-form"
      @submit.prevent="submitCreate"
    >
      <el-form-item label="新建环境">
        <el-input
          v-model="state.createName"
          placeholder="环境名称"
          data-testid="env-create-name"
        />
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          :loading="state.creating"
          data-testid="env-create-submit"
          @click="submitCreate"
          >创建</el-button
        >
      </el-form-item>
    </el-form>

    <template v-if="state.detail">
      <el-divider />
      <h3 class="detail-title">
        {{ state.detail.name }}（当前 v{{ state.detail.currentVersion }}）
      </h3>

      <el-alert
        v-if="state.savedVersion"
        type="success"
        :closable="false"
        show-icon
        :title="`已保存为新版本 v${state.savedVersion}`"
      />

      <h4>普通变量</h4>
      <div data-testid="env-variables">
        <div
          v-for="(variable, index) in state.variables"
          :key="variable.key"
          class="row-line"
        >
          <span class="row-key">{{ variable.key }}</span>
          <el-input
            v-model="variable.value"
            :disabled="!isOwner"
            :data-testid="`env-variable-value-${index}`"
          />
          <el-button
            v-if="isOwner"
            size="small"
            type="danger"
            @click="state.variables.splice(index, 1)"
            >删除</el-button
          >
        </div>
        <el-form v-if="isOwner" inline @submit.prevent="addVariable">
          <el-form-item>
            <el-input
              v-model="state.newVariable.key"
              placeholder="变量名"
              data-testid="env-variable-key"
            />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="state.newVariable.value"
              placeholder="变量值"
              data-testid="env-variable-value-new"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              size="small"
              data-testid="env-variable-add"
              @click="addVariable"
              >添加变量</el-button
            >
          </el-form-item>
        </el-form>
      </div>

      <h4>加密密钥</h4>
      <div data-testid="env-secrets">
        <p v-if="state.detail.secrets.length === 0" class="empty-hint">
          尚未配置密钥
        </p>
        <div v-for="edit in state.secretEdits" :key="edit.key" class="row-line">
          <span class="row-key">{{ edit.key }}</span>
          <el-tag v-if="!edit.remove" type="success">已设置</el-tag>
          <span class="masked-value">******</span>
          <template v-if="isOwner && !edit.remove">
            <el-input
              v-model="edit.value"
              type="password"
              placeholder="留空保持不变"
              show-password
            />
            <el-button size="small" type="danger" @click="edit.remove = true"
              >移除</el-button
            >
          </template>
          <el-tag v-else-if="edit.remove" type="danger">保存后移除</el-tag>
        </div>
        <el-form v-if="isOwner" inline @submit.prevent>
          <el-form-item>
            <el-input
              v-model="state.newSecret.key"
              placeholder="密钥名"
              data-testid="env-secret-key"
            />
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="state.newSecret.value"
              type="password"
              placeholder="密钥值（仅写入）"
              show-password
              data-testid="env-secret-value"
            />
          </el-form-item>
        </el-form>
      </div>

      <h4>目标白名单</h4>
      <div data-testid="env-targets">
        <div v-for="target in state.targets" :key="target" class="row-line">
          <span class="row-key">{{ target }}</span>
          <el-button
            v-if="isOwner"
            size="small"
            type="danger"
            @click="state.targets = state.targets.filter((t) => t !== target)"
            >删除</el-button
          >
        </div>
        <el-form v-if="isOwner" inline @submit.prevent="addTarget">
          <el-form-item>
            <el-input
              v-model="state.targetInput"
              placeholder="https://api.demo.local"
              data-testid="env-target-input"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              size="small"
              data-testid="env-target-add"
              @click="addTarget"
              >添加目标</el-button
            >
          </el-form-item>
        </el-form>
      </div>

      <el-button
        v-if="isOwner"
        type="primary"
        :loading="state.saving"
        data-testid="env-save"
        @click="save"
        >保存为新版本</el-button
      >

      <h4>目标校验（执行前检查）</h4>
      <el-form inline @submit.prevent="runTargetCheck">
        <el-form-item>
          <el-input
            v-model="state.checkUrl"
            placeholder="待执行 URL"
            data-testid="env-check-url"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="state.checkRedirect"
            placeholder="重定向 URL（可选）"
            data-testid="env-check-redirect"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            :loading="state.checking"
            data-testid="env-check-submit"
            @click="runTargetCheck"
            >校验</el-button
          >
        </el-form-item>
      </el-form>
      <p
        v-if="state.checkResult"
        data-testid="env-check-result"
        class="check-result"
      >
        {{ state.checkResult.allowed ? "允许访问" : "已拒绝" }}
      </p>

      <h4>版本快照（密钥脱敏）</h4>
      <div data-testid="env-versions">
        <div
          v-for="version in state.versions"
          :key="version.version"
          class="version-line"
        >
          <span class="row-key">v{{ version.version }}</span>
          <span>目标：{{ version.targets.join("、") || "无" }}</span>
          <span v-if="version.secrets.length">
            密钥：{{
              version.secrets.map((s) => `${s.key}:${s.maskedValue}`).join("、")
            }}
          </span>
        </div>
      </div>
    </template>
  </el-card>
</template>

<style scoped>
.env-card {
  max-width: 960px;
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

.create-form {
  margin-top: 16px;
}

.detail-title {
  margin: 8px 0;
}

.row-line {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.row-key {
  min-width: 180px;
  word-break: break-all;
}

.masked-value {
  color: var(--el-text-color-secondary);
}

.empty-hint {
  color: var(--el-text-color-secondary);
}

.check-result {
  font-weight: 600;
}

.version-line {
  display: flex;
  gap: 16px;
  margin-bottom: 6px;
}
</style>
