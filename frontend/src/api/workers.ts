import { apiGet, apiSend } from "./http";

export type WorkerStatus = "ONLINE" | "OFFLINE" | "NEVER_SEEN";

export type ExecutorCapability =
  | "DIAGNOSTIC"
  | "API"
  | "WEB"
  | "ELECTRON"
  | "ANDROID"
  | "WINDOWS_NATIVE"
  | "PERFORMANCE"
  | "WHITEBOX";

export interface WorkerRow {
  id: number;
  name: string;
  capabilities: ExecutorCapability[];
  version?: string | null;
  maxConcurrency: number;
  status: WorkerStatus;
  lastHeartbeatAt?: string | null;
}

export interface WorkerRegistration extends WorkerRow {
  /** Shown exactly once at registration; never returned again. */
  token: string;
}

export interface DiagnosticCheck {
  name: string;
  ok: boolean;
  detail: string;
}

export interface DiagnosticRow {
  id: number;
  jobId?: number | null;
  status: "PASSED" | "ERROR";
  checks: DiagnosticCheck[];
  createdAt: string;
}

export interface QueueJobRow {
  id: number;
  jobType: string;
  workerId?: number | null;
  status:
    | "QUEUED"
    | "RUNNING"
    | "PASSED"
    | "ASSERTION_FAILED"
    | "ERROR"
    | "CANCELLED"
    | "TIMED_OUT";
  createdAt: string;
}

export interface QueueStats {
  counts: Record<string, number>;
  total: number;
}

export async function fetchWorkers(): Promise<WorkerRow[]> {
  return apiGet<WorkerRow[]>("/api/v1/system/workers");
}

export async function registerWorker(payload: {
  name: string;
  capabilities: ExecutorCapability[];
  version?: string;
  maxConcurrency?: number;
}): Promise<WorkerRegistration> {
  return apiSend<WorkerRegistration>("POST", "/api/v1/system/workers", payload);
}

export async function triggerDiagnostic(
  workerId: number,
): Promise<{ jobId: number; status: string }> {
  return apiSend<{ jobId: number; status: string }>(
    "POST",
    `/api/v1/system/workers/${workerId}/diagnostics`,
    {},
  );
}

export async function fetchWorkerDiagnostics(
  workerId: number,
): Promise<DiagnosticRow[]> {
  return apiGet<DiagnosticRow[]>(
    `/api/v1/system/workers/${workerId}/diagnostics`,
  );
}

export async function fetchQueueJobs(status?: string): Promise<QueueJobRow[]> {
  const suffix = status ? `?status=${encodeURIComponent(status)}` : "";
  return apiGet<QueueJobRow[]>(`/api/v1/system/queue/jobs${suffix}`);
}

export async function cancelJob(jobId: number): Promise<QueueJobRow> {
  return apiSend<QueueJobRow>(
    "POST",
    `/api/v1/system/queue/jobs/${jobId}/cancel`,
    {},
  );
}

export async function fetchQueueStats(): Promise<QueueStats> {
  return apiGet<QueueStats>("/api/v1/system/queue/stats");
}
