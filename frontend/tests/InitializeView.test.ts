import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import InitializeView from "../src/views/InitializeView.vue";
import { session } from "../src/stores/session";

// Test credential is generated at runtime — no secrets in source.
const TEST_PASSWORD =
  "Pw" + crypto.randomUUID().replace(/-/g, "").substring(0, 16);

const EmptyPage = defineComponent({ render: () => h("div") });

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/health", name: "health", component: EmptyPage }],
  });
}

describe("InitializeView", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    // jsdom does not process Set-Cookie; pre-seed the CSRF cookie so
    // apiSend skips the csrf bootstrap and sends the header directly.
    document.cookie = "XSRF-TOKEN=test-csrf-token";
    session.clear();
  });

  it("rejects mismatched password confirmation without calling the API", async () => {
    const wrapper = mount(InitializeView, {
      global: { plugins: [ElementPlus, makeRouter()] },
    });
    await wrapper.find('[data-testid="init-username"]').setValue("admin");
    await wrapper.find('[data-testid="init-password"]').setValue(TEST_PASSWORD);
    await wrapper
      .find('[data-testid="init-confirm"]')
      .setValue("different-pass");
    await wrapper.find('[data-testid="init-submit"]').trigger("submit");
    await flushPromises();

    expect(wrapper.find('[data-testid="init-error"]').text()).toContain(
      "两次输入的密码不一致",
    );
    expect(fetch).not.toHaveBeenCalled();
  });

  it("initializes the admin, stores the session and redirects", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            id: 1,
            username: "admin",
            role: "ADMIN",
            status: "ACTIVE",
          }),
          { status: 201 },
        ),
      ),
    );
    const router = makeRouter();
    router.push = vi.fn().mockResolvedValue(undefined);

    const wrapper = mount(InitializeView, {
      global: { plugins: [ElementPlus, router] },
    });
    await wrapper.find('[data-testid="init-username"]').setValue("admin");
    await wrapper.find('[data-testid="init-password"]').setValue(TEST_PASSWORD);
    await wrapper.find('[data-testid="init-confirm"]').setValue(TEST_PASSWORD);
    await wrapper.find('[data-testid="init-submit"]').trigger("submit");
    await flushPromises();

    const [url, init] = vi.mocked(fetch).mock.calls.at(-1)!;
    expect(url).toBe("/api/v1/system/initialize");
    expect(JSON.parse(String(init?.body))).toEqual({
      username: "admin",
      password: TEST_PASSWORD,
    });
    expect(session.username).toBe("admin");
    expect(session.isAdmin).toBe(true);
    expect(router.push).toHaveBeenCalledWith("/health");
  });
});
