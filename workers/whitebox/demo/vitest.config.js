import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.js"],
    coverage: {
      provider: "v8",
      reporter: ["json-summary", "text"],
      reportsDirectory: "./coverage",
      exclude: ["src/legacy.js"],
    },
  },
});
