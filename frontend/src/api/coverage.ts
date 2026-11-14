import { apiGet } from "./http";

export interface CaseVersionTrace {
  versionId: number;
  title: string;
  version: number;
  latestRunStatus: string;
  lastExecutedAt?: string | null;
}

export interface CoverageRow {
  requirementId: number;
  title: string;
  currentVersion: number;
  status:
    | "NOT_COVERED"
    | "GENERATING"
    | "PENDING_REVIEW"
    | "COVERED_POINTS_ONLY"
    | "COVERED"
    | "EXEC_PASSED"
    | "EXEC_FAILED";
  confirmedCoveragePoints: number;
  pendingDrafts: number;
  caseVersions: CaseVersionTrace[];
}

export const COVERAGE_STATUS_LABELS: Record<CoverageRow["status"], string> = {
  NOT_COVERED: "未覆盖",
  GENERATING: "生成中",
  PENDING_REVIEW: "待审核",
  COVERED_POINTS_ONLY: "仅覆盖点",
  COVERED: "已覆盖",
  EXEC_PASSED: "执行通过",
  EXEC_FAILED: "执行失败",
};

export const COVERAGE_STATUS_TYPES: Record<CoverageRow["status"], string> = {
  NOT_COVERED: "danger",
  GENERATING: "warning",
  PENDING_REVIEW: "warning",
  COVERED_POINTS_ONLY: "info",
  COVERED: "primary",
  EXEC_PASSED: "success",
  EXEC_FAILED: "danger",
};

export const RUN_STATUS_LABELS: Record<string, string> = {
  NOT_EXECUTED: "未执行",
  PENDING: "待执行",
  PASSED: "通过",
  FAILED: "失败",
  BLOCKED: "阻塞",
  SKIPPED: "跳过",
};

export async function fetchCoverage(projectId: number): Promise<CoverageRow[]> {
  return apiGet<CoverageRow[]>(`/api/v1/projects/${projectId}/coverage`);
}
