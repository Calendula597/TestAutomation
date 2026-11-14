# Domain Docs

## Before exploring, read these

- `CONTEXT.md` at the repo root.
- ADRs in `docs/adr/` that affect the area being changed.

If these files do not exist, proceed silently. Domain modeling creates them
lazily when terms or decisions are resolved.

## Layout

Use a single-context layout: one root `CONTEXT.md` and `docs/adr/`.

## Vocabulary and decisions

- Use the project's canonical domain vocabulary in issues, specs, tickets,
  tests, and implementation.
- Flag missing or ambiguous terms instead of inventing synonyms.
- Surface conflicts with existing ADRs explicitly rather than silently
  overriding them.
