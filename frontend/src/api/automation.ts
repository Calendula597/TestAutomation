import { apiGet, apiSend } from "./http";

export interface ScheduleRow {
  id: number;
  caseVersionId: number;
  environmentId: number;
  name: string;
  cronExpression: string;
  timeZone: string;
  allowOverlap: boolean;
  enabled: boolean;
  nextRunAt: string;
  lastFiredAt: string | null;
}

export interface ScheduleInput {
  caseId: number;
  environmentId: number;
  name: string;
  cronExpression: string;
  timeZone: string;
  allowOverlap: boolean;
  enabled: boolean;
  confirmed?: boolean;
}

export interface CiTokenRow {
  id: number;
  name: string;
  prefix: string;
  scopes: string[];
  expiresAt: string;
  revokedAt: string | null;
  lastUsedAt: string | null;
  createdAt: string;
}

export async function fetchSchedules(
  projectId: number,
): Promise<ScheduleRow[]> {
  return apiGet(`/api/v1/projects/${projectId}/schedules`);
}

export async function createSchedule(
  projectId: number,
  payload: ScheduleInput,
): Promise<ScheduleRow> {
  return apiSend("POST", `/api/v1/projects/${projectId}/schedules`, payload);
}

export async function updateSchedule(
  projectId: number,
  id: number,
  payload: Partial<ScheduleInput>,
): Promise<ScheduleRow> {
  return apiSend(
    "PUT",
    `/api/v1/projects/${projectId}/schedules/${id}`,
    payload,
  );
}

export async function fetchCiTokens(projectId: number): Promise<CiTokenRow[]> {
  return apiGet(`/api/v1/projects/${projectId}/ci-tokens`);
}

export async function createCiToken(
  projectId: number,
  payload: { name: string; scopes: string[]; expiresAt: string },
): Promise<CiTokenRow & { token: string }> {
  return apiSend("POST", `/api/v1/projects/${projectId}/ci-tokens`, payload);
}

export async function revokeCiToken(
  projectId: number,
  id: number,
): Promise<void> {
  return apiSend("DELETE", `/api/v1/projects/${projectId}/ci-tokens/${id}`);
}
