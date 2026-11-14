import { apiGet, apiSend } from "./http";

export interface AgentQuestion {
  id: number;
  orderIndex: number;
  question: string;
  blocking: boolean;
  answer?: string | null;
  answered: boolean;
}

export interface AgentCoveragePoint {
  id: number;
  description: string;
  technique?: string | null;
  isAssumption: boolean;
  confirmed: boolean;
  userNote?: string | null;
}

export interface AgentTaskState {
  status: string;
  phase: string;
  attempts: number;
  maxAttempts: number;
  promptTokens: number;
  completionTokens: number;
  error: string;
}

export interface AgentSessionDetail {
  id: number;
  requirementId: number;
  status: string;
  model: string;
  promptTokens: number;
  completionTokens: number;
  error: string;
  task?: AgentTaskState | null;
  questions: AgentQuestion[];
  coveragePoints: AgentCoveragePoint[];
}

export async function startAgentSession(
  projectId: number,
  requirementId: number,
): Promise<AgentSessionDetail> {
  return apiSend<AgentSessionDetail>(
    "POST",
    `/api/v1/projects/${projectId}/agent/sessions`,
    {
      requirementId,
    },
  );
}

export async function fetchAgentSession(
  projectId: number,
  sessionId: number,
): Promise<AgentSessionDetail> {
  return apiGet<AgentSessionDetail>(
    `/api/v1/projects/${projectId}/agent/sessions/${sessionId}`,
  );
}

export async function answerQuestion(
  projectId: number,
  sessionId: number,
  questionId: number,
  answer: string,
): Promise<{ status: string }> {
  return apiSend<{ status: string }>(
    "POST",
    `/api/v1/projects/${projectId}/agent/sessions/${sessionId}/questions/${questionId}/answer`,
    { answer },
  );
}

export async function confirmCoverage(
  projectId: number,
  sessionId: number,
  confirmations: { pointId: number; confirmed: boolean; userNote?: string }[],
): Promise<{ status: string }> {
  return apiSend<{ status: string }>(
    "POST",
    `/api/v1/projects/${projectId}/agent/sessions/${sessionId}/coverage/confirm`,
    { confirmations },
  );
}

export async function cancelSession(
  projectId: number,
  sessionId: number,
): Promise<{ status: string }> {
  return apiSend<{ status: string }>(
    "POST",
    `/api/v1/projects/${projectId}/agent/sessions/${sessionId}/cancel`,
  );
}

export interface DraftCase {
  id: number;
  title: string;
  priority: string;
  technique: string;
  suggestion: string;
  duplicateOfCaseId?: number | null;
  knowledgeInsufficient: boolean;
  steps: { action: string; testData?: string; expected: string }[];
  citations: { documentId: number; version: number; documentTitle?: string }[];
}

export interface AgentToolCall {
  id: number;
  toolName: string;
  inputSummary: string;
  outputSummary: string;
}

export interface DraftBatchDetail {
  id: number;
  sessionId: number;
  status: string;
  model: string;
  promptVersion: string;
  promptTokens: number;
  completionTokens: number;
  error: string;
  cases: DraftCase[];
  toolCalls: AgentToolCall[];
  requirementId: number;
}

export async function generateDraft(
  projectId: number,
  sessionId: number,
): Promise<DraftBatchDetail> {
  return apiSend<DraftBatchDetail>(
    "POST",
    `/api/v1/projects/${projectId}/agent/sessions/${sessionId}/generate-draft`,
  );
}

export async function fetchDraft(
  projectId: number,
  sessionId: number,
): Promise<DraftBatchDetail> {
  return apiGet<DraftBatchDetail>(
    `/api/v1/projects/${projectId}/agent/sessions/${sessionId}/draft`,
  );
}

export async function acceptDraft(
  projectId: number,
  sessionId: number,
): Promise<{ status: string; createdCaseIds: number[] }> {
  return apiSend<{ status: string; createdCaseIds: number[] }>(
    "POST",
    `/api/v1/projects/${projectId}/agent/sessions/${sessionId}/draft/accept`,
  );
}
