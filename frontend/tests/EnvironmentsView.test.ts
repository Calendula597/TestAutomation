import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EnvironmentsView from "../src/views/EnvironmentsView.vue";

const EmptyPage = defineComponent({ render: () => h("div") });

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status });
}

const DETAIL = {
  id: 7,
  projectId: 1,
  name: "冒烟环境",
  currentVersion: 2,
  plainVariables: { baseUrl: "https://api.demo.local" },
  secrets: [{ key: "apiKey", hasValue: true, maskedValue: "******" }],
  targets: ["https://api.demo.local"],
  createdAt: "2026-11-14T10:00:00Z",
};

const VERSIONS = [
  {
    version: 1,
    plainVariables: { baseUrl: "https://api.demo.local" },
    secrets: [{ key: "apiKey", hasValue: true, maskedValue: "******" }],
    targets: ["https://api.demo.local"],
    createdAt: "2026-11-14T09:00:00Z",
  },
  {
    version: 2,
    plainVariables: { baseUrl: "https://api.demo.local" },
    secrets: [{ key: "apiKey", hasValue: true, maskedValue: "******" }],
    targets: ["https://api.demo.local", "http://127.0.0.1:8080"],
    createdAt: "2026-11-14T10:00:00Z",
  },
];

function mountView(): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/projects/:id/environments",
        name: "environments",
        component: EnvironmentsView,
      },
      { path: "/projects/:id", name: "project-detail", component: EmptyPage },
    ],
  });
  router.push("/projects/1/environments");
  return router.isReady().then(() =>
    mount(EnvironmentsView, {
      global: { plugins: [ElementPlus, router] },
    }),
  );
}

describe("EnvironmentsView", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    document.cookie = "XSRF-TOKEN=test-csrf-token";
  });

  it("renders the environment list with versions", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1")) {
        return Promise.resolve(
          jsonResponse({
            id: 1,
            name: "演示项目",
            status: "ACTIVE",
            myRole: "OWNER",
          }),
        );
      }
      if (url.endsWith("/environments")) {
        return Promise.resolve(
          jsonResponse([
            {
              id: 7,
              name: "冒烟环境",
              currentVersion: 2,
              createdAt: DETAIL.createdAt,
            },
          ]),
        );
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="env-table"]').text()).toContain(
      "冒烟环境",
    );
    expect(wrapper.find('[data-testid="env-table"]').text()).toContain("v2");
  });

  it("creates an environment as owner and reloads the list", async () => {
    let created = false;
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/api/v1/projects/1")) {
          return Promise.resolve(
            jsonResponse({
              id: 1,
              name: "演示项目",
              status: "ACTIVE",
              myRole: "OWNER",
            }),
          );
        }
        if (url.endsWith("/environments") && init?.method === "POST") {
          created = true;
          return Promise.resolve(jsonResponse(DETAIL, 201));
        }
        if (url.endsWith("/environments")) {
          return Promise.resolve(
            jsonResponse(
              created
                ? [
                    {
                      id: 7,
                      name: "冒烟环境",
                      currentVersion: 1,
                      createdAt: DETAIL.createdAt,
                    },
                  ]
                : [],
            ),
          );
        }
        return Promise.resolve(jsonResponse({}));
      });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="env-create-name"]').setValue("冒烟环境");
    await wrapper.find('[data-testid="env-create-submit"]').trigger("click");
    await flushPromises();

    const sent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/environments") && init?.method === "POST",
    ) as [RequestInfo, RequestInit] | undefined;
    expect(sent).toBeTruthy();
    expect(JSON.parse(String(sent![1]?.body))).toEqual({ name: "冒烟环境" });
    expect(wrapper.find('[data-testid="env-table"]').text()).toContain(
      "冒烟环境",
    );
  });

  it("shows masked secrets, version history and a working target check", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1")) {
        return Promise.resolve(
          jsonResponse({
            id: 1,
            name: "演示项目",
            status: "ACTIVE",
            myRole: "OWNER",
          }),
        );
      }
      if (url.endsWith("/environments")) {
        return Promise.resolve(
          jsonResponse([
            {
              id: 7,
              name: "冒烟环境",
              currentVersion: 2,
              createdAt: DETAIL.createdAt,
            },
          ]),
        );
      }
      if (url.endsWith("/environments/7")) {
        return Promise.resolve(jsonResponse(DETAIL));
      }
      if (url.endsWith("/environments/7/versions")) {
        return Promise.resolve(jsonResponse(VERSIONS));
      }
      if (url.endsWith("/validate-target")) {
        return Promise.resolve(
          jsonResponse({
            allowed: false,
            checks: [
              {
                url: "https://evil.example.com/x",
                allowed: false,
                reason: "目标不在白名单",
              },
            ],
          }),
        );
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="env-open-7"]').trigger("click");
    await flushPromises();

    const secrets = wrapper.find('[data-testid="env-secrets"]').text();
    expect(secrets).toContain("apiKey");
    expect(secrets).toContain("******");
    expect(secrets).not.toContain("sk-plain-value");

    const versions = wrapper.find('[data-testid="env-versions"]').text();
    expect(versions).toContain("v1");
    expect(versions).toContain("v2");
    expect(versions).toContain("******");

    await wrapper
      .find('[data-testid="env-check-url"]')
      .setValue("https://evil.example.com/x");
    await wrapper.find('[data-testid="env-check-submit"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="env-check-result"]').text()).toContain(
      "已拒绝",
    );
  });

  it("saves owner edits as a new version without resending stored secrets", async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/api/v1/projects/1")) {
          return Promise.resolve(
            jsonResponse({
              id: 1,
              name: "演示项目",
              status: "ACTIVE",
              myRole: "OWNER",
            }),
          );
        }
        if (url.endsWith("/environments")) {
          return Promise.resolve(
            jsonResponse([
              {
                id: 7,
                name: "冒烟环境",
                currentVersion: 2,
                createdAt: DETAIL.createdAt,
              },
            ]),
          );
        }
        if (url.endsWith("/environments/7") && init?.method === "PUT") {
          return Promise.resolve(
            jsonResponse({ ...DETAIL, currentVersion: 3 }),
          );
        }
        if (url.endsWith("/environments/7")) {
          return Promise.resolve(jsonResponse(DETAIL));
        }
        if (url.endsWith("/environments/7/versions")) {
          return Promise.resolve(jsonResponse(VERSIONS));
        }
        return Promise.resolve(jsonResponse({}));
      });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="env-open-7"]').trigger("click");
    await flushPromises();

    await wrapper
      .find('[data-testid="env-target-input"]')
      .setValue("http://127.0.0.1:8080");
    await wrapper.find('[data-testid="env-target-add"]').trigger("click");
    await wrapper.find('[data-testid="env-save"]').trigger("click");
    await flushPromises();

    const sent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/environments/7") && init?.method === "PUT",
    ) as [RequestInfo, RequestInit] | undefined;
    expect(sent).toBeTruthy();
    const payload = JSON.parse(String(sent![1]?.body));
    expect(payload.targets).toContain("http://127.0.0.1:8080");
    // The stored secret is kept server-side; the client never resends it.
    expect(payload.secretWrites).toBeUndefined();
    expect(JSON.stringify(payload)).not.toContain("******");
    expect(wrapper.text()).toContain("v3");
  });

  it("hides editing controls from members", async () => {
    const fetchMock = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith("/api/v1/projects/1")) {
        return Promise.resolve(
          jsonResponse({
            id: 1,
            name: "演示项目",
            status: "ACTIVE",
            myRole: "MEMBER",
          }),
        );
      }
      if (url.endsWith("/environments")) {
        return Promise.resolve(
          jsonResponse([
            {
              id: 7,
              name: "冒烟环境",
              currentVersion: 2,
              createdAt: DETAIL.createdAt,
            },
          ]),
        );
      }
      if (url.endsWith("/environments/7")) {
        return Promise.resolve(jsonResponse(DETAIL));
      }
      if (url.endsWith("/environments/7/versions")) {
        return Promise.resolve(jsonResponse(VERSIONS));
      }
      return Promise.resolve(jsonResponse({}));
    });
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="env-create-submit"]').exists()).toBe(
      false,
    );
    await wrapper.find('[data-testid="env-open-7"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="env-save"]').exists()).toBe(false);
  });
});
