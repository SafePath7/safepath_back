---
name: database-development
description: Safely assess and implement SafePath database, JPA Entity, Repository, and Flyway changes with approval-aware impact analysis.
---

# SafePath database development

Before making changes, read `AGENTS.md`, `docs/ai-rules/common.md`, `docs/ai-rules/database.md`, and `docs/ai-rules/backend.md`.

Inspect the current schema migrations, Entity mappings, Repository usage, and callers. Explain the DB impact and obtain user approval before changing existing tables, columns, relations, constraints, mappings, or applied migration history. Preserve `ddl-auto=validate` behavior and use the existing Flyway policy.

Read `docs/ai-rules/integration.md` for authentication, notification, or WebSocket data flows, and `docs/ai-rules/testing.md` for validation. Report migrations, mapping effects, compatibility risks, and verification performed.
