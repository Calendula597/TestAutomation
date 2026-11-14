import { apiGet, apiSend } from "./http";

export interface DiffStep {
  stepOrder: number;
  action: string;
  testData?: string;
  expected: string;
}

export interface DiffSide {
  title: string;
  priority: string;
  requirementId?: number | null;
  steps: DiffStep[];
}

export interface DiffResponse {
  previousStatus: string;
  previous?: DiffSide | null;
  current: DiffSide;
}

export interface ReviewVersionRow {
  version: number;
  status: string;
  title: string;
  priority: string;
  publishedBy: string;
  publishedAt: string;
  publishBasis?: string | null;
  origin: string;
}

export async function fetchReviewDiff(
  projectId: number,
  caseId: number,
): Promise<DiffResponse> {
  return apiGet<DiffResponse>(
    `/api/v1/projects/${projectId}/testcases/${caseId}/review/diff`,
  );
}

export async function publishCase(
  projectId: number,
  caseId: number,
  payload: { basis: string; expectedVersion: number },
): Promise<{ version: number; status: string; publishedBy: string }> {
  return apiSend<{ version: number; status: string; publishedBy: string }>(
    "POST",
    `/api/v1/projects/${projectId}/testcases/${caseId}/review/publish`,
    payload,
  );
}

export async function fetchReviewVersions(
  projectId: number,
  caseId: number,
): Promise<ReviewVersionRow[]> {
  return apiGet<ReviewVersionRow[]>(
    `/api/v1/projects/${projectId}/testcases/${caseId}/review/versions`,
  );
}
