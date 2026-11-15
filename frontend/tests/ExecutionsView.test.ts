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
    expect(wrapper.text()).toContain("API / Web 用例");
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
});
