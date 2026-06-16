# .github directory structure

```
.github/
├── README.md              ← this file
├── codecov.yml             ← Codecov configuration
└── workflows/
    ├── add-to-platform-project.yml  ← adds new issues to project board
    ├── build.yml                    ← deploys SNAPSHOT artifacts to Maven Central
    └── ci.yml                       ← main CI: format, test, coverage
```

## Why `codecov.yml` is here, not in `workflows/`

The `workflows/` directory is reserved for GitHub Actions workflow files
(`.yml`/`.yaml`). The [Codecov
CLI](https://docs.codecov.com/docs/codecovyml-reference) searches for its
config at one of these paths, in order:

1. `.codecov.yml`
2. `codecov.yml`
3. `.github/codecov.yml`

A file inside `workflows/` would be silently ignored — Codecov never looks
there. Putting it at `.github/codecov.yml` keeps it alongside the CI
configuration without polluting the repo root.

## Workflow summary

| File | Trigger | Jobs |
|------|---------|------|
| `ci.yml` | push → master, pull_request → master | `verify-changelog` (PR only), `format`, `test` (ubuntu / macos / windows) |
| `build.yml` | push → master | `maven` — SNAPSHOT deploy to Maven Central |
| `add-to-platform-project.yml` | issues → opened | adds issue to alphora/platform project board |
