import { createRouter, createWebHistory } from "vue-router";
import HealthView from "../views/HealthView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: { name: "health" } },
    {
      path: "/health",
      name: "health",
      component: HealthView,
      meta: { title: "系统健康" },
    },
  ],
});

router.afterEach((to) => {
  document.title = (to.meta.title as string | undefined) ?? "统一测试平台";
});

export default router;
