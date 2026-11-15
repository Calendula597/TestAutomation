import { executeWhitebox } from "./executor.js";

const controlPlaneUrl = (process.env.CONTROL_PLANE_URL || "").replace(/\/$/, "");
const workerToken = process.env.WORKER_TOKEN || "";
const runOnce = process.env.WORKER_ONCE === "1";
const pollIntervalMs = Number(process.env.WORKER_POLL_INTERVAL_MS || 1000);

if (!controlPlaneUrl || !workerToken) {
  throw new Error("必须配置 CONTROL_PLANE_URL 和 WORKER_TOKEN");
}

async function post(path, body) {
  const response = await fetch(`${controlPlaneUrl}${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${workerToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : {};
  if (!response.ok) {
    const error = new Error(payload.message || `控制面返回 HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return payload;
}

async function heartbeat(attemptId) {
  const response = await post("/api/v1/worker/v1/heartbeat", { attemptIds: [attemptId] });
  const attempt = response.attempts.find((item) => item.attemptId === attemptId);
  if (attempt?.cancelRequested) {
    return "CANCELLED";
  }
  return attempt?.status || "RUNNING";
}

async function runClaimedJob(claimed) {
  const startedAt = Date.now();
  const attemptId = claimed.attempt.id;
  const deadline = startedAt + claimed.job.timeoutSeconds * 1000;
  const control = {
    deadline: () => deadline,
    cancelled: () => heartbeat(attemptId),
  };
  let outcome;
  try {
    const preparation = await post(`/api/v1/worker/v1/attempts/${attemptId}/execution`, {});
    outcome = await executeWhitebox(preparation, control);
  } catch (error) {
    outcome = {
      status: "ERROR",
      category: "TOOL_ERROR",
      reason: error.message,
      steps: [],
      durationMs: Date.now() - startedAt,
    };
  }
  await post(`/api/v1/worker/v1/attempts/${attemptId}/result`, {
    status: outcome.status,
    detail: outcome,
  });
}

async function main() {
  while (true) {
    const claimed = await post("/api/v1/worker/v1/jobs/claim", {});
    if (claimed.job) {
      await runClaimedJob(claimed);
      if (runOnce) {
        return;
      }
    } else {
      if (runOnce) {
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
    }
  }
}

main().catch((error) => {
  console.error(`Whitebox Worker 失败: ${error.message}`);
  process.exitCode = 1;
});
