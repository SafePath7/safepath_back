package com.safepath.backend.session.store;

import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestLocationStoreTest {
	private final ActiveSessionStore sessions = new ActiveSessionStore();
	private final LatestLocationStore locations = new LatestLocationStore(sessions);
	private final UUID sessionId = UUID.randomUUID();
	private final Instant capturedAt = Instant.parse("2026-11-15T00:00:00Z");

	LatestLocationStoreTest() {
		sessions.saveSession(new ActiveSession(sessionId, 42L, capturedAt));
	}

	@Test
	void savesLatestMeasurementAndKeepsSessionsIsolated() {
		UUID other = UUID.randomUUID();
		sessions.saveSession(new ActiveSession(other, 43L, capturedAt));
		assertTrue(locations.findBySessionId(sessionId).isEmpty());
		LocationUpdateRequest first = location(capturedAt);
		assertSame(first, locations.updateLocation(sessionId, 42L, first));
		LocationUpdateRequest latest = location(capturedAt.plusSeconds(1));
		locations.updateLocation(sessionId, 42L, latest);
		assertSame(latest, locations.findBySessionId(sessionId).orElseThrow());
		assertTrue(locations.findBySessionId(other).isEmpty());
	}

	@Test
	void outOfOrderOrEqualTimestampCannotOverwriteLatest() {
		LocationUpdateRequest latest = location(capturedAt);
		locations.updateLocation(sessionId, 42L, latest);
		assertSame(latest, locations.updateLocation(sessionId, 42L, location(capturedAt.minusSeconds(1))));
		assertSame(latest, locations.updateLocation(sessionId, 42L,
				new LocationUpdateRequest(0, 0, null, capturedAt)));
	}

	@Test
	void rejectsNonOwnerAndMissingOrEndedSession() {
		assertThrows(SecurityException.class, () -> locations.updateLocation(sessionId, 43L, location(capturedAt)));
		assertTrue(locations.findBySessionId(sessionId).isEmpty());
		assertThrows(NoSuchElementException.class,
				() -> locations.updateLocation(UUID.randomUUID(), 42L, location(capturedAt)));
		sessions.remove(sessionId);
		assertThrows(NoSuchElementException.class, () -> locations.updateLocation(sessionId, 42L, location(capturedAt)));
		assertThrows(NoSuchElementException.class, () -> locations.findBySessionId(sessionId));
	}

	@Test
	void rejectsInvalidMeasurementsWithoutReplacingExistingLocation() {
		LocationUpdateRequest original = location(capturedAt);
		locations.updateLocation(sessionId, 42L, original);
		List<LocationUpdateRequest> invalid = List.of(
				new LocationUpdateRequest(91, 0, null, capturedAt),
				new LocationUpdateRequest(-91, 0, null, capturedAt),
				new LocationUpdateRequest(0, 181, null, capturedAt),
				new LocationUpdateRequest(0, -181, null, capturedAt),
				new LocationUpdateRequest(Double.NaN, 0, null, capturedAt),
				new LocationUpdateRequest(0, Double.POSITIVE_INFINITY, null, capturedAt),
				new LocationUpdateRequest(0, 0, -1.0, capturedAt),
				new LocationUpdateRequest(0, 0, Double.NaN, capturedAt),
				new LocationUpdateRequest(0, 0, Double.POSITIVE_INFINITY, capturedAt),
				new LocationUpdateRequest(0, 0, null, null));
		for (LocationUpdateRequest request : invalid) {
			assertThrows(IllegalArgumentException.class, () -> locations.updateLocation(sessionId, 42L, request));
		}
		assertSame(original, locations.findBySessionId(sessionId).orElseThrow());
	}

	@Test
	void rejectsInvalidUpdateArguments() {
		assertThrows(IllegalArgumentException.class, () -> locations.updateLocation(null, 42L, location(capturedAt)));
		assertThrows(IllegalArgumentException.class, () -> locations.updateLocation(sessionId, null, location(capturedAt)));
		assertThrows(IllegalArgumentException.class, () -> locations.updateLocation(sessionId, 0L, location(capturedAt)));
		assertThrows(IllegalArgumentException.class, () -> locations.updateLocation(sessionId, 42L, null));
	}

	@Test
	void removalIsIdempotentAndOnlyRemovesRequestedSession() {
		UUID other = UUID.randomUUID();
		sessions.saveSession(new ActiveSession(other, 43L, capturedAt));
		locations.updateLocation(sessionId, 42L, location(capturedAt));
		locations.updateLocation(other, 43L, location(capturedAt));
		locations.remove(sessionId);
		locations.remove(sessionId);
		assertTrue(locations.findBySessionId(sessionId).isEmpty());
		assertTrue(locations.findBySessionId(other).isPresent());
	}

	@Test
	void concurrentMeasurementsKeepNewestCapturedAt() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var newer = executor.submit(() -> {
				start.await();
				return locations.updateLocation(sessionId, 42L, location(capturedAt.plusSeconds(1)));
			});
			var older = executor.submit(() -> {
				start.await();
				return locations.updateLocation(sessionId, 42L, location(capturedAt));
			});
			start.countDown();
			newer.get(5, TimeUnit.SECONDS);
			older.get(5, TimeUnit.SECONDS);
		}
		assertEquals(capturedAt.plusSeconds(1), locations.findBySessionId(sessionId).orElseThrow().capturedAt());
	}

	private LocationUpdateRequest location(Instant time) {
		return new LocationUpdateRequest(37.5, 127.0, 5.0, time);
	}
}
