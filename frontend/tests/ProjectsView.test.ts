import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ProjectsView from "../src/views/ProjectsView.vue";

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: "/projects/:id",
        name: "project-detail",
        component: { template: "<div />" },
      },
    ],
  });
}

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status });
}

describe("ProjectsView", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    document.cookie = "XSRF-TOKEN=test-csrf-token";
  });

  it("renders my projects with roles and statuses", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse([
          { id: 1, name: "项目一", status: "ACTIVE", myRole: "OWNER" },
          { id: 2, name: "项目二", status: "ARCHIVED", myRole: "VIEWER" },
        ]),
      ),
    );

    const wrapper = mount(ProjectsView, {
      global: { plugins: [ElementPlus, makeRouter()] },
    });
    await flushPromises();

    const text = wrapper.find('[data-testid="projects-table"]').text();
    expect(text).toContain("项目一");
    expect(text).toContain("项目负责人");
    expect(text).toContain("已归档");
    expect(text).toContain("只读成员");
  });

  it("submits a new project and reloads the list", async () => {
    let created = false;
    const fetchMock = vi.fn();
    fetchMock.mockImplementation(
      (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url === "/api/v1/projects" && init?.method === "POST") {
          return Promise.resolve(
            jsonResponse(
              { id: 9, name: "新项目", status: "ACTIVE", myRole: "OWNER" },
              201,
            ),
          );
        }
        if (url === "/api/v1/projects") {
          const rows = created
            ? [{ id: 9, name: "新项目", status: "ACTIVE", myRole: "OWNER" }]
            : [];
          created = true;
          return Promise.resolve(jsonResponse(rows));
        }
        return Promise.resolve(jsonResponse({}));
      },
    );
    vi.stubGlobal("fetch", fetchMock);

    const wrapper = mount(ProjectsView, {
      global: { plugins: [ElementPlus, makeRouter()] },
    });
    await flushPromises();

    await wrapper.find('[data-testid="project-create"]').trigger("click");
    await wrapper.find('[data-testid="project-name"]').setValue("新项目");
    await wrapper.find('[data-testid="project-submit"]').trigger("click");
    await flushPromises();

    const sent = fetchMock.mock.calls.find(
      ([input, init]) =>
        String(input) === "/api/v1/projects" && init?.method === "POST",
    ) as [RequestInfo, RequestInit] | undefined;
    expect(sent).toBeTruthy();
    expect(JSON.parse(String(sent![1]?.body))).toEqual({
      name: "新项目",
      description: "",
    });
    // The header carries the raw CSRF cookie value.
    expect((sent![1]?.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe(
      "test-csrf-token",
    );
    // The list reloads and now shows the created project.
    expect(wrapper.find('[data-testid="projects-table"]').text()).toContain(
      "新项目",
    );
  });
});
