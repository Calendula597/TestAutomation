# Issue tracker: GitHub

Issues and PRDs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- Create: `gh issue create --title "..." --body "..."`.
- Read: `gh issue view <number> --comments`.
- List: `gh issue list --state open`.
- Comment: `gh issue comment <number> --body "..."`.
- Labels: `gh issue edit <number> --add-label "..."`.
- Close: `gh issue close <number> --comment "..."`.

Infer the repository from `git remote -v`.

## Pull requests as a triage surface

**PRs as a request surface: no.**

## Publishing and fetching

- When a skill says "publish to the issue tracker", create a GitHub issue.
- When a skill says "fetch the relevant ticket", read its full body and comments.

## Ticket relationships

Use GitHub's native issue dependencies for blocking relationships.
If unavailable, record `Blocked by: #<n>` in the issue body.
Do not close or modify parent issues when publishing child tickets.
