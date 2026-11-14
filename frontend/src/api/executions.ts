import { apiGet, apiSend } from "./http";

export type ExecutionStatus =
  | "QUEUED"
  | "RUNNING"
  | "PASSED"
  | "ASSERTION_FAILED"
  | "ERROR"
  | "CANCELLED"
  | "TIMED_OUT";

export interface ExecutionRow {
  id: number;
  jobType: string;
  status: ExecutionStatus;
  caseVersionId?: number | null;
  environmentId?: number | null;
  createdAt: string;
}

export interface MaskedAssertion {
  type: string;
  path?: string;
  expected: string;
  actual: string;
  passed: boolean;
}

export interface ExecutionResult {
  status: string;
  reason?: string | null;
  request?: {
    method: string;
    url: string;
    headers: Record<string, string>;
    body?: string;
  } | null;
  response?: {
    statusCode: number;
    headers: Record<string, string>;
    body: string;
    bodyTruncated: boolean;
  } | null;
  assertions: MaskedAssertion[];
  durationMs: number;
  redirects: string[];
}

export interface ExecutionAttempt {
  attemptNumber: number;
  status: ExecutionStatus;
  startedAt?: string | null;
  finishedAt?: string | null;
  result?: ExecutionResult | null;
}

export interface ExecutionDetail extends ExecutionRow {
  attempts: ExecutionAttempt[];
}

export async function fetchExecutions(
  projectId: number,
): Promise<ExecutionRow[]> {
  return apiGet<ExecutionRow[]>(`/api/v1/projects/${projectId}/executions`);
}

export async function fetchExecutionDetail(
  projectId: number,
  jobId: number,
): Promise<ExecutionDetail> {
  return apiGet<ExecutionDetail>(
    `/api/v1/projects/${projectId}/executions/${jobId}`,
  );
}

export async function triggerExecution(
  projectId: number,
  payload: { caseId: number; environmentId: number },
): Promise<{ jobId: number; status: string }> {
  return apiSend<{ jobId: number; status: string }>(
    "POST",
    `/api/v1/projects/${projectId}/executions`,
    payload,
  );
}
