# TypeScript packages

Each immediate child of `packages/` is a deep module. Copy the `example/`
package as a starting point.

```text
packages/<name>/
├── index.ts
├── lib/
│   └── impl.ts
└── tests/
    └── example.test.ts
```

- **Entry-point boundary:** outside code may import only package root files;
  everything inside package subfolders is private.
- **Intra-package freedom:** files within one package may import each other.
- **Tests through entry points:** tests import public entry points and their
  own test fixtures, never implementation internals.
- **No cycles:** package dependencies must be acyclic.

Run `npm run lint:boundaries` to enforce these rules. Avoid barrel files that
re-export an entire subtree; expose several small root entry points instead.
