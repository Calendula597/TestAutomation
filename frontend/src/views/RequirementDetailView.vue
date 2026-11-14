<script setup lang="ts">
import { computed, onMounted, reactive, watch } from "vue";
import { useRoute } from "vue-router";
import {
  fetchRequirement,
  fetchRequirementVersions,
  updateRequirement,
  type RequirementRow,
  type RequirementVersionRow,
} from "../api/requirements";
import { ApiError } from "../api/http";
import AgentPanel from "../components/AgentPanel.vue";

const route = useRoute();
const projectId = computed(() => Number(route.params.id));
const requirementId = computed(() => Number(route.params.requirementId));

const state = reactive({
  requirement: null as RequirementRow | null,
  versions: [] as RequirementVersionRow[],
  selectedVersion: 0,
  loading: false,
  error: null as string | null,
  editing: false,
  editBody: "",
  editSource: "PASTED_TEXT" as "PASTED_TEXT" | "MARKDOWN",
  editCriteria: "",
  submitting: false,
});

const shownVersion = computed(() => {
  if (
    state.selectedVersion === 0 ||
    state.selectedVersion === state.requirement?.currentVersion
  ) {
    return state.requirement;
  }
  const snapshot = state.versions.find(
    (v) => v.version === state.selectedVersion,
  );
  return snapshot
    ? {
        id: requirementId.value,
        title: state.requirement?.title ?? "",
        currentVersion: snapshot.version,
        body: snapshot.body,
        sourceType: snapshot.sourceType as RequirementRow["sourceType"],
        acceptanceCriteria: snapshot.acceptanceCriteria,
      }
    : null;
});

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.requirement = await fetchRequirement(
      projectId.value,
      requirementId.value,
    );
    state.versions = await fetchRequirementVersions(
      projectId.value,
      requirementId.value,
    );
    state.selectedVersion = 0;
  } catch (error) {
    state.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

function startEdit(): void {
  if (!state.requirement) {
    return;
  }
  state.editing = true;
  state.editBody = state.requirement.body;
  state.editSource = state.requirement.sourceType;
  state.editCriteria = state.requirement.acceptanceCriteria;
}

async function submitEdit(): Promise<void> {
  state.submitting = true;
  state.error = null;
  try {
    await updateRequirement(projectId.value, requirementId.value, {
      body: state.editBody,
      sourceType: state.editSource,
      acceptanceCriteria: state.editCriteria,
    });
    state.editing = false;
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.submitting = false;
  }
}

const SOURCE_LABELS: Record<string, string> = {
  PASTED_TEXT: "粘贴文本",
  MARKDOWN: "Markdown",
};

onMounted(load);
watch([projectId, requirementId], load);
</script>

<template>
  <el-card class="detail-card">
    <template #header>
      <div class="card-header">
        <span>
          {{ state.requirement?.title ?? "需求详情" }}
          <el-tag v-if="state.requirement" class="version-tag"
            >v{{ state.requirement.currentVersion }}</el-tag
          >
        </span>
        <div>
          <el-button :loading="state.loading" @click="load">刷新</el-button>
          <el-button
            v-if="state.requirement && !state.editing"
            type="primary"
            data-testid="req-edit"
            @click="startEdit"
          >
            修改（产生新版本）
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

    <template v-if="state.requirement">
      <el-form
        v-if="state.editing"
        label-position="top"
        @submit.prevent="submitEdit"
      >
        <el-form-item label="来源">
          <el-radio-group v-model="state.editSource">
            <el-radio value="PASTED_TEXT">粘贴文本</el-radio>
            <el-radio value="MARKDOWN">Markdown</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="需求正文">
          <el-input
            v-model="state.editBody"
            type="textarea"
            :rows="5"
            data-testid="req-edit-body"
          />
        </el-form-item>
        <el-form-item label="验收标准">
          <el-input
            v-model="state.editCriteria"
            type="textarea"
            :rows="4"
            data-testid="req-edit-criteria"
          />
        </el-form-item>
        <el-button
          type="primary"
          :loading="state.submitting"
          data-testid="req-edit-submit"
          @click="submitEdit"
        >
          保存为新版本
        </el-button>
        <el-button @click="state.editing = false">取消</el-button>
      </el-form>

      <template v-else>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="来源">{{
            SOURCE_LABELS[shownVersion?.sourceType ?? ""]
          }}</el-descriptions-item>
          <el-descriptions-item label="需求正文">
            <pre class="content-block" data-testid="req-body">{{
              shownVersion?.body
            }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="验收标准">
            <pre class="content-block" data-testid="req-criteria">{{
              shownVersion?.acceptanceCriteria
            }}</pre>
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="versions-title">历史版本</h4>
        <el-table
          :data="state.versions"
          size="small"
          data-testid="req-versions-table"
        >
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }">{{
              SOURCE_LABELS[row.sourceType]
            }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button
                size="small"
                :disabled="row.version === state.requirement?.currentVersion"
                @click="state.selectedVersion = row.version"
              >
                查看
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </template>
    <el-empty
      v-else-if="!state.loading && !state.error"
      description="需求不存在"
    />
  </el-card>

  <AgentPanel
    v-if="state.requirement"
    :project-id="projectId"
    :requirement-id="requirementId"
  />
</template>

<style scoped>
.detail-card {
  max-width: 860px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.version-tag {
  margin-left: 8px;
}

.content-block {
  margin: 0;
  white-space: pre-wrap;
  font-family: inherit;
}

.versions-title {
  margin: 20px 0 8px;
}
</style>
