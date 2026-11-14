## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the five canonical Matt Pocock skill labels. See `docs/agents/triage-labels.md`.

### Domain docs

Use single-context domain documentation. See `docs/agents/domain.md`.

Use the canonical terms in [UBIQUITOUS_LANGUAGE.md](./UBIQUITOUS_LANGUAGE.md). The approved product authority is [PRD #1](https://github.com/Calendula597/TestAutomation/issues/1); vertical tickets are Issues #2–#26 with native blocking relationships.

### TypeScript package boundaries

TypeScript frontend and tooling packages are deep modules — see [packages/README.md](./packages/README.md) before adding or importing one. These rules do not apply to Java backend modules.
