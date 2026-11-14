import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import HealthView from "../src/views/HealthView.vue";

const mountView = () =>
  mount(HealthView, { global: { plugins: [ElementPlus] } });

const upPayload = {
  status: "UP",
  checkedAt: "2026-11-14T03:00:00Z",
  components: {
    database: {
      status: "UP",
      latencyMs: 3,
      detail: { version: "PostgreSQL 16.15 (Debian 16.15-1.pgdg12+2)" },
    },
  },
};

describe("HealthView", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders the real backend and database health state", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify(upPayload), { status: 200 }),
        ),
    );

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain("系统健康");
    expect(wrapper.text()).toContain("正常");
    expect(wrapper.text()).toContain("数据库");
    expect(wrapper.text()).toContain("3 ms");
    expect(wrapper.text()).toContain("PostgreSQL 16.15");
  });

  it("reports an explicit error when the backend is unreachable", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new Error("connection refused")),
    );

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain("后端不可达");
    expect(wrapper.text()).toContain("connection refused");
    expect(wrapper.text()).not.toContain("正常");
  });

  it("marks the platform DOWN when the backend reports a failing database", async () => {
    const downPayload = {
      status: "DOWN",
      components: { database: { status: "DOWN", error: "connection reset" } },
    };
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify(downPayload), { status: 200 }),
        ),
    );

    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain("异常");
    expect(wrapper.text()).toContain("connection reset");
    expect(wrapper.text()).not.toContain("正常");
  });
});
