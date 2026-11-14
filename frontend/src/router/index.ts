import { createRouter, createWebHistory } from "vue-router";
import { fetchMe, fetchSystemStatus } from "../api/auth";
import { session } from "../stores/session";
import HealthView from "../views/HealthView.vue";
import InitializeView from "../views/InitializeView.vue";
import LoginView from "../views/LoginView.vue";
import ProjectDetailView from "../views/ProjectDetailView.vue";
import ProjectsView from "../views/ProjectsView.vue";
import RequirementDetailView from "../views/RequirementDetailView.vue";
import RequirementsView from "../views/RequirementsView.vue";
import UsersView from "../views/UsersView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: { name: "projects" } },
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
    {
      path: "/projects",
      name: "projects",
      component: ProjectsView,
      meta: { title: "项目" },
    },
    {
      path: "/projects/:id",
      name: "project-detail",
      component: ProjectDetailView,
      meta: { title: "项目详情" },
    },
    {
      path: "/projects/:id/requirements",
      name: "requirements",
      component: RequirementsView,
      meta: { title: "需求" },
    },
    {
      path: "/projects/:id/requirements/:requirementId",
      name: "requirement-detail",
      component: RequirementDetailView,
      meta: { title: "需求详情" },
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
