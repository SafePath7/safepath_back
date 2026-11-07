# Safe-Return Active Session Store

## Purpose and scope

`ActiveSessionStore` keeps ordinary active safe-return sessions in process memory. It does not use a database and does not implement session lifecycle APIs, controllers, or services.

The in-memory model is separate from JPA entities. It currently contains only the approved identifiers (`UUID` session ID and `Long` user ID); session state and other fields remain pending confirmation against the approved API specification.

## Structure

- `src/main/java/com/safepath/backend/session/model/ActiveSession.java` — immutable in-memory session model.
- `src/main/java/com/safepath/backend/session/store/ActiveSessionStore.java` — Spring component providing in-memory operations.
- `src/test/java/com/safepath/backend/session/store/ActiveSessionStoreTest.java` — unit tests.

## Methods

- `void saveSession(ActiveSession session)` stores a session. It throws `IllegalStateException` if the session UUID already exists or the user already has an active session.
- `Optional<ActiveSession> findById(UUID sessionId)` finds a session by UUID.
- `Optional<ActiveSession> findByUserId(Long userId)` finds the user's active session.
- `void remove(UUID sessionId)` removes a session; removing a missing ID has no effect.

Example:

```java
ActiveSession session = new ActiveSession(UUID.randomUUID(), 42L);
activeSessionStore.saveSession(session);

Optional<ActiveSession> found = activeSessionStore.findById(session.id());
activeSessionStore.remove(session.id());
```

There are no HTTP endpoints or request/response formats in this scope.

## Consistency and limitations

The store uses concurrent hash maps indexed by session UUID and user ID. Its public operations synchronize access so both indexes are updated atomically, and concurrent attempts to create sessions for the same user cannot silently overwrite one another. Only one active session per user is allowed.

Memory contents are lost when the application process restarts and are not shared across multiple application instances. Confirmed-risk persistence and coordination between instances require separate approved integration work.

## Database and configuration

This store does not access database tables, entities, repositories, migrations, or environment variables. Database schema changes are not required.

## Test instructions

Run the targeted unit test on Windows:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.store.ActiveSessionStoreTest"
```

The tests cover saving and lookup by both identifiers, absent lookups, removal, duplicate UUID and user rejection without replacement, null input, and concurrent attempts to save sessions for one user.

Tests executed and passed: `.\gradlew.bat test --tests "com.safepath.backend.session.store.ActiveSessionStoreTest"` (8 tests).

## Pending decisions

- Confirm the one-active-session-per-user policy against the approved API and business specification.
- Add session state or additional fields only after those fields are approved.
- Confirm how the store will integrate with the future session service and confirmed-risk handling.
