import { apiGet, apiSend } from "./http";

export interface StepRow {
  stepOrder?: number;
  action: string;
  testData?: string;
  expected: string;
}

export interface CaseRow {
  id: number;
  title: string;
  priority: "HIGH" | "MEDIUM" | "LOW";
  status: string;
  version: number;
  requirementId?: number | null;
  requirementTitle?: string | null;
  steps: StepRow[];
  caseType?: "MANUAL" | "API" | "WEB" | "ELECTRON" | "WINDOWS_NATIVE";
  execution?: Record<string, unknown> | null;
}

export interface BatchResultRow {
  id: number;
  status: number;
  message?: string | null;
  newVersion?: number | null;
}

export async function fetchCases(projectId: number): Promise<CaseRow[]> {
  return apiGet<CaseRow[]>(`/api/v1/projects/${projectId}/testcases`);
}

export async function createCase(
  projectId: number,
  payload: {
    title: string;
    priority: string;
    requirementId?: number | null;
    steps: StepRow[];
  },
): Promise<CaseRow> {
  return apiSend<CaseRow>(
    "POST",
    `/api/v1/projects/${projectId}/testcases`,
    payload,
  );
}

export async function updateCase(
  projectId: number,
  id: number,
  payload: {
    title: string;
    priority: string;
    requirementId?: number | null;
    expectedVersion: number;
    steps: StepRow[];
  },
): Promise<CaseRow> {
  return apiSend<CaseRow>(
    "PUT",
    `/api/v1/projects/${projectId}/testcases/${id}`,
    payload,
  );
}

export async function batchUpdateCases(
  projectId: number,
  ids: number[],
  fields: Record<string, string>,
  expectedVersion?: number,
): Promise<{ results: BatchResultRow[] }> {
  return apiSend<{ results: BatchResultRow[] }>(
    "POST",
    `/api/v1/projects/${projectId}/testcases/batch-update`,
    { ids, fields, expectedVersion },
  );
}

export interface OpenApiImportResult {
  created: number;
  caseIds: number[];
}

/** Imports an OpenAPI definition as reviewable API drafts. */
export async function importOpenApi(
  projectId: number,
  definition: string,
): Promise<OpenApiImportResult> {
  return apiSend<OpenApiImportResult>(
    "POST",
    `/api/v1/projects/${projectId}/testcases/import-openapi`,
    { definition },
  );
}
