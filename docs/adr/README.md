# Architecture Decision Records

This folder records the significant design decisions for the CQL Language Server: what was
decided, why, and what was rejected.

## Conventions

- One decision per file, named `NNNN-kebab-case-title.md` and numbered in order.
- YAML frontmatter with `title`, `status`, `date` and `tags`, and optionally `issues`.
- Status is `Proposed`, `Accepted`, `Deprecated` or `Superseded by ADR-NNNN`.
- Sections: Context, Decision, Alternatives Considered, Consequences, Agent Directives.
- Accepted ADRs aren't rewritten. To change a decision, write a new ADR that supersedes it, and
  update the old one's status.
- Decisions that span repositories live in the repository that owns the behaviour. The others
  link to it.

Related ADR folders:

- vscode-cql: <https://github.com/cqframework/vscode-cql/tree/master/docs/adr>
- cql-mcp: <https://github.com/cqframework/cql-mcp/tree/main/docs/adr>

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-runtime-network-access.md) | Runtime network access (FHIR packages, proxies, TLS) | Accepted |
