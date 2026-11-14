import { createRouter, createWebHistory } from "vue-router";
import { fetchMe, fetchSystemStatus } from "../api/auth";
import { session } from "../stores/session";
import HealthView from "../views/HealthView.vue";
import InitializeView from "../views/InitializeView.vue";
import LoginView from "../views/LoginView.vue";
import UsersView from "../views/UsersView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: { name: "health" } },
    {
      path: "/health",
      name: "health",
      component: HealthView,
      meta: { title: "系统健康", public: true },
    },
    {
      path: "/login",
      name: "login",
      component: LoginView,
      meta: { title: "登录", public: true },
    },
    {
      path: "/initialize",
      name: "initialize",
      component: InitializeView,
      meta: { title: "初始化", public: true },
    },
    {
      path: "/users",
      name: "users",
      component: UsersView,
      meta: { title: "用户管理", adminOnly: true },
    },
  ],
});

router.beforeEach(async (to) => {
  const status = await fetchSystemStatus().catch(() => ({ initialized: true }));

  if (!status.initialized && to.name !== "initialize") {
    return { name: "initialize" };
  }
  if (status.initialized && to.name === "initialize") {
    return { name: "login" };
  }
  if (to.meta.public) {
    return true;
  }

  if (!session.loaded) {
    try {
      session.setFrom(await fetchMe());
    } catch {
      return { name: "login" };
    }
  }
  if (to.meta.adminOnly && !session.isAdmin) {
    return { name: "health" };
  }
  return true;
});

router.afterEach((to) => {
  document.title = (to.meta.title as string | undefined) ?? "统一测试平台";
});

export default router;
