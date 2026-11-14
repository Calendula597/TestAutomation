import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import LoginView from "../src/views/LoginView.vue";
import { session } from "../src/stores/session";

const EmptyPage = defineComponent({ render: () => h("div") });

// Test credential is generated at runtime — no secrets in source.
const TEST_PASSWORD =
  "Pw" + crypto.randomUUID().replace(/-/g, "").substring(0, 16);

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/health", name: "health", component: EmptyPage }],
  });
}

describe("LoginView", () => {
  let router: Router;

  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    // jsdom does not process Set-Cookie; pre-seed the CSRF cookie so
    // apiSend skips the csrf bootstrap and sends the header directly.
    document.cookie = "XSRF-TOKEN=test-csrf-token";
    router = makeRouter();
    vi.mocked(router.push).mockClear?.();
    session.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("submits credentials, stores the session and redirects", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ username: "admin", role: "ADMIN" }), {
          status: 200,
        }),
      ),
    );
    router.push = vi.fn().mockResolvedValue(undefined);

    const wrapper = mount(LoginView, {
      global: { plugins: [ElementPlus, router] },
    });
    await wrapper.find('[data-testid="login-username"]').setValue("admin");
    await wrapper
      .find('[data-testid="login-password"]')
      .setValue(TEST_PASSWORD);
    await wrapper.find('[data-testid="login-submit"]').trigger("submit");
    await flushPromises();

    const fetchCall = vi.mocked(fetch).mock.calls.at(-1)!;
    const [url, init] = fetchCall;
    expect(url).toBe("/api/v1/auth/login");
    expect(JSON.parse(String(init?.body))).toEqual({
      username: "admin",
      password: TEST_PASSWORD,
    });
    expect(session.username).toBe("admin");
    expect(session.isAdmin).toBe(true);
    expect(router.push).toHaveBeenCalledWith("/health");
  });

  it("shows the backend error message instead of failing silently", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            status: 401,
            message: "用户名或密码不正确，或账号已被禁用",
          }),
          {
            status: 401,
          },
        ),
      ),
    );

    const wrapper = mount(LoginView, {
      global: { plugins: [ElementPlus, router] },
    });
    await wrapper.find('[data-testid="login-username"]').setValue("admin");
    await wrapper.find('[data-testid="login-password"]').setValue("wrong");
    await wrapper.find('[data-testid="login-submit"]').trigger("submit");
    await flushPromises();

    expect(wrapper.find('[data-testid="login-error"]').text()).toContain(
      "用户名或密码不正确",
    );
    expect(session.username).toBeNull();
  });
});
