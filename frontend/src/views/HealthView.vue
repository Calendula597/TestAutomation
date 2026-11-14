<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { fetchHealth, type HealthResponse } from "../api/health";

const loading = ref(false);
const health = ref<HealthResponse | null>(null);
const connectionError = ref<string | null>(null);

const overall = computed(() => {
  if (loading.value && !health.value) {
    return { tag: "info" as const, text: "检查中" };
  }
  if (health.value?.status === "UP") {
    return { tag: "success" as const, text: "正常" };
  }
  return { tag: "danger" as const, text: "异常" };
});

const database = computed(() => health.value?.components?.["database"]);

async function load(): Promise<void> {
  loading.value = true;
  connectionError.value = null;
  try {
    health.value = await fetchHealth();
  } catch (error) {
    health.value = null;
    connectionError.value =
      error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <el-card class="health-card">
    <template #header>
      <div class="card-header">
        <span>系统健康</span>
        <el-button :loading="loading" @click="load"> 刷新 </el-button>
      </div>
    </template>

    <el-alert
      v-if="connectionError"
      type="error"
      :closable="false"
      show-icon
      title="后端不可达"
      :description="`无法读取健康状态：${connectionError}`"
    />

    <el-descriptions v-else-if="health" :column="1" border>
      <el-descriptions-item label="整体状态">
        <el-tag :type="overall.tag">
          {{ overall.text }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="检查时间">
        {{ health.checkedAt ?? "—" }}
      </el-descriptions-item>
      <el-descriptions-item label="数据库">
        <el-tag :type="database?.status === 'UP' ? 'success' : 'danger'">
          {{ database?.status === "UP" ? "正常" : "异常" }}
        </el-tag>
        <span v-if="database?.latencyMs != null" class="muted"
          >（{{ database.latencyMs }} ms）</span
        >
        <span v-if="database?.error" class="error-text">{{
          database.error
        }}</span>
      </el-descriptions-item>
      <el-descriptions-item
        v-if="database?.detail?.['version']"
        label="数据库版本"
      >
        {{ database?.detail?.["version"] }}
      </el-descriptions-item>
    </el-descriptions>

    <p v-else>暂无健康数据。</p>
  </el-card>
</template>

<style scoped>
.health-card {
  max-width: 720px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.muted {
  color: var(--el-text-color-secondary);
}

.error-text {
  color: var(--el-color-danger);
}
</style>
