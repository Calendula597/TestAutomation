# Frontend

The Vue 3 + TypeScript application is a deep module: business logic lives
inside `src/`, and only deliberate entry points are consumed from outside.

```text
frontend/
├── src/
│   ├── api/        # backend HTTP clients and response types
│   ├── router/     # route table
│   ├── views/      # page components
│   ├── App.vue
│   └── main.ts
└── tests/          # component tests through user-visible behavior
```

- **Entry-point boundary:** outside code (tests included) may import only
  `src/main.ts`, `src/App.vue`, the route table, and public `src/api/*`
  clients; deeper internals stay private.
- **Intra-package freedom:** files within `src/` may import each other.
- **Tests through behavior:** tests render pages and interact like a user;
  they never assert on implementation internals.
- **No cycles:** circular imports are rejected by `npm run lint:boundaries`.

Run `npm run lint:boundaries` to enforce these rules.

## Checks

```bash
npm run check   # format + lint + typecheck + boundaries + tests + build
```

All dependency versions in `package.json` are pinned exactly (no ranges);
bump them deliberately after compatibility verification (PRD #1).
