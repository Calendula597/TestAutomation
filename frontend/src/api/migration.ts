import { apiSend } from "./http";

export interface ImportPreviewRow {
  rowNumber: number;
  title: string;
  priority: string;
  steps: {
    stepOrder: number;
    action: string;
    testData?: string;
    expected: string;
  }[];
  errors: string[];
}

export interface ImportPreview {
  totalRows: number;
  rows: ImportPreviewRow[];
  columnCount: number;
}

export interface ImportMapping {
  titleColumn: number;
  priorityColumn?: number | null;
  stepsColumn?: number | null;
}

export async function previewImport(
  projectId: number,
  file: File,
  mapping: ImportMapping,
): Promise<ImportPreview> {
  const form = new FormData();
  form.append("file", file);
  form.append("mapping", JSON.stringify(mapping));
  const csrfCookie = document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="));
  const headers: Record<string, string> = {};
  if (csrfCookie) {
    headers["X-XSRF-TOKEN"] = decodeURIComponent(
      csrfCookie.slice("XSRF-TOKEN=".length),
    );
  }
  const response = await fetch(
    `/api/v1/projects/${projectId}/testcases/import/preview`,
    {
      method: "POST",
      credentials: "same-origin",
      headers,
      body: form,
    },
  );
  if (!response.ok) {
    let message = `接口返回 HTTP ${response.status}`;
    try {
      const payload = (await response.json()) as { message?: string };
      if (payload?.message) {
        message = payload.message;
      }
    } catch {
      // non-JSON error body
    }
    throw new Error(message);
  }
  return (await response.json()) as ImportPreview;
}

export async function commitImport(
  projectId: number,
  rows: {
    title: string;
    priority: string;
    steps: { action: string; testData?: string; expected: string }[];
  }[],
): Promise<{
  created: number;
  results: { rowNumber: number; status: number; errors?: string[] }[];
}> {
  return apiSend(
    "POST",
    `/api/v1/projects/${projectId}/testcases/import/commit`,
    { rows },
  );
}
