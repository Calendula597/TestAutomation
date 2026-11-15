import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AutomationView from "../src/views/AutomationView.vue";

const EmptyPage = defineComponent({ render: () => h("div") });

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status });
}

function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/projects/:id/automation",
        name: "automation",
        component: AutomationView,
      },
      { path: "/projects/:id", name: "project-detail", component: EmptyPage },
      {
        path: "/projects/:id/executions",
        name: "executions",
        component: EmptyPage,
      },
    ],
  });
  router.push("/projects/7/automation");
  return router
    .isReady()
    .then(() =>
      mount(AutomationView, { global: { plugins: [ElementPlus, router] } }),
    );
}

describe("AutomationView", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=test-csrf-token";
  });

  it("saves an explicitly zoned non-overlapping schedule and disables it", async () => {
    let schedules: Record<string, unknown>[] = [];
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url.endsWith("/api/v1/projects/7/testcases")) {
          return Promise.resolve(
            jsonResponse([
              {
                id: 5,
                title: "API 健康检查",
                status: "READY",
                caseType: "API",
              },
            ]),
          );
        }
        if (url.endsWith("/api/v1/projects/7/environments")) {
          return Promise.resolve(
            jsonResponse([{ id: 3, name: "预发布", currentVersion: 1 }]),
          );
        }
        if (url.endsWith("/api/v1/projects/7/ci-tokens")) {
          return Promise.resolve(jsonResponse([]));
        }
        if (url.endsWith("/api/v1/projects/7/schedules") && method === "GET") {
          return Promise.resolve(jsonResponse(schedules));
        }
        if (url.endsWith("/api/v1/projects/7/schedules") && method === "POST") {
          schedules = [
            {
              id: 1,
              caseVersionId: 15,
              environmentId: 3,
              ...JSON.parse(String(init?.body)),
              nextRunAt: "2026-11-17T01:00:00Z",
              lastFiredAt: null,
            },
          ];
          return Promise.resolve(jsonResponse(schedules[0], 201));
        }
        if (
          url.endsWith("/api/v1/projects/7/schedules/1") &&
          method === "PUT"
        ) {
          schedules[0] = { ...schedules[0], ...JSON.parse(String(init?.body)) };
          return Promise.resolve(jsonResponse(schedules[0]));
        }
        return Promise.resolve(jsonResponse({}));
      });
    vi.stubGlobal("fetch", fetchMock);
    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="schedule-name"]').setValue("每日检查");
    await wrapper.find('[data-testid="schedule-case-5"] input').setValue(true);
    await wrapper.find('[data-testid="schedule-env-3"] input').setValue(true);
    await wrapper.find('[data-testid="schedule-cron"]').setValue("0 0 9 * * *");
    await wrapper
      .find('[data-testid="schedule-timezone"]')
      .setValue("Asia/Shanghai");
    await wrapper.find('[data-testid="schedule-create"]').trigger("click");
    await flushPromises();

    const created = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/api/v1/projects/7/schedules") &&
        init?.method === "POST",
    );
    expect(JSON.parse(String(created?.[1]?.body))).toMatchObject({
      caseId: 5,
      environmentId: 3,
      cronExpression: "0 0 9 * * *",
      timeZone: "Asia/Shanghai",
      allowOverlap: false,
      enabled: true,
    });
    expect(wrapper.find('[data-testid="schedules-table"]').text()).toContain(
      "Asia/Shanghai",
    );
    expect(wrapper.find('[data-testid="schedules-table"]').text()).toContain(
      "禁止",
    );

    await wrapper.find('[data-testid="toggle-schedule-1"]').trigger("click");
    await flushPromises();
    const disabled = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/api/v1/projects/7/schedules/1") &&
        init?.method === "PUT",
    );
    expect(JSON.parse(String(disabled?.[1]?.body))).toEqual({ enabled: false });
  });

  it("shows a raw token once and revokes it only after confirmation", async () => {
    let tokens: Record<string, unknown>[] = [];
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url.endsWith("/api/v1/projects/7/testcases")) {
          return Promise.resolve(jsonResponse([]));
        }
        if (
          url.endsWith("/api/v1/projects/7/environments") ||
          url.endsWith("/api/v1/projects/7/schedules")
        ) {
          return Promise.resolve(jsonResponse([]));
        }
        if (url.endsWith("/api/v1/projects/7/ci-tokens") && method === "GET") {
          return Promise.resolve(jsonResponse(tokens));
        }
        if (url.endsWith("/api/v1/projects/7/ci-tokens") && method === "POST") {
          const { token, ...metadata } = {
            id: 11,
            name: "发布流水线",
            prefix: "cik_12345678",
            scopes: ["EXECUTION_READ", "EXECUTION_TRIGGER"],
            expiresAt: "2026-11-23T00:00:00Z",
            revokedAt: null,
            lastUsedAt: null,
            createdAt: "2026-11-16T00:00:00Z",
            token: "cik_raw-only-once",
          };
          tokens = [metadata];
          return Promise.resolve(jsonResponse({ ...metadata, token }, 201));
        }
        if (
          url.endsWith("/api/v1/projects/7/ci-tokens/11") &&
          method === "DELETE"
        ) {
          tokens[0] = { ...tokens[0], revokedAt: "2026-11-16T01:00:00Z" };
          return Promise.resolve(new Response(null, { status: 204 }));
        }
        return Promise.resolve(jsonResponse({}));
      });
    vi.stubGlobal("fetch", fetchMock);
    const confirm = vi.fn(() => true);
    vi.stubGlobal("confirm", confirm);
    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="token-name"]').setValue("发布流水线");
    await wrapper.find('[data-testid="token-create"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="issued-token"]').text()).toBe(
      "cik_raw-only-once",
    );
    expect(wrapper.find('[data-testid="tokens-table"]').text()).not.toContain(
      "cik_raw-only-once",
    );
    const issued = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/api/v1/projects/7/ci-tokens") &&
        init?.method === "POST",
    );
    expect(JSON.parse(String(issued?.[1]?.body))).toMatchObject({
      name: "发布流水线",
      scopes: ["EXECUTION_TRIGGER", "EXECUTION_READ"],
    });
    expect(
      Number.isNaN(Date.parse(JSON.parse(String(issued?.[1]?.body)).expiresAt)),
    ).toBe(false);

    await wrapper.find('[data-testid="revoke-token-11"]').trigger("click");
    await flushPromises();
    expect(confirm).toHaveBeenCalledOnce();
    expect(
      fetchMock.mock.calls.some(
        ([input, init]) =>
          String(input).endsWith("/api/v1/projects/7/ci-tokens/11") &&
          init?.method === "DELETE",
      ),
    ).toBe(true);
    expect(wrapper.find('[data-testid="tokens-table"]').text()).toContain(
      "已撤销",
    );
  });
});
