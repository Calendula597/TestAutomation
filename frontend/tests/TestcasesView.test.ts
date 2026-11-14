import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TestcasesView from "../src/views/TestcasesView.vue";

const EmptyPage = defineComponent({ render: () => h("div") });

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status });
}

const oneCase = [
  {
    id: 1,
    title: "正常登录用例",
    priority: "HIGH",
    status: "DRAFT",
    version: 1,
    requirementId: 7,
    requirementTitle: "登录需求",
    steps: [
      {
        stepOrder: 1,
        action: "打开登录页",
        testData: "",
        expected: "页面正常加载",
      },
    ],
  },
];

async function mountView(): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/projects/:id/testcases",
        name: "testcases",
        component: TestcasesView,
      },
      { path: "/", component: EmptyPage },
    ],
  });
  router.push("/projects/1/testcases");
  await router.isReady();
  return mount(TestcasesView, { global: { plugins: [ElementPlus, router] } });
}

describe("TestcasesView", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    document.cookie = "XSRF-TOKEN=test-csrf-token";
    // jsdom lacks ResizeObserver, which AG Grid requires.
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("loads cases into the grid toolbar state without errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(oneCase)));

    const wrapper = await mountView();
    await flushPromises();
    await flushPromises();

    // The grid data loaded; no error alert is shown.
    expect(wrapper.find(".el-alert--error").exists()).toBe(false);
  });

  it("shows the backend error state when loading fails", async () => {
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

  it("displays a conflict alert and reloads when a save returns 409", async () => {
    const fetchMock = vi.fn();
    fetchMock.mockImplementation(
      (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === "PUT" && url.includes("/testcases/1")) {
          return Promise.resolve(
            jsonResponse(
              {
                status: 409,
                message: "版本冲突：他人已修改此用例，请刷新后基于最新版本重试",
                currentVersion: 3,
              },
              409,
            ),
          );
        }
        if (url.includes("/testcases") && !init?.method) {
          return Promise.resolve(jsonResponse(oneCase));
        }
        return Promise.resolve(jsonResponse(oneCase));
      },
    );
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    // Simulate a stale save through the component's API path: expand panel then save.
    const vm = wrapper.vm as unknown as {
      state: {
        expanded: {
          id: number;
          title: string;
          priority: string;
          requirementId: null;
          version: number;
        } | null;
        steps: { action: string; expected: string }[];
      };
      saveSteps: () => Promise<void>;
    };
    vm.state.expanded = {
      id: 1,
      title: "正常登录用例",
      priority: "HIGH",
      requirementId: null,
      version: 1,
    };
    vm.state.steps = [{ action: "打开登录页", expected: "页面正常加载" }];
    await vm.saveSteps();
    await flushPromises();

    expect(wrapper.find('[data-testid="conflict-alert"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="conflict-alert"]').text()).toContain(
      "版本冲突",
    );
    // The reload happened (a fresh GET after the conflict).
    const reloads = fetchMock.mock.calls.filter(([input, init]) => {
      const url = String(input);
      return (
        url.includes("/testcases") &&
        !init?.method &&
        !url.includes("batch-update")
      );
    });
    expect(reloads.length).toBeGreaterThanOrEqual(2);
  });

  it("inserts group header rows when custom grouping is active", async () => {
    const fetchMock = vi.fn();
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(oneCase)));
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = await mountView();
    await flushPromises();

    const vm = wrapper.vm as unknown as {
      state: { groupBy: string; cases: { priority: string; id: number }[] };
      displayRows: Array<Record<string, unknown>>;
    };
    vm.state.groupBy = "priority";
    await flushPromises();

    const headerRows = vm.displayRows.filter((r) => r.headerRow);
    expect(headerRows).toHaveLength(1);
    expect(String(headerRows[0]!.label)).toContain("优先级：高（1）");
    // The case row still follows its group header.
    expect(vm.displayRows).toHaveLength(2);
  });
});
