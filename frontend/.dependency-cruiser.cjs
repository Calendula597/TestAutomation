/**
 * Frontend boundary rules (T01): the app stays acyclic, tests are not
 * importable from production code, and everything ships through src/.
 *
 * @type {import('dependency-cruiser').IConfiguration}
 */
module.exports = {
  forbidden: [
    {
      name: "no-circular",
      severity: "error",
      comment: "Circular dependencies make the module graph untestable.",
      from: {},
      to: { circular: true },
    },
    {
      name: "src-must-not-import-tests",
      severity: "error",
      comment: "Production code may never depend on test fixtures.",
      from: { path: "^src" },
      to: { path: "^tests" },
    },
    {
      name: "no-reaching-outside-src",
      severity: "error",
      comment: "Business code lives under src/; nothing else is importable.",
      from: { path: "^src" },
      to: { pathNot: "^(src|node_modules|element-plus/dist|element-plus/es)" },
    },
  ],
  options: {
    doNotFollow: { path: "node_modules" },
    tsConfig: { fileName: "tsconfig.app.json" },
  },
};
