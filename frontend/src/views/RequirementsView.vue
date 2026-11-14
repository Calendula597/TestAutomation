<script setup lang="ts">
import { computed, onMounted, reactive, watch } from "vue";
import { useRoute } from "vue-router";
import {
  createRequirement,
  fetchRequirements,
  type RequirementRow,
  type SourceType,
} from "../api/requirements";
import { ApiError } from "../api/http";

const route = useRoute();
const projectId = computed(() => Number(route.params.id));

const state = reactive({
  requirements: [] as RequirementRow[],
  loading: false,
  error: null as string | null,
  form: {
    visible: false,
    title: "",
    body: "",
    sourceType: "PASTED_TEXT" as SourceType,
    acceptanceCriteria: "",
    submitting: false,
  },
});

async function load(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.requirements = await fetchRequirements(projectId.value);
  } catch (error) {
    state.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function submitCreate(): Promise<void> {
  state.form.submitting = true;
  state.error = null;
  try {
    await createRequirement(projectId.value, {
      title: state.form.title,
      body: state.form.body,
      sourceType: state.form.sourceType,
      acceptanceCriteria: state.form.acceptanceCriteria,
    });
    state.form.visible = false;
    state.form.title = "";
    state.form.body = "";
    state.form.sourceType = "PASTED_TEXT";
    state.form.acceptanceCriteria = "";
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.form.submitting = false;
  }
}

onMounted(load);
watch(projectId, load);
</script>

<template>
  <el-card class="req-card">
    <template #header>
      <div class="card-header">
        <span>需求</span>
        <div>
          <el-button :loading="state.loading" @click="load">刷新</el-button>
          <el-button
            type="primary"
            data-testid="req-create"
            @click="state.form.visible = true"
          >
            新建需求
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
    <el-empty
      v-else-if="!state.loading && state.requirements.length === 0"
      description="尚无需求"
    />

    <el-table
      v-else
      v-loading="state.loading"
      :data="state.requirements"
      data-testid="req-table"
    >
      <el-table-column prop="title" label="标题">
        <template #default="{ row }">
          <router-link :to="`/projects/${projectId}/requirements/${row.id}`">
            {{ row.title }}
          </router-link>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="110">
        <template #default="{ row }">
          {{ row.sourceType === "MARKDOWN" ? "Markdown" : "粘贴文本" }}
        </template>
      </el-table-column>
      <el-table-column label="当前版本" width="100">
        <template #default="{ row }">v{{ row.currentVersion }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="state.form.visible" title="新建需求" width="560px">
      <el-form label-position="top" @submit.prevent="submitCreate">
        <el-form-item label="标题">
          <el-input
            v-model="state.form.title"
            data-testid="req-title"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="来源">
          <el-radio-group
            v-model="state.form.sourceType"
            data-testid="req-source"
          >
            <el-radio value="PASTED_TEXT">粘贴文本</el-radio>
            <el-radio value="MARKDOWN">Markdown</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="需求正文">
          <el-input
            v-model="state.form.body"
            type="textarea"
            :rows="5"
            data-testid="req-body"
          />
        </el-form-item>
        <el-form-item label="验收标准（每行一条）">
          <el-input
            v-model="state.form.acceptanceCriteria"
            type="textarea"
            :rows="4"
            data-testid="req-criteria"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="state.form.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="state.form.submitting"
          data-testid="req-submit"
          @click="submitCreate"
        >
          创建
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.req-card {
  max-width: 860px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
