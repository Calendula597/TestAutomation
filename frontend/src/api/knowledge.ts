import { apiGet, apiSend } from "./http";

export type KnowledgeScope = "GLOBAL" | "PROJECT";

export interface KnowledgeDocRow {
  id: number;
  title: string;
  currentVersion: number;
  scope: KnowledgeScope;
  projectId?: number | null;
}

export interface KnowledgeVersionRow {
  version: number;
  body: string;
  createdAt: string;
}

export interface KnowledgeHit {
  documentId: number;
  documentTitle: string;
  version: number;
  chunkIndex: number;
  heading?: string | null;
  content: string;
  scope: KnowledgeScope;
  projectId?: number | null;
  score: number;
  matchedBy: "KEYWORD" | "VECTOR";
}

export interface SearchResponse {
  results: KnowledgeHit[];
  embeddingStatus: "NOT_CONFIGURED" | "OK" | "ERROR";
  embeddingError?: string | null;
}

export async function fetchGlobalKnowledge(): Promise<KnowledgeDocRow[]> {
  return apiGet<KnowledgeDocRow[]>("/api/v1/knowledge/global");
}

export async function fetchProjectKnowledge(
  projectId: number,
): Promise<KnowledgeDocRow[]> {
  return apiGet<KnowledgeDocRow[]>(`/api/v1/projects/${projectId}/knowledge`);
}

export async function createGlobalKnowledge(payload: {
  title: string;
  body: string;
}): Promise<KnowledgeDocRow> {
  return apiSend<KnowledgeDocRow>("POST", "/api/v1/knowledge/global", payload);
}

export async function createProjectKnowledge(
  projectId: number,
  payload: { title: string; body: string },
): Promise<KnowledgeDocRow> {
  return apiSend<KnowledgeDocRow>(
    "POST",
    `/api/v1/projects/${projectId}/knowledge`,
    payload,
  );
}

export async function updateGlobalKnowledge(
  id: number,
  payload: { title: string; body: string },
): Promise<KnowledgeDocRow> {
  return apiSend<KnowledgeDocRow>(
    "PUT",
    `/api/v1/knowledge/global/${id}`,
    payload,
  );
}

export async function updateProjectKnowledge(
  projectId: number,
  id: number,
  payload: { title: string; body: string },
): Promise<KnowledgeDocRow> {
  return apiSend<KnowledgeDocRow>(
    "PUT",
    `/api/v1/projects/${projectId}/knowledge/${id}`,
    payload,
  );
}

export async function fetchKnowledgeVersions(
  scope: "global",
  id: number,
): Promise<KnowledgeVersionRow[]> {
  return apiGet<KnowledgeVersionRow[]>(
    `/api/v1/knowledge/global/${id}/versions`,
  );
}

export async function fetchProjectKnowledgeVersions(
  projectId: number,
  id: number,
): Promise<KnowledgeVersionRow[]> {
  return apiGet<KnowledgeVersionRow[]>(
    `/api/v1/projects/${projectId}/knowledge/${id}/versions`,
  );
}

export async function searchKnowledge(
  query: string,
  projectId?: number | null,
): Promise<SearchResponse> {
  const suffix = projectId ? `&projectId=${projectId}` : "";
  return apiGet<SearchResponse>(
    `/api/v1/knowledge/search?q=${encodeURIComponent(query)}${suffix}`,
  );
}
