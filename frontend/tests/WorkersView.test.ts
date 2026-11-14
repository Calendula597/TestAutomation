import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import WorkersView from "../src/views/WorkersView.vue";

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status });
}

function mountView(): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/workers", name: "workers", component: WorkersView },
      { path: "/", name: "home", component: { template: "<div />" } },
    ],
  });
  router.push("/workers");
  return router.isReady().then(() =>
    mount(WorkersView, {
      global: { plugins: [ElementPlus, router] },
    }),
  );
}

const WORKERS = [
  {
    id: 1,
    name: "主 Worker",
    capabilities: ["DIAGNOSTIC", "API", "WEB"],
    version: "0.1.0",
    maxConcurrency: 2,
    status: "OFFLINE",
    lastHeartbeatAt: "2026-11-14T10:00:00Z",
  },
  {
    id: 2,
    name: "备用 Worker",
    capabilities: ["DIAGNOSTIC"],
    version: "0.1.0",
    maxConcurrency: 1,
    status: "NEVER_SEEN",
    lastHeartbeatAt: null,
  },
];

const JOBS = [
  {
    id: 11,
    jobType: "DIAGNOSTIC",
    workerId: 1,
    status: "QUEUED",
    createdAt: "2026-11-14T10:05:00Z",
  },
  {
    id: 10,
    jobType: "DIAGNOSTIC",
    workerId: 1,
    status: "TIMED_OUT",
    createdAt: "2026-11-14T10:04:00Z",
  },
];

describe("WorkersView", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    document.cookie = "XSRF-TOKEN=test-csrf-token";
  });

  it("renders registered workers with declared capabilities and status", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/api/v1/system/workers")) {
          return Promise.resolve(jsonResponse(WORKERS));
        }
        if (url.endsWith("/api/v1/system/queue/jobs")) {
          return Promise.resolve(jsonResponse(JOBS));
        }
        return Promise.resolve(jsonResponse({}));
      }),
    );

    const wrapper = await mountView();
    await flushPromises();

    const table = wrapper.find('[data-testid="workers-table"]').text();
    expect(table).toContain("主 Worker");
    expect(table).toContain("备用 Worker");
    // Derived visibility states are surfaced in Chinese labels.
    expect(table).toContain("未连接");
    expect(table).toContain("失联");
    expect(table).toContain("诊断");
    expect(table).toContain("DIAGNOSTIC");
  });

  it("registers a worker, shows the token once and reloads", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/api/v1/system/workers") && init?.method === "POST") {
          return Promise.resolve(
            jsonResponse(
              {
                id: 9,
                name: "诊断 Worker",
                capabilities: ["DIAGNOSTIC"],
                version: "1.0.0",
                maxConcurrency: 2,
                status: "NEVER_SEEN",
                lastHeartbeatAt: null,
                token: "wpk_abcdef0123456789abcdef0123456789ab",
              },
              201,
            ),
          );
        }
        if (url.endsWith("/api/v1/system/workers")) {
          return Promise.resolve(jsonResponse(WORKERS));
        }
        if (url.endsWith("/api/v1/system/queue/jobs")) {
          return Promise.resolve(jsonResponse(JOBS));
        }
        return Promise.resolve(jsonResponse({}));
      });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    await wrapper
      .find('[data-testid="worker-register-name"]')
      .setValue("诊断 Worker");
    await wrapper.find('[data-testid="cap-DIAGNOSTIC"] input').setValue(true);
    await wrapper
      .find('[data-testid="worker-register-version"]')
      .setValue("1.0.0");
    await wrapper
      .find('[data-testid="worker-register-submit"]')
      .trigger("click");
    await flushPromises();

    const sent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/api/v1/system/workers") &&
        init?.method === "POST",
    ) as [RequestInfo, RequestInit] | undefined;
    expect(sent).toBeTruthy();
    const payload = JSON.parse(String(sent![1]?.body));
    expect(payload.name).toBe("诊断 Worker");
    expect(payload.capabilities).toEqual(["DIAGNOSTIC"]);
    expect(payload.version).toBe("1.0.0");
    expect(payload.maxConcurrency).toBe(1);

    // The credential is displayed exactly once with a keep-it-now hint.
    expect(wrapper.text()).toContain("wpk_abcdef0123456789abcdef0123456789ab");
    expect(wrapper.text()).toContain("仅显示一次");
  });

  it("triggers a real diagnostic and lists queued jobs with cancel", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (
          url.endsWith("/api/v1/system/workers/1/diagnostics") &&
          init?.method === "POST"
        ) {
          return Promise.resolve(
            jsonResponse({ jobId: 21, status: "QUEUED" }, 202),
          );
        }
        if (url.endsWith("/api/v1/system/workers/1/diagnostics")) {
          return Promise.resolve(
            jsonResponse([
              {
                id: 5,
                jobId: 21,
                status: "PASSED",
                checks: [
                  { name: "jvm", ok: true, detail: "Java 25.0.1" },
                  {
                    name: "chromium",
                    ok: false,
                    detail: "未找到 Chromium/Chrome 可执行文件",
                  },
                ],
                createdAt: "2026-11-14T10:06:00Z",
              },
            ]),
          );
        }
        if (url.includes("/api/v1/system/queue/jobs/11/cancel")) {
          return Promise.resolve(jsonResponse({ id: 11, status: "CANCELLED" }));
        }
        if (url.endsWith("/api/v1/system/queue/jobs")) {
          return Promise.resolve(jsonResponse(JOBS));
        }
        if (url.endsWith("/api/v1/system/workers")) {
          return Promise.resolve(jsonResponse(WORKERS));
        }
        return Promise.resolve(jsonResponse({}));
      });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="worker-run-diag-1"]').trigger("click");
    await flushPromises();

    const diagSent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/api/v1/system/workers/1/diagnostics") &&
        init?.method === "POST",
    );
    expect(diagSent).toBeTruthy();
    expect(wrapper.text()).toContain("诊断任务已提交");

    // Diagnostics surface real check results, including explicit misses.
    await wrapper.find('[data-testid="worker-view-diag-1"]').trigger("click");
    await flushPromises();
    const diagText = wrapper.find('[data-testid="diag-list"]').text();
    expect(diagText).toContain("jvm");
    expect(diagText).toContain("Java 25.0.1");
    expect(diagText).toContain("未找到 Chromium/Chrome 可执行文件");

    // Queued jobs can be cancelled from the observation table.
    await wrapper.find('[data-testid="job-cancel-11"]').trigger("click");
    await flushPromises();
    const cancelSent = fetchMock.mock.calls.find(([input]) =>
      String(input).includes("/api/v1/system/queue/jobs/11/cancel"),
    );
    expect(cancelSent).toBeTruthy();
  });
});
