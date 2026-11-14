export interface ComponentHealth {
  status: "UP" | "DOWN";
  latencyMs?: number;
  detail?: Record<string, string>;
  error?: string;
}

export interface HealthResponse {
  status: "UP" | "DOWN";
  checkedAt?: string;
  components?: Record<string, ComponentHealth>;
}

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await fetch("/api/v1/health");
  if (!response.ok) {
    throw new Error(`健康接口返回 HTTP ${response.status}`);
  }
  return (await response.json()) as HealthResponse;
}
