# Safe-Return Active Session Store

## Purpose and scope

`ActiveSessionStore` keeps ordinary active safe-return sessions in process memory. It does not use a database or implement HTTP endpoints.

The in-memory model is separate from JPA entities. It contains a `UUID` session ID, `Long` user ID, and `Instant` start time. No session status is assigned because an approved status contract is not available in this checkout.

## Structure

- `src/main/java/com/safepath/backend/session/model/ActiveSession.java` — immutable in-memory session model.
- `src/main/java/com/safepath/backend/session/store/ActiveSessionStore.java` — Spring component providing in-memory operations.
- `src/main/java/com/safepath/backend/session/service/SafeReturnSessionService.java` — session start, active-session lookup, and session termination business logic.
- `src/test/java/com/safepath/backend/session/store/ActiveSessionStoreTest.java` — unit tests.
- `src/test/java/com/safepath/backend/session/service/SafeReturnSessionServiceTest.java` — session start unit tests.

## Starting a session

`ActiveSession startSession(Long userId)` rejects null or non-positive user IDs with `IllegalArgumentException`. For a valid ID, it creates a UUID and UTC `Instant` start time, saves the new model in `ActiveSessionStore`, and returns it. The store rejects a second active session for the same user with `IllegalStateException`; the service relies on that atomic check rather than performing a separate lookup.

The service validates the identifier's shape only. It does not authenticate the caller or check whether the user exists; those concerns require the approved authentication and user-service integration.

## Getting an active session

`ActiveSession getSessionStatus(UUID sessionId, Long userId)` looks up the current in-memory session by UUID and returns it only when its `userId` matches the supplied user ID. It throws `IllegalArgumentException` for a null session ID or a null/non-positive user ID, `NoSuchElementException` when no active session exists, and `SecurityException` when the session belongs to another user. It does not query ended-session history or return a session status field; the current model has no status field.

The method enforces ownership against the supplied ID but does not authenticate that ID. A future HTTP layer must derive or validate the user identity through the approved authentication integration and decide how internal exceptions map to external error responses.

## Methods

- `void saveSession(ActiveSession session)` stores a session. It throws `IllegalStateException` if the session UUID already exists or the user already has an active session.
- `Optional<ActiveSession> findById(UUID sessionId)` finds a session by UUID.
- `Optional<ActiveSession> findByUserId(Long userId)` finds the user's active session.
- `void remove(UUID sessionId)` removes a session; removing a missing ID has no effect.
- `void endSession(UUID sessionId, Long userId)` removes the active session only when the supplied user owns it. It throws `IllegalArgumentException` for invalid input, `NoSuchElementException` when the session is missing or already ended, and `SecurityException` when another user owns the session.
- `ActiveSession getSessionStatus(UUID sessionId, Long userId)` returns the active session after checking its owner.

Example:

```java
ActiveSession session = safeReturnSessionService.startSession(42L);
Optional<ActiveSession> found = activeSessionStore.findById(session.id());
safeReturnSessionService.endSession(session.id(), 42L);
```

There are no HTTP endpoints or request/response formats in this scope.

## Consistency and limitations

The store uses concurrent hash maps indexed by session UUID and user ID. Its public operations synchronize access so both indexes are updated atomically, and concurrent attempts to create sessions for the same user cannot silently overwrite one another. Only one active session per user is allowed.

Session termination uses the store's synchronized `removeIfOwned(UUID, Long)` operation, which checks existence and ownership and removes both indexes within one lock. A non-owner cannot remove a session. For simultaneous end requests, exactly one request succeeds; subsequent requests receive the same not-found error as an already-ended session. Ending a session releases the user's active-session slot so a new one can be started.

Memory contents are lost when the application process restarts and are not shared across multiple application instances. Confirmed-risk persistence and coordination between instances require separate approved integration work.

## Database and configuration

Session start, lookup, and termination do not access database tables, entities, repositories, migrations, or environment variables. Ending an ordinary active session only removes it from memory; it does not persist a history record. Database schema changes are not required.

## Test instructions

Run the targeted unit test on Windows:

```powershell
.\gradlew.bat test --tests "com.safepath.backend.session.service.SafeReturnSessionServiceTest" --tests "com.safepath.backend.session.store.ActiveSessionStoreTest"
```

The tests cover saving and lookup by both identifiers, absent lookups, removal, duplicate UUID and user rejection without replacement, model null validation, session start and persistence, generated UUID and start time, invalid user IDs, duplicate starts, concurrent start attempts, owned session lookup, missing/deleted sessions, invalid lookup arguments, and rejection of another user's session.

Session termination coverage additionally includes owner-only removal and removal outcomes, deletion from both indexes, new session start after ending, missing/already-ended sessions, invalid inputs, and concurrent end requests.

### Test results and current limitations

- Command: `.\gradlew.bat test`
- Result: **25 tests total: 24 passed, 1 failed, 0 skipped.**
- Failed test: `BackendApplicationTests.contextLoads()`. Spring context initialization fails because the default profile has no DataSource URL configured (`Failed to determine a suitable driver class`).
- The session service tests (15) and store tests (9) all passed.
- Connecting to the local PostgreSQL environment and rerunning the complete test suite remain follow-up work.
- Flyway schema management and the Hibernate `ddl-auto` setting will be confirmed with the database-owning team before applying any related configuration.

The full test suite is **not fully passing**. Its context-load test remains blocked by the default-profile database configuration; the session unit-test results above are separate from that limitation.

## Pending decisions

- Add session state or additional fields only after those fields are approved.
- Confirm how the store will integrate with the future session service and confirmed-risk handling.
