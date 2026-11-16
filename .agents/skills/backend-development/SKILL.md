---
name: backend-development
description: Implement or modify SafePath REST APIs and Spring backend behavior while preserving existing layering and project conventions.
---

# SafePath backend development

Before making changes, read `AGENTS.md`, `docs/ai-rules/common.md`, and `docs/ai-rules/backend.md`.

Inspect relevant existing packages and reuse their Controller, Service, Repository, DTO, Entity, and helper behavior before adding code. Keep changes within the user's request and do not change another owner's code or shared contracts without approval.

Read `docs/ai-rules/database.md` when the task touches Entity, Repository, persistence, schema, or Flyway. Read `docs/ai-rules/integration.md` for Firebase, authorization, WebSocket/STOMP, FCM, or notifications. Read `docs/ai-rules/testing.md` when creating, changing, or running tests.

Apply the selected rules, validate the relevant behavior, and report changed files plus verification results.
