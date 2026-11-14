<script setup lang="ts">
import { computed, onMounted, reactive, watch } from "vue";
import { useRoute } from "vue-router";
import {
  createGlobalKnowledge,
  createProjectKnowledge,
  fetchGlobalKnowledge,
  fetchProjectKnowledge,
  searchKnowledge,
  updateGlobalKnowledge,
  updateProjectKnowledge,
  type KnowledgeDocRow,
  type KnowledgeVersionRow,
  type SearchResponse,
} from "../api/knowledge";
import { fetchProjects, type ProjectRow } from "../api/projects";
import { ApiError } from "../api/http";
import { session } from "../stores/session";

const route = useRoute();

const state = reactive({
  projects: [] as ProjectRow[],
  projectId: null as number | null,
  tab: "project" as "global" | "project",
  docs: [] as KnowledgeDocRow[],
  loading: false,
  error: null as string | null,
  isAdmin: false,
  form: {
    visible: false,
    editingId: null as number | null,
    title: "",
    body: "",
    submitting: false,
  },
  versions: null as KnowledgeVersionRow[] | null,
  versionsTitle: "",
  search: {
    query: "",
    searching: false,
    result: null as SearchResponse | null,
  },
});

const scopeLabel = computed(() =>
  state.tab === "global" ? "全局知识" : "项目知识",
);

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

async function loadDocs(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.docs =
      state.tab === "global"
        ? await fetchGlobalKnowledge()
        : state.projectId
          ? await fetchProjectKnowledge(state.projectId)
          : [];
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

function openCreate(): void {
  state.form.editingId = null;
  state.form.title = "";
  state.form.body = "";
  state.form.visible = true;
}

async function openEdit(doc: KnowledgeDocRow): Promise<void> {
  state.form.editingId = doc.id;
  state.form.title = doc.title;
  try {
    const versions =
      state.tab === "global"
        ? await import("../api/knowledge").then((m) =>
            m.fetchKnowledgeVersions("global", doc.id),
          )
        : state.projectId
          ? await import("../api/knowledge").then((m) =>
              m.fetchProjectKnowledgeVersions(state.projectId!, doc.id),
            )
          : [];
    const latest = versions.at(-1);
    state.form.body = latest?.body ?? "";
  } catch {
    state.form.body = "";
  }
  state.form.visible = true;
}

async function showVersions(doc: KnowledgeDocRow): Promise<void> {
  state.versionsTitle = doc.title;
  state.versions =
    state.tab === "global"
      ? await import("../api/knowledge").then((m) =>
          m.fetchKnowledgeVersions("global", doc.id),
        )
      : state.projectId
        ? await import("../api/knowledge").then((m) =>
            m.fetchProjectKnowledgeVersions(state.projectId!, doc.id),
          )
        : null;
}

async function submitForm(): Promise<void> {
  state.form.submitting = true;
  state.error = null;
  try {
    const payload = { title: state.form.title, body: state.form.body };
    if (state.form.editingId === null) {
      if (state.tab === "global") {
        await createGlobalKnowledge(payload);
      } else if (state.projectId) {
        await createProjectKnowledge(state.projectId, payload);
      }
    } else if (state.tab === "global") {
      await updateGlobalKnowledge(state.form.editingId, payload);
    } else if (state.projectId) {
      await updateProjectKnowledge(
        state.projectId,
        state.form.editingId,
        payload,
      );
    }
    state.form.visible = false;
    await loadDocs();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.form.submitting = false;
  }
}

async function runSearch(): Promise<void> {
  state.search.searching = true;
  state.error = null;
  try {
    state.search.result = await searchKnowledge(
      state.search.query,
      state.projectId,
    );
  } catch (error) {
    state.error = error instanceof Error ? error.message : String(error);
  } finally {
    state.search.searching = false;
  }
}

onMounted(async () => {
  if (!session.loaded) {
    try {
      const { fetchMe } = await import("../api/auth");
      session.setFrom(await fetchMe());
    } catch {
      // unauthenticated; global editing stays hidden
    }
  }
  await loadProjects();
  await loadDocs();
});
watch(
  () => state.tab,
  () => loadDocs(),
);
watch(
  () => state.projectId,
  () => {
    if (state.tab === "project") {
      loadDocs();
    }
  },
);
watch(
  () => route.params.id,
  () => {
    state.projectId = Number(route.params.id) || null;
  },
);
</script>

<template>
  <el-card class="kb-card">
    <template #header>
      <div class="card-header">
        <span>知识库 — {{ scopeLabel }}</span>
        <div class="toolbar">
          <el-select
            v-model="state.tab"
            size="small"
            style="width: 120px"
            data-testid="kb-scope"
          >
            <el-option label="项目知识" value="project" />
            <el-option label="全局知识" value="global" />
          </el-select>
          <el-select
            v-if="state.tab === 'project'"
            v-model="state.projectId"
            size="small"
            style="width: 160px"
            data-testid="kb-project"
          >
            <el-option
              v-for="p in state.projects"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
          <el-button size="small" :loading="state.loading" @click="loadDocs"
            >刷新</el-button
          >
          <el-button
            size="small"
            type="primary"
            data-testid="kb-create"
            @click="openCreate"
          >
            新建知识
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

    <div class="search-row">
      <el-input
        v-model="state.search.query"
        placeholder="检索知识（授权范围内）"
        data-testid="kb-search-input"
        @keyup.enter="runSearch"
      />
      <el-button
        type="primary"
        :loading="state.search.searching"
        data-testid="kb-search"
        @click="runSearch"
      >
        检索
      </el-button>
    </div>

    <div
      v-if="state.search.result"
      class="search-results"
      data-testid="kb-search-results"
    >
      <el-alert
        v-if="state.search.result.embeddingStatus !== 'OK'"
        :type="
          state.search.result.embeddingStatus === 'ERROR' ? 'error' : 'info'
        "
        :closable="false"
        :title="
          state.search.result.embeddingStatus === 'NOT_CONFIGURED'
            ? '嵌入服务未配置：当前为关键词检索结果。'
            : '向量检索出错：' + (state.search.result.embeddingError ?? '')
        "
      />
      <el-empty
        v-if="state.search.result.results.length === 0"
        description="无匹配结果"
      />
      <el-card
        v-for="(hit, index) in state.search.result.results"
        :key="index"
        shadow="never"
        class="hit-card"
      >
        <div class="hit-meta">
          <el-tag size="small">{{
            hit.scope === "GLOBAL" ? "全局" : "项目"
          }}</el-tag>
          <strong>{{ hit.documentTitle }}</strong>
          <span class="hit-loc"
            >v{{ hit.version }} · 分块 #{{ hit.chunkIndex }}</span
          >
          <el-tag size="small" type="info">{{ hit.matchedBy }}</el-tag>
        </div>
        <div class="hit-content">{{ hit.content }}</div>
      </el-card>
    </div>

    <el-table
      v-if="!state.search.result"
      v-loading="state.loading"
      :data="state.docs"
      data-testid="kb-table"
    >
      <el-table-column prop="title" label="标题" />
      <el-table-column label="当前版本" width="100">
        <template #default="{ row }">v{{ row.currentVersion }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="showVersions(row)">历史</el-button>
          <el-button size="small" type="primary" @click="openEdit(row)"
            >编辑</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <el-empty
      v-if="!state.search.result && !state.loading && state.docs.length === 0"
      description="尚无知识文档"
    />

    <el-dialog
      v-model="state.form.visible"
      :title="state.form.editingId ? '编辑知识（产生新版本）' : '新建知识'"
      width="640px"
    >
      <el-form label-position="top" @submit.prevent="submitForm">
        <el-form-item label="标题">
          <el-input
            v-model="state.form.title"
            data-testid="kb-title"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="内容（粘贴文本或 Markdown，# 标题用于分块）">
          <el-input
            v-model="state.form.body"
            type="textarea"
            :rows="10"
            data-testid="kb-body"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="state.form.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="state.form.submitting"
          data-testid="kb-submit"
          @click="submitForm"
        >
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="!!state.versions"
      :title="`历史版本 — ${state.versionsTitle}`"
      width="640px"
      @close="state.versions = null"
    >
      <el-table
        v-if="state.versions"
        :data="state.versions"
        size="small"
        data-testid="kb-versions-table"
      >
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="200" />
        <el-table-column prop="body" label="内容" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.kb-card {
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

.search-row {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.search-results {
  margin-top: 8px;
}

.hit-card {
  margin-bottom: 8px;
}

.hit-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 4px;
}

.hit-loc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.hit-content {
  white-space: pre-wrap;
  font-size: 13px;
}
</style>
