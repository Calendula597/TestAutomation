import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import RequirementsView from "../src/views/RequirementsView.vue";

const EmptyPage = defineComponent({ render: () => h("div") });

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status });
}

async function mountView(): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/projects/:id/requirements",
        name: "requirements",
        component: RequirementsView,
      },
      {
        path: "/projects/:id/requirements/:requirementId",
        name: "req-detail",
        component: EmptyPage,
      },
    ],
  });
  router.push("/projects/1/requirements");
  await router.isReady();
  return mount(RequirementsView, {
    global: { plugins: [ElementPlus, router] },
  });
}

describe("RequirementsView", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    document.cookie = "XSRF-TOKEN=test-csrf-token";
  });

  it("renders the requirement list with source and version", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            id: 1,
            title: "登录功能",
            currentVersion: 2,
            sourceType: "MARKDOWN",
          },
        ]),
      ),
    );

    const wrapper = await mountView();
    await flushPromises();

    const text = wrapper.find('[data-testid="req-table"]').text();
    expect(text).toContain("登录功能");
    expect(text).toContain("Markdown");
    expect(text).toContain("v2");
  });

  it("shows an empty state when the project has no requirements", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse([])));

    const wrapper = await mountView();
    await flushPromises();

    expect(wrapper.text()).toContain("尚无需求");
    expect(wrapper.find('[data-testid="req-table"]').exists()).toBe(false);
  });

  it("shows an error state when the backend rejects the request", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ status: 404, message: "项目不存在" }, 404),
        ),
    );

    const wrapper = await mountView();
    await flushPromises();

    expect(wrapper.text()).toContain("项目不存在");
  });

  it("submits a new requirement and reloads", async () => {
    let created = false;
    const fetchMock = vi.fn();
    fetchMock.mockImplementation(
      (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/requirements") && init?.method === "POST") {
          return Promise.resolve(
            jsonResponse(
              {
                id: 5,
                title: "搜索功能",
                currentVersion: 1,
                body: "b",
                sourceType: "PASTED_TEXT",
                acceptanceCriteria: "- c",
              },
              201,
            ),
          );
        }
        if (url.endsWith("/requirements")) {
          const alreadyCreated = created;
          created = true;
          return Promise.resolve(
            jsonResponse(
              alreadyCreated
                ? [
                    {
                      id: 5,
                      title: "搜索功能",
                      currentVersion: 1,
                      sourceType: "PASTED_TEXT",
                    },
                  ]
                : [],
            ),
          );
        }
        return Promise.resolve(jsonResponse({}));
      },
    );
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    await wrapper.find('[data-testid="req-create"]').trigger("click");
    await wrapper.find('[data-testid="req-title"]').setValue("搜索功能");
    await wrapper.find('[data-testid="req-body"]').setValue("b");
    await wrapper.find('[data-testid="req-criteria"]').setValue("- c");
    await wrapper.find('[data-testid="req-submit"]').trigger("click");
    await flushPromises();

    const sent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input).endsWith("/requirements") && init?.method === "POST",
    ) as [RequestInfo, RequestInit] | undefined;
    expect(sent).toBeTruthy();
    expect(JSON.parse(String(sent![1]?.body))).toEqual({
      title: "搜索功能",
      body: "b",
      sourceType: "PASTED_TEXT",
      acceptanceCriteria: "- c",
    });
    expect(wrapper.find('[data-testid="req-table"]').text()).toContain(
      "搜索功能",
    );
  });
});
