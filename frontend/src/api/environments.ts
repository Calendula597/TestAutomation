import { apiGet, apiSend } from "./http";

export interface EnvironmentSummary {
  id: number;
  name: string;
  currentVersion: number;
  createdAt: string;
}

export interface SecretView {
  key: string;
  hasValue: boolean;
  maskedValue: string;
}

export interface EnvironmentDetail {
  id: number;
  projectId: number;
  name: string;
  currentVersion: number;
  plainVariables: Record<string, string>;
  secrets: SecretView[];
  targets: string[];
  createdAt: string;
}

export interface EnvironmentVersionRow {
  version: number;
  plainVariables: Record<string, string>;
  secrets: SecretView[];
  targets: string[];
  createdAt: string;
}

export interface TargetCheck {
  url: string;
  allowed: boolean;
  matchedTarget?: string | null;
  reason?: string | null;
}

export interface TargetValidationResponse {
  allowed: boolean;
  checks: TargetCheck[];
}

/**
 * Secret writes are a delta: a string replaces the stored value, null
 * removes the key, and omitted keys keep their ciphertext server-side.
 */
export interface EnvironmentUpdatePayload {
  name?: string;
  plainVariables?: Record<string, string>;
  secretWrites?: Record<string, string | null>;
  targets?: string[];
}

export async function fetchEnvironments(
  projectId: number,
): Promise<EnvironmentSummary[]> {
  return apiGet<EnvironmentSummary[]>(
    `/api/v1/projects/${projectId}/environments`,
  );
}

export async function fetchEnvironment(
  projectId: number,
  environmentId: number,
): Promise<EnvironmentDetail> {
  return apiGet<EnvironmentDetail>(
    `/api/v1/projects/${projectId}/environments/${environmentId}`,
  );
}

export async function fetchEnvironmentVersions(
  projectId: number,
  environmentId: number,
): Promise<EnvironmentVersionRow[]> {
  return apiGet<EnvironmentVersionRow[]>(
    `/api/v1/projects/${projectId}/environments/${environmentId}/versions`,
  );
}

export async function createEnvironment(
  projectId: number,
  payload: EnvironmentUpdatePayload,
): Promise<EnvironmentDetail> {
  return apiSend<EnvironmentDetail>(
    "POST",
    `/api/v1/projects/${projectId}/environments`,
    payload,
  );
}

export async function updateEnvironment(
  projectId: number,
  environmentId: number,
  payload: EnvironmentUpdatePayload,
): Promise<EnvironmentDetail> {
  return apiSend<EnvironmentDetail>(
    "PUT",
    `/api/v1/projects/${projectId}/environments/${environmentId}`,
    payload,
  );
}

export async function validateTarget(
  projectId: number,
  environmentId: number,
  payload: { url: string; redirectUrl?: string },
): Promise<TargetValidationResponse> {
  return apiSend<TargetValidationResponse>(
    "POST",
    `/api/v1/projects/${projectId}/environments/${environmentId}/validate-target`,
    payload,
  );
}
