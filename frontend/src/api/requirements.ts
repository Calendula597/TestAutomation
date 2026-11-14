import { apiGet, apiSend } from "./http";

export type SourceType = "PASTED_TEXT" | "MARKDOWN";

export interface RequirementRow {
  id: number;
  title: string;
  currentVersion: number;
  body: string;
  sourceType: SourceType;
  acceptanceCriteria: string;
}

export interface RequirementVersionRow {
  version: number;
  body: string;
  sourceType: string;
  acceptanceCriteria: string;
  createdBy: string;
  createdAt: string;
}

export async function fetchRequirements(
  projectId: number,
): Promise<RequirementRow[]> {
  return apiGet<RequirementRow[]>(`/api/v1/projects/${projectId}/requirements`);
}

export async function createRequirement(
  projectId: number,
  payload: {
    title: string;
    body: string;
    sourceType: SourceType;
    acceptanceCriteria: string;
  },
): Promise<RequirementRow> {
  return apiSend<RequirementRow>(
    "POST",
    `/api/v1/projects/${projectId}/requirements`,
    payload,
  );
}

export async function fetchRequirement(
  projectId: number,
  id: number,
): Promise<RequirementRow> {
  return apiGet<RequirementRow>(
    `/api/v1/projects/${projectId}/requirements/${id}`,
  );
}

export async function updateRequirement(
  projectId: number,
  id: number,
  payload: {
    title?: string;
    body?: string;
    sourceType?: SourceType;
    acceptanceCriteria?: string;
  },
): Promise<RequirementRow> {
  return apiSend<RequirementRow>(
    "PUT",
    `/api/v1/projects/${projectId}/requirements/${id}`,
    payload,
  );
}

export async function fetchRequirementVersions(
  projectId: number,
  id: number,
): Promise<RequirementVersionRow[]> {
  return apiGet<RequirementVersionRow[]>(
    `/api/v1/projects/${projectId}/requirements/${id}/versions`,
  );
}
