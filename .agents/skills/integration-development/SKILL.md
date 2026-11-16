---
name: integration-development
description: Implement SafePath Firebase, authorization, WebSocket/STOMP, FCM, and notification integrations without weakening existing security controls.
---

# SafePath integration development

Before making changes, read `AGENTS.md`, `docs/ai-rules/common.md`, and `docs/ai-rules/integration.md`.

Inspect and reuse the current authentication filters, security configuration, channel registry, subscription authorization, FCM, and notification implementations. Do not bypass authentication, authorization, or ownership checks, and do not trust client-provided identities without server validation.

Read `docs/ai-rules/backend.md` for REST or service-layer changes, `docs/ai-rules/database.md` for persistence changes, and `docs/ai-rules/testing.md` for verification. Keep shared security and integration structures unchanged unless the user approves the impact.
