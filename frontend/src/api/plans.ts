import { apiGet, apiSend } from "./http";

export interface PlanRow {
  id: number;
  name: string;
  status: string;
}

export interface PlanItemRow {
  id: number;
  orderIndex: number;
  caseVersionId: number;
  caseTitle: string;
  caseVersion: number;
  caseStatus: string;
}

export interface PlanDetail {
  id: number;
  name: string;
  status: string;
  items: PlanItemRow[];
}

export interface PublishedVersionRow {
  versionId: number;
  caseId: number;
  caseTitle: string;
  version: number;
  priority: string;
}

export interface RunItemRow {
  id: number;
  planItemId: number;
  orderIndex: number;
  status: "PENDING" | "PASSED" | "FAILED" | "BLOCKED" | "SKIPPED";
  actualResult?: string | null;
  note?: string | null;
  evidenceUrl?: string | null;
  caseTitle: string;
  caseVersion: number;
  steps: {
    stepOrder: number;
    action: string;
    testData?: string;
    expected: string;
  }[];
}

export interface RunDetail {
  id: number;
  planId: number;
  planName: string;
  status: string;
  executedBy: string;
  startedAt?: string | null;
  completedAt?: string | null;
  items: RunItemRow[];
}

export interface RunReport {
  summary: {
    total: number;
    passed: number;
    failed: number;
    blocked: number;
    skipped: number;
    pending: number;
    executedBy: string;
    startedAt?: string | null;
    completedAt?: string | null;
    runStatus: string;
  };
  items: RunItemRow[];
}

export async function fetchPlans(projectId: number): Promise<PlanRow[]> {
  return apiGet<PlanRow[]>(`/api/v1/projects/${projectId}/plans`);
}

export async function createPlan(
  projectId: number,
  payload: { name: string; caseVersionIds: number[] },
): Promise<PlanDetail> {
  return apiSend<PlanDetail>(
    "POST",
    `/api/v1/projects/${projectId}/plans`,
    payload,
  );
}

export async function fetchPlan(
  projectId: number,
  planId: number,
): Promise<PlanDetail> {
  return apiGet<PlanDetail>(`/api/v1/projects/${projectId}/plans/${planId}`);
}

export async function fetchPublishedVersions(
  projectId: number,
): Promise<PublishedVersionRow[]> {
  return apiGet<PublishedVersionRow[]>(
    `/api/v1/projects/${projectId}/testcases/published`,
  );
}

export async function startRun(
  projectId: number,
  planId: number,
): Promise<{ runId: number }> {
  return apiSend<{ runId: number }>(
    "POST",
    `/api/v1/projects/${projectId}/plans/${planId}/runs`,
  );
}

export async function fetchRuns(
  projectId: number,
  planId: number,
): Promise<{ id: number; status: string; executedBy: string }[]> {
  return apiGet<{ id: number; status: string; executedBy: string }[]>(
    `/api/v1/projects/${projectId}/plans/${planId}/runs`,
  );
}

export async function fetchRun(
  projectId: number,
  planId: number,
  runId: number,
): Promise<RunDetail> {
  return apiGet<RunDetail>(
    `/api/v1/projects/${projectId}/plans/${planId}/runs/${runId}`,
  );
}

export async function recordRunItem(
  projectId: number,
  planId: number,
  runId: number,
  itemId: number,
  payload: {
    status: string;
    actualResult?: string;
    note?: string;
    evidenceUrl?: string;
  },
): Promise<RunDetail> {
  return apiSend<RunDetail>(
    "PUT",
    `/api/v1/projects/${projectId}/plans/${planId}/runs/${runId}/items/${itemId}`,
    payload,
  );
}

export async function completeRun(
  projectId: number,
  planId: number,
  runId: number,
): Promise<RunDetail> {
  return apiSend<RunDetail>(
    "POST",
    `/api/v1/projects/${projectId}/plans/${planId}/runs/${runId}/complete`,
  );
}

export async function fetchRunReport(
  projectId: number,
  planId: number,
  runId: number,
): Promise<RunReport> {
  return apiGet<RunReport>(
    `/api/v1/projects/${projectId}/plans/${planId}/runs/${runId}/report`,
  );
}
