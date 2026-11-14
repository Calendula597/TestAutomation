<script setup lang="ts">
import { reactive } from "vue";
import {
  answerQuestion,
  cancelSession,
  confirmCoverage,
  fetchAgentSession,
  startAgentSession,
  type AgentSessionDetail,
} from "../api/agent";
import { ApiError } from "../api/http";

const props = defineProps<{ projectId: number; requirementId: number }>();

const state = reactive({
  session: null as AgentSessionDetail | null,
  error: null as string | null,
  loading: false,
  answers: {} as Record<number, string>,
  notes: {} as Record<number, string>,
  confirming: false,
});

const STATUS_LABELS: Record<string, string> = {
  ANALYZING: "分析中",
  WAITING_CLARIFICATION: "等待澄清回答",
  COVERAGE_CONFIRMATION: "覆盖点确认",
  READY_FOR_DRAFT: "可生成草稿",
  CANCELLED: "已取消",
  FAILED: "失败",
};

const PHASE_LABELS: Record<string, string> = {
  RETRIEVING: "检索授权知识",
  THINKING: "模型分析",
  PERSISTING: "保存结果",
};

async function start(): Promise<void> {
  state.loading = true;
  state.error = null;
  try {
    state.session = await startAgentSession(
      props.projectId,
      props.requirementId,
    );
    await pollUntilQuiet();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.loading = false;
  }
}

async function pollUntilQuiet(): Promise<void> {
  if (!state.session) return;
  for (let i = 0; i < 60; i++) {
    await sleep(1000);
    state.session = await fetchAgentSession(props.projectId, state.session.id);
    const task = state.session.task;
    if (
      task &&
      (task.status === "DONE" ||
        task.status === "FAILED" ||
        task.status === "CANCELLED")
    ) {
      return;
    }
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function load(): Promise<void> {
  if (!state.session) return;
  state.session = await fetchAgentSession(props.projectId, state.session.id);
}

async function saveAnswer(questionId: number): Promise<void> {
  if (!state.session) return;
  state.error = null;
  try {
    await answerQuestion(
      props.projectId,
      state.session.id,
      questionId,
      state.answers[questionId] ?? "",
    );
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

async function toggleConfirm(
  pointId: number,
  confirmed: boolean,
): Promise<void> {
  if (!state.session) return;
  state.error = null;
  try {
    await confirmCoverage(props.projectId, state.session.id, [
      { pointId, confirmed, userNote: state.notes[pointId] ?? "" },
    ]);
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  }
}

async function confirmAll(): Promise<void> {
  if (!state.session) return;
  state.confirming = true;
  state.error = null;
  try {
    const confirmations = state.session.coveragePoints.map((p) => ({
      pointId: p.id,
      confirmed: true,
      userNote: state.notes[p.id] ?? "",
    }));
    await confirmCoverage(props.projectId, state.session.id, confirmations);
    await load();
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : String(error);
  } finally {
    state.confirming = false;
  }
}

async function cancel(): Promise<void> {
  if (!state.session) return;
  await cancelSession(props.projectId, state.session.id);
  await load();
}
</script>

<template>
  <el-card class="agent-card" data-testid="agent-panel">
    <template #header>
      <div class="card-header">
        <span>Agent 澄清与覆盖点</span>
        <div v-if="!state.session">
          <el-button
            size="small"
            type="primary"
            :loading="state.loading"
            data-testid="agent-start"
            @click="start"
          >
            开始 AI 分析
          </el-button>
        </div>
        <div v-else>
          <el-button size="small" @click="load">刷新</el-button>
          <el-button
            v-if="
              !['CANCELLED', 'FAILED', 'READY_FOR_DRAFT'].includes(
                state.session.status,
              )
            "
            size="small"
            data-testid="agent-cancel"
            @click="cancel"
          >
            取消
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

    <template v-if="state.session">
      <div class="status-row" data-testid="agent-status">
        <el-tag
          :type="state.session.status === 'FAILED' ? 'danger' : 'primary'"
        >
          {{ STATUS_LABELS[state.session.status] ?? state.session.status }}
        </el-tag>
        <span v-if="state.session.task" class="phase">
          阶段：{{
            PHASE_LABELS[state.session.task.phase] ?? state.session.task.phase
          }}（尝试 {{ state.session.task.attempts }}/{{
            state.session.task.maxAttempts
          }}）
        </span>
        <span class="usage">
          模型 {{ state.session.model || "—" }} · tokens
          {{ state.session.promptTokens }}+
          {{ state.session.completionTokens }}
        </span>
      </div>

      <el-alert
        v-if="state.session.error"
        type="error"
        :closable="false"
        show-icon
        :title="state.session.error"
      />

      <h5 class="section-title">澄清问题</h5>
      <div
        v-for="q in state.session.questions"
        :key="q.id"
        class="question-block"
        :data-testid="`agent-question-${q.id}`"
      >
        <div class="question-text">
          <el-tag v-if="q.blocking" size="small" type="danger">关键</el-tag>
          {{ q.question }}
        </div>
        <div class="answer-row">
          <el-input
            v-model="state.answers[q.id]"
            placeholder="回答澄清…"
            size="small"
            :data-testid="`agent-answer-${q.id}`"
          />
          <el-button size="small" type="primary" @click="saveAnswer(q.id)"
            >提交回答</el-button
          >
        </div>
        <div v-if="q.answered" class="answered">已回答：{{ q.answer }}</div>
      </div>

      <h5 class="section-title">覆盖点</h5>
      <div
        v-for="p in state.session.coveragePoints"
        :key="p.id"
        class="coverage-block"
        :data-testid="`agent-point-${p.id}`"
      >
        <div class="coverage-head">
          <el-checkbox
            :model-value="p.confirmed"
            :data-testid="`agent-confirm-${p.id}`"
            @change="(v: unknown) => toggleConfirm(p.id, Boolean(v))"
          >
            确认：{{ p.description }}
          </el-checkbox>
          <el-tag v-if="p.technique" size="small" type="info">{{
            p.technique
          }}</el-tag>
        </div>
        <el-input
          v-model="state.notes[p.id]"
          size="small"
          placeholder="备注（可选）"
          @change="toggleConfirm(p.id, p.confirmed)"
        />
      </div>
      <el-button
        v-if="state.session.coveragePoints.length > 0"
        size="small"
        type="primary"
        :loading="state.confirming"
        data-testid="agent-confirm-all"
        @click="confirmAll"
      >
        全部确认
      </el-button>
    </template>
    <el-empty
      v-else-if="!state.loading && !state.error"
      description="尚未开始分析"
    />
  </el-card>
</template>

<style scoped>
.agent-card {
  max-width: 860px;
  margin-top: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.status-row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 8px;
}

.phase,
.usage {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.section-title {
  margin: 16px 0 8px;
}

.question-block {
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  padding: 8px;
  margin-bottom: 8px;
}

.question-text {
  margin-bottom: 6px;
}

.answer-row {
  display: flex;
  gap: 8px;
}

.answered {
  margin-top: 6px;
  color: var(--el-color-success);
  font-size: 13px;
}

.coverage-block {
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  padding: 8px;
  margin-bottom: 8px;
}

.coverage-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
</style>
