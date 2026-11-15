import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ExecutionsView from "../src/views/ExecutionsView.vue";

const EmptyPage = defineComponent({ render: () => h("div") });

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status });
}

const EXECUTIONS = [
  {
    id: 11,
    jobType: "API",
    status: "PASSED",
    caseVersionId: 5,
    environmentId: 3,
    createdAt: "2026-11-14T10:00:00Z",
  },
  {
    id: 12,
    jobType: "API",
    status: "ASSERTION_FAILED",
    caseVersionId: 6,
    environmentId: 3,
    createdAt: "2026-11-14T10:05:00Z",
  },
];

const DETAIL = {
  id: 11,
  jobType: "API",
  status: "PASSED",
  caseVersionId: 5,
  environmentId: 3,
  createdAt: "2026-11-14T10:00:00Z",
  attempts: [
    {
      attemptNumber: 1,
      status: "PASSED",
      startedAt: "2026-11-14T10:00:01Z",
      finishedAt: "2026-11-14T10:00:02Z",
      result: {
        status: "PASSED",
        request: {
          method: "POST",
          url: "http://127.0.0.1:8000/echo",
          headers: { Authorization: "******", Accept: "application/json" },
        },
        response: {
          statusCode: 200,
          headers: { "Content-Type": "application/json" },
          body: '{"code":"OK","headers":{"Authorization":"******"}}',
          bodyTruncated: false,
        },
        assertions: [
          { type: "STATUS", expected: "200", actual: "200", passed: true },
          {
            type: "JSONPATH",
            path: "$.code",
            expected: "OK",
            actual: "OK",
            passed: true,
          },
        ],
        durationMs: 42,
        redirects: [],
      },
    },
  ],
};

function mountView(): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/projects/:id/executions",
        name: "executions",
        component: ExecutionsView,
      },
      { path: "/projects/:id", name: "project-detail", component: EmptyPage },
    ],
  });
  router.push("/projects/1/executions");
  return router.isReady().then(() =>
    mount(ExecutionsView, {
      global: { plugins: [ElementPlus, router] },
    }),
  );
}

describe("ExecutionsView", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    document.cookie = "XSRF-TOKEN=test-csrf-token";
  });

  function baseMock(): ReturnType<typeof vi.fn> {
    return vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1/executions")) {
        return Promise.resolve(jsonResponse(EXECUTIONS));
      }
      if (url.endsWith("/api/v1/projects/1/testcases")) {
        return Promise.resolve(
          jsonResponse([
            { id: 2, title: "登录成功", status: "READY", caseType: "API" },
          ]),
        );
      }
      if (url.endsWith("/api/v1/projects/1/environments")) {
        return Promise.resolve(
          jsonResponse([{ id: 3, name: "演示环境", currentVersion: 1 }]),
        );
      }
      return Promise.resolve(jsonResponse({}));
    });
  }

  it("renders execution records with per-class statuses", async () => {
    vi.stubGlobal("fetch", baseMock());

    const wrapper = await mountView();
    await flushPromises();

    const table = wrapper.find('[data-testid="executions-table"]').text();
    expect(table).toContain("通过");
    expect(table).toContain("断言失败");
    // Assertion failures and infrastructure errors are distinct labels.
    expect(table).not.toContain("执行错误");
  });

  it("triggers an execution for a case against a chosen environment", async () => {
    const fetchMock = baseMock();
    fetchMock.mockImplementation(
      (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (
          url.endsWith("/api/v1/projects/1/executions") &&
          init?.method === "POST"
        ) {
          return Promise.resolve(
            jsonResponse({ jobId: 13, status: "QUEUED" }, 202),
          );
        }
        if (url.endsWith("/api/v1/projects/1/executions")) {
          return Promise.resolve(jsonResponse(EXECUTIONS));
        }
        if (url.endsWith("/api/v1/projects/1/testcases")) {
          return Promise.resolve(
            jsonResponse([
              { id: 2, title: "登录成功", status: "READY", caseType: "API" },
            ]),
          );
        }
        if (url.endsWith("/api/v1/projects/1/environments")) {
          return Promise.resolve(
            jsonResponse([{ id: 3, name: "演示环境", currentVersion: 1 }]),
          );
        }
        return Promise.resolve(jsonResponse({}));
      },
    );
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    // Pick like a user would: click the case button, then the environment
    // button, then submit.
    await wrapper.find('[data-testid="exec-case-2"] input').setValue(true);
    await wrapper.find('[data-testid="exec-env-3"] input').setValue(true);
    await wrapper.find('[data-testid="exec-trigger"]').trigger("click");
    await flushPromises();

    const sent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/api/v1/projects/1/executions") &&
        init?.method === "POST",
    ) as [RequestInfo, RequestInit] | undefined;
    expect(sent).toBeTruthy();
    expect(JSON.parse(String(sent![1]?.body))).toEqual({
      caseId: 2,
      environmentId: 3,
    });
    expect(wrapper.text()).toContain("执行任务已提交");
  });

  it("shows masked request artifacts and per-assertion outcomes", async () => {
    const fetchMock = baseMock();
    fetchMock.mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1/executions/11")) {
        return Promise.resolve(jsonResponse(DETAIL));
      }
      if (url.endsWith("/api/v1/projects/1/executions")) {
        return Promise.resolve(jsonResponse(EXECUTIONS));
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="exec-open-11"]').trigger("click");
    await flushPromises();

    const result = wrapper.find('[data-testid="exec-detail"]').text();
    // The masked credential is visible; no plaintext value exists anywhere.
    expect(result).toContain("******");
    expect(result).toContain("POST");
    expect(result).toContain("STATUS");
    expect(result).toContain("$.code");
    // Every stored assertion reports its outcome explicitly.
    expect(result).toContain("通过");
    expect(wrapper.text()).not.toContain("sk-live-secret");
  });

  it("renders chained runs with per-row and per-step outcomes", async () => {
    const CHAINED_DETAIL = {
      id: 14,
      jobType: "API",
      status: "PASSED",
      caseVersionId: 9,
      environmentId: 3,
      createdAt: "2026-11-14T11:00:00Z",
      attempts: [
        {
          attemptNumber: 1,
          status: "PASSED",
          result: {
            status: "PASSED",
            rows: [
              {
                rowIndex: 0,
                values: {},
                status: "PASSED",
                durationMs: 25,
                steps: [
                  {
                    name: "登录",
                    status: "PASSED",
                    assertions: [
                      {
                        type: "JSONPATH",
                        path: "$.code",
                        expected: "OK",
                        actual: "OK",
                        passed: true,
                      },
                    ],
                    extract: [{ variable: "token", value: "abc123def456" }],
                    durationMs: 20,
                    redirects: [],
                  },
                  {
                    name: "我的资料",
                    status: "PASSED",
                    assertions: [
                      {
                        type: "JSONPATH",
                        path: "$.user",
                        expected: "alice",
                        actual: "alice",
                        passed: true,
                      },
                    ],
                    extract: [],
                    durationMs: 5,
                    redirects: [],
                  },
                ],
              },
              {
                rowIndex: 1,
                values: { name: "bob" },
                status: "ASSERTION_FAILED",
                durationMs: 12,
                steps: [
                  {
                    name: "登录",
                    status: "PASSED",
                    assertions: [
                      {
                        type: "JSONPATH",
                        path: "$.code",
                        expected: "OK",
                        actual: "OK",
                        passed: true,
                      },
                    ],
                    extract: [{ variable: "token", value: "abc123def456" }],
                    durationMs: 12,
                    redirects: [],
                  },
                ],
              },
            ],
          },
        },
      ],
    };
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1/executions/14")) {
        return Promise.resolve(jsonResponse(CHAINED_DETAIL));
      }
      if (url.endsWith("/api/v1/projects/1/executions")) {
        return Promise.resolve(
          jsonResponse([
            ...EXECUTIONS,
            {
              id: 14,
              jobType: "API",
              status: "PASSED",
              caseVersionId: 9,
              environmentId: 3,
              createdAt: "2026-11-14T11:00:00Z",
            },
          ]),
        );
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="exec-open-14"]').trigger("click");
    await flushPromises();

    const result = wrapper.find('[data-testid="exec-detail"]').text();
    expect(result).toContain("数据行与步骤");
    expect(result).toContain("登录");
    expect(result).toContain("我的资料");
    expect(result).toContain("token=abc123def456");
    // The failing row is visible as its own outcome, not folded into the pass.
    expect(result).toContain("断言失败");
    expect(result).toContain("1/1");
  });

  it("triggers a ready WEB case from the execution page", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (
          url.endsWith("/api/v1/projects/1/executions") &&
          init?.method === "POST"
        ) {
          return Promise.resolve(
            jsonResponse({ jobId: 16, status: "QUEUED" }, 202),
          );
        }
        if (url.endsWith("/api/v1/projects/1/executions")) {
          return Promise.resolve(jsonResponse([]));
        }
        if (url.endsWith("/api/v1/projects/1/testcases")) {
          return Promise.resolve(
            jsonResponse([
              { id: 7, title: "浏览器登录", status: "READY", caseType: "WEB" },
            ]),
          );
        }
        if (url.endsWith("/api/v1/projects/1/environments")) {
          return Promise.resolve(
            jsonResponse([{ id: 3, name: "演示环境", currentVersion: 1 }]),
          );
        }
        return Promise.resolve(jsonResponse({}));
      });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();
    await wrapper.find('[data-testid="exec-case-7"] input').setValue(true);
    await wrapper.find('[data-testid="exec-env-3"] input').setValue(true);
    await wrapper.find('[data-testid="exec-trigger"]').trigger("click");
    await flushPromises();

    const sent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/api/v1/projects/1/executions") &&
        init?.method === "POST",
    ) as [RequestInfo, RequestInit] | undefined;
    expect(sent).toBeTruthy();
    expect(JSON.parse(String(sent![1]?.body))).toEqual({
      caseId: 7,
      environmentId: 3,
    });
    expect(wrapper.text()).toContain(
      "API / Web / Electron / Android / Windows / 性能 / 白盒用例",
    );
  });

  it("renders WEB steps and authorized evidence links", async () => {
    const webDetail = {
      id: 15,
      jobType: "WEB",
      status: "ASSERTION_FAILED",
      caseVersionId: 8,
      environmentId: 3,
      createdAt: "2026-11-15T10:00:00Z",
      attempts: [
        {
          attemptNumber: 1,
          status: "ASSERTION_FAILED",
          result: {
            status: "ASSERTION_FAILED",
            reason: "业务断言未满足",
            steps: [
              {
                index: 0,
                action: "FILL",
                selector: "input[name=q]",
                actual: "******",
                status: "PASSED",
                durationMs: 12,
                evidence: [],
              },
              {
                index: 1,
                action: "ASSERT_TEXT",
                selector: "#result",
                expected: "欢迎",
                actual: "再见",
                status: "ASSERTION_FAILED",
                durationMs: 8,
                evidence: [
                  {
                    id: "shot-1",
                    kind: "SCREENSHOT",
                    filename: "step-1-failure.png",
                    path: "run/step.png",
                    contentType: "image/png",
                  },
                ],
              },
            ],
            evidence: [
              {
                id: "trace-1",
                kind: "TRACE",
                filename: "trace.zip",
                path: "run/trace.zip",
                contentType: "application/zip",
              },
            ],
            durationMs: 20,
          },
        },
      ],
    };
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1/executions/15")) {
        return Promise.resolve(jsonResponse(webDetail));
      }
      if (url.endsWith("/api/v1/projects/1/executions")) {
        return Promise.resolve(
          jsonResponse([{ ...webDetail, attempts: undefined }]),
        );
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();
    await wrapper.find('[data-testid="exec-open-15"]').trigger("click");
    await flushPromises();

    const steps = wrapper.find('[data-testid="web-steps"]');
    expect(steps.exists()).toBe(true);
    expect(steps.text()).toContain("FILL");
    expect(steps.text()).toContain("input[name=q]");
    expect(steps.text()).toContain("ASSERT_TEXT");
    expect(steps.text()).toContain("欢迎");
    expect(steps.text()).toContain("再见");
    expect(steps.text()).toContain("断言失败");
    expect(
      wrapper.find('[data-testid="evidence-trace-1"]').attributes("href"),
    ).toBe("/api/v1/projects/1/executions/15/evidence/trace-1");
    expect(
      wrapper.find('[data-testid="evidence-shot-1"]').attributes("href"),
    ).toBe("/api/v1/projects/1/executions/15/evidence/shot-1");
    expect(wrapper.find('[data-testid="assertions-table"]').exists()).toBe(
      false,
    );
  });

  it("offers Electron cases and shows the experimental main-process boundary", async () => {
    const electronDetail = {
      id: 17,
      jobType: "ELECTRON",
      status: "PASSED",
      caseVersionId: 9,
      environmentId: 3,
      createdAt: "2026-11-15T11:00:00Z",
      attempts: [
        {
          attemptNumber: 1,
          status: "PASSED",
          result: {
            status: "PASSED",
            steps: [
              {
                index: 0,
                action: "MAIN_HOOK",
                status: "PASSED",
                actual: "[]",
                durationMs: 4,
                evidence: [],
              },
            ],
            evidence: [],
            durationMs: 20,
          },
        },
      ],
    };
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1/executions/17")) {
        return Promise.resolve(jsonResponse(electronDetail));
      }
      if (url.endsWith("/api/v1/projects/1/executions")) {
        return Promise.resolve(
          jsonResponse([{ ...electronDetail, attempts: undefined }]),
        );
      }
      if (url.endsWith("/api/v1/projects/1/testcases")) {
        return Promise.resolve(
          jsonResponse([
            {
              id: 9,
              title: "Electron 笔记",
              status: "READY",
              caseType: "ELECTRON",
            },
          ]),
        );
      }
      if (url.endsWith("/api/v1/projects/1/environments")) {
        return Promise.resolve(
          jsonResponse([{ id: 3, name: "演示环境", currentVersion: 1 }]),
        );
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="exec-case-9"]').exists()).toBe(true);
    expect(wrapper.text()).toContain(
      "API / Web / Electron / Android / Windows / 性能 / 白盒用例",
    );

    await wrapper.find('[data-testid="exec-open-17"]').trigger("click");
    await flushPromises();
    const boundary = wrapper.find('[data-testid="electron-boundary"]');
    expect(boundary.exists()).toBe(true);
    expect(boundary.text()).toContain("实验性");
    expect(boundary.text()).toContain("显式测试钩子");
    expect(boundary.text()).toContain("原生系统对话框");
    expect(wrapper.text()).toContain("MAIN_HOOK");
  });

  it("offers native Windows cases and reports application and driver details", async () => {
    const nativeDetail = {
      id: 18,
      jobType: "WINDOWS_NATIVE",
      status: "ERROR",
      caseVersionId: 10,
      environmentId: 3,
      createdAt: "2026-11-15T12:00:00Z",
      attempts: [
        {
          attemptNumber: 1,
          status: "ERROR",
          result: {
            status: "ERROR",
            reason: "未找到 UI 元素",
            appType: "WINFORMS",
            driverName: "Wincore",
            driverVersion: "4.1.1",
            steps: [
              {
                index: 0,
                action: "CLICK",
                selector: "missingButton",
                status: "ERROR",
                error: "未找到 UI 元素",
                durationMs: 5,
                evidence: [],
              },
            ],
            evidence: [],
            durationMs: 20,
          },
        },
      ],
    };
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1/executions/18")) {
        return Promise.resolve(jsonResponse(nativeDetail));
      }
      if (url.endsWith("/api/v1/projects/1/executions")) {
        return Promise.resolve(
          jsonResponse([{ ...nativeDetail, attempts: undefined }]),
        );
      }
      if (url.endsWith("/api/v1/projects/1/testcases")) {
        return Promise.resolve(
          jsonResponse([
            {
              id: 10,
              title: "WinForms 流程",
              status: "READY",
              caseType: "WINDOWS_NATIVE",
            },
          ]),
        );
      }
      if (url.endsWith("/api/v1/projects/1/environments")) {
        return Promise.resolve(
          jsonResponse([{ id: 3, name: "演示环境", currentVersion: 1 }]),
        );
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="exec-case-10"]').exists()).toBe(true);
    expect(wrapper.text()).toContain(
      "API / Web / Electron / Android / Windows / 性能 / 白盒用例",
    );

    await wrapper.find('[data-testid="exec-open-18"]').trigger("click");
    await flushPromises();
    const driver = wrapper.find('[data-testid="native-driver"]');
    expect(driver.exists()).toBe(true);
    expect(driver.text()).toContain("WINFORMS");
    expect(driver.text()).toContain("Wincore 4.1.1");
    expect(wrapper.text()).toContain("未找到 UI 元素");
  });

  it("requires performance confirmation and displays real metrics and thresholds", async () => {
    const performanceDetail = {
      id: 19,
      jobType: "PERFORMANCE",
      status: "ASSERTION_FAILED",
      caseVersionId: 11,
      environmentId: 3,
      createdAt: "2026-11-15T13:00:00Z",
      attempts: [
        {
          attemptNumber: 1,
          status: "ASSERTION_FAILED",
          result: {
            status: "ASSERTION_FAILED",
            reason: "压测阈值未满足",
            engine: "k6",
            engineVersion: "2.4.0",
            metrics: {
              count: 120,
              throughputPerSecond: 40,
              errorRate: 0.02,
              p90Ms: 20,
              p95Ms: 30,
              p99Ms: 40,
            },
            thresholds: [
              { name: "maxP95Ms", expected: 25, actual: 30, passed: false },
            ],
            evidence: [],
            durationMs: 2000,
          },
        },
      ],
    };
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/api/v1/projects/1/executions/19")) {
          return Promise.resolve(jsonResponse(performanceDetail));
        }
        if (
          url.endsWith("/api/v1/projects/1/executions") &&
          init?.method === "POST"
        ) {
          return Promise.resolve(
            jsonResponse({ jobId: 19, status: "QUEUED" }, 202),
          );
        }
        if (url.endsWith("/api/v1/projects/1/executions")) {
          return Promise.resolve(
            jsonResponse([{ ...performanceDetail, attempts: undefined }]),
          );
        }
        if (url.endsWith("/api/v1/projects/1/testcases")) {
          return Promise.resolve(
            jsonResponse([
              {
                id: 11,
                title: "受限压测",
                status: "READY",
                caseType: "PERFORMANCE",
              },
            ]),
          );
        }
        if (url.endsWith("/api/v1/projects/1/environments")) {
          return Promise.resolve(
            jsonResponse([{ id: 3, name: "演示环境", currentVersion: 1 }]),
          );
        }
        return Promise.resolve(jsonResponse({}));
      });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();
    await wrapper.find('[data-testid="exec-case-11"] input').setValue(true);
    await wrapper.find('[data-testid="exec-env-3"] input').setValue(true);
    await wrapper.find('[data-testid="exec-trigger"]').trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("请先确认本次压测");
    expect(
      fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
    ).toHaveLength(0);

    await wrapper
      .find('[data-testid="performance-confirm"] input')
      .setValue(true);
    await wrapper.find('[data-testid="exec-trigger"]').trigger("click");
    await flushPromises();
    const sent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/api/v1/projects/1/executions") &&
        init?.method === "POST",
    ) as [RequestInfo, RequestInit] | undefined;
    expect(sent).toBeTruthy();
    expect(JSON.parse(String(sent![1]?.body))).toEqual({
      caseId: 11,
      environmentId: 3,
      confirmed: true,
    });

    await wrapper.find('[data-testid="exec-open-19"]').trigger("click");
    await flushPromises();
    const metrics = wrapper.find('[data-testid="performance-metrics"]');
    expect(metrics.exists()).toBe(true);
    expect(metrics.text()).toContain("k6 2.4.0");
    expect(metrics.text()).toContain("120");
    expect(metrics.text()).toContain("0.02");
    expect(metrics.text()).toContain("30");
    expect(
      wrapper.find('[data-testid="performance-thresholds"]').text(),
    ).toContain("maxP95Ms");
    expect(
      wrapper.find('[data-testid="performance-thresholds"]').text(),
    ).toContain("未达标");
  });

  it("shows whitebox result categories, coverage and exclusions", async () => {
    const whiteboxDetail = {
      id: 20,
      jobType: "WHITEBOX",
      status: "ASSERTION_FAILED",
      caseVersionId: 12,
      environmentId: 3,
      createdAt: "2026-11-15T14:00:00Z",
      attempts: [
        {
          attemptNumber: 1,
          status: "ASSERTION_FAILED",
          result: {
            status: "ASSERTION_FAILED",
            category: "COVERAGE_FAILED",
            reason: "覆盖率未达到项目门槛",
            testResults: { total: 4, passed: 4, failed: 0, exitCode: 0 },
            coverage: { statements: 75, branches: 60, functions: 80 },
            thresholds: [
              { name: "statements", expected: 90, actual: 75, passed: false },
            ],
            exclusions: ["src/legacy.js"],
            engine: "Vitest",
            engineVersion: "5.1.0",
            evidence: [],
            durationMs: 100,
          },
        },
      ],
    };
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1/executions/20")) {
        return Promise.resolve(jsonResponse(whiteboxDetail));
      }
      if (url.endsWith("/api/v1/projects/1/executions")) {
        return Promise.resolve(
          jsonResponse([{ ...whiteboxDetail, attempts: undefined }]),
        );
      }
      if (url.endsWith("/api/v1/projects/1/testcases")) {
        return Promise.resolve(
          jsonResponse([
            {
              id: 12,
              title: "白盒覆盖率",
              status: "READY",
              caseType: "WHITEBOX",
            },
          ]),
        );
      }
      if (url.endsWith("/api/v1/projects/1/environments")) {
        return Promise.resolve(
          jsonResponse([{ id: 3, name: "演示环境", currentVersion: 1 }]),
        );
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="exec-case-12"]').exists()).toBe(true);
    expect(wrapper.text()).toContain(
      "API / Web / Electron / Android / Windows / 性能 / 白盒用例",
    );

    await wrapper.find('[data-testid="exec-open-20"]').trigger("click");
    await flushPromises();
    const result = wrapper.find('[data-testid="whitebox-result"]');
    expect(result.exists()).toBe(true);
    expect(result.text()).toContain("覆盖率未达标");
    expect(result.text()).toContain("75");
    expect(result.text()).toContain("60");
    expect(result.text()).toContain("80");
    expect(result.text()).toContain("src/legacy.js");
    expect(result.text()).toContain("Vitest 5.1.0");
  });

  it("links every unified export format to the selected project execution", async () => {
    const detail = {
      id: 21,
      jobType: "WEB",
      status: "PASSED",
      caseVersionId: 8,
      environmentId: 3,
      createdAt: "2026-11-15T15:00:00Z",
      attempts: [],
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/api/v1/projects/1/executions/21")) {
          return Promise.resolve(jsonResponse(detail));
        }
        if (url.endsWith("/api/v1/projects/1/executions")) {
          return Promise.resolve(
            jsonResponse([{ ...detail, attempts: undefined }]),
          );
        }
        return Promise.resolve(jsonResponse({}));
      }),
    );

    const wrapper = await mountView();
    await flushPromises();
    await wrapper.find('[data-testid="exec-open-21"]').trigger("click");
    await flushPromises();

    for (const format of ["html", "junit", "csv", "xlsx", "raw"]) {
      expect(
        wrapper.find(`[data-testid="export-${format}"]`).attributes("href"),
      ).toBe(`/api/v1/projects/1/executions/21/export?format=${format}`);
    }
  });
});
