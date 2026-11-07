# Safe-Return Active Session Store

## Purpose and scope

`ActiveSessionStore` keeps ordinary active safe-return sessions in process memory. It does not use a database or implement HTTP endpoints.

The in-memory model is separate from JPA entities. It contains a `UUID` session ID, `Long` user ID, and `Instant` start time. No session status is assigned because an approved status contract is not available in this checkout.

## Structure

- `src/main/java/com/safepath/backend/session/model/ActiveSession.java` — immutable in-memory session model.
- `src/main/java/com/safepath/backend/session/store/ActiveSessionStore.java` — Spring component providing in-memory operations.
- `src/main/java/com/safepath/backend/session/service/SafeReturnSessionService.java` — session start business logic.
- `src/test/java/com/safepath/backend/session/store/ActiveSessionStoreTest.java` — unit tests.
- `src/test/java/com/safepath/backend/session/service/SafeReturnSessionServiceTest.java` — session start unit tests.

## Starting a session

`ActiveSession startSession(Long userId)` rejects null or non-positive user IDs with `IllegalArgumentException`. For a valid ID, it creates a UUID and UTC `Instant` start time, saves the new model in `ActiveSessionStore`, and returns it. The store rejects a second active session for the same user with `IllegalStateException`; the service relies on that atomic check rather than performing a separate lookup.

The service validates the identifier's shape only. It does not authenticate the caller or check whether the user exists; those concerns require the approved authentication and user-service integration.

## Methods

- `void saveSession(ActiveSession session)` stores a session. It throws `IllegalStateException` if the session UUID already exists or the user already has an active session.
- `Optional<ActiveSession> findById(UUID sessionId)` finds a session by UUID.
- `Optional<ActiveSession> findByUserId(Long userId)` finds the user's active session.
- `void remove(UUID sessionId)` removes a session; removing a missing ID has no effect.

Example:

```java
ActiveSession session = safeReturnSessionService.startSession(42L);
Optional<ActiveSession> found = activeSessionStore.findById(session.id());
```

There are no HTTP endpoints or request/response formats in this scope.

## Consistency and limitations

The store uses concurrent hash maps indexed by session UUID and user ID. Its public operations synchronize access so both indexes are updated atomically, and concurrent attempts to create sessions for the same user cannot silently overwrite one another. Only one active session per user is allowed.

Memory contents are lost when the application process restarts and are not shared across multiple application instances. Confirmed-risk persistence and coordination between instances require separate approved integration work.

## Database and configuration

Session start and storage do not access database tables, entities, repositories, migrations, or environment variables. Database schema changes are not required.

## Test instructions

Run the targeted unit test on Windows:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.service.SafeReturnSessionServiceTest" --tests "com.safepath.backend.session.store.ActiveSessionStoreTest"
```

The tests cover saving and lookup by both identifiers, absent lookups, removal, duplicate UUID and user rejection without replacement, model null validation, session start and persistence, generated UUID and start time, invalid user IDs, duplicate starts, and concurrent start attempts.

Tests executed and passed: `.\gradlew.bat test --tests "com.safepath.backend.session.service.SafeReturnSessionServiceTest" --tests "com.safepath.backend.session.store.ActiveSessionStoreTest"` (12 tests).

## Pending decisions

- Add session state or additional fields only after those fields are approved.
- Confirm how the store will integrate with the future session service and confirmed-risk handling.
