package com.safepath.backend.session.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionChannelStoreTest {
	private final SessionChannelStore store = new SessionChannelStore();
	private final UUID sessionId = UUID.randomUUID();

	@Test
	void savesFindsAndRemovesOnlyRequestedMapping() {
		UUID other = UUID.randomUUID();
		assertTrue(store.findBySessionId(sessionId).isEmpty());
		store.save(sessionId, "channel-1");
		store.save(other, "channel-2");
		assertEquals("channel-1", store.findBySessionId(sessionId).orElseThrow());
		assertEquals("channel-1", store.remove(sessionId).orElseThrow());
		assertTrue(store.remove(sessionId).isEmpty());
		assertEquals("channel-2", store.findBySessionId(other).orElseThrow());
	}

	@Test
	void duplicateMappingCannotOverwriteExistingValue() {
		store.save(sessionId, "channel-1");
		assertThrows(IllegalStateException.class, () -> store.save(sessionId, "channel-2"));
		assertEquals("channel-1", store.findBySessionId(sessionId).orElseThrow());
		assertFalse(store.removeIfMapped(sessionId, "channel-2"));
		assertTrue(store.removeIfMapped(sessionId, "channel-1"));
	}

	@Test
	void rejectsInvalidArguments() {
		assertThrows(NullPointerException.class, () -> store.save(null, "channel-1"));
		assertThrows(IllegalArgumentException.class, () -> store.save(sessionId, null));
		assertThrows(IllegalArgumentException.class, () -> store.save(sessionId, " "));
		assertThrows(NullPointerException.class, () -> store.findBySessionId(null));
		assertThrows(NullPointerException.class, () -> store.remove(null));
		assertThrows(NullPointerException.class, () -> store.removeIfMapped(null, "channel-1"));
		assertThrows(NullPointerException.class, () -> store.removeIfMapped(sessionId, null));
	}

	@Test
	void reverseLookupTracksBothRemovalMethodsAndKeepsOtherSession() {
		UUID other = UUID.randomUUID();
		assertTrue(store.findSessionIdByChannelId("channel-1").isEmpty());
		store.save(sessionId, "channel-1");
		store.save(other, "channel-2");
		assertEquals(sessionId, store.findSessionIdByChannelId("channel-1").orElseThrow());
		assertFalse(store.removeIfMapped(sessionId, "channel-2"));
		assertEquals(sessionId, store.findSessionIdByChannelId("channel-1").orElseThrow());
		assertTrue(store.removeIfMapped(sessionId, "channel-1"));
		assertTrue(store.findSessionIdByChannelId("channel-1").isEmpty());
		assertEquals(other, store.findSessionIdByChannelId("channel-2").orElseThrow());
		store.remove(other);
		assertTrue(store.findSessionIdByChannelId("channel-2").isEmpty());
	}

	@Test
	void reverseLookupRejectsInvalidAndAmbiguousChannels() {
		assertThrows(IllegalArgumentException.class, () -> store.findSessionIdByChannelId(null));
		assertThrows(IllegalArgumentException.class, () -> store.findSessionIdByChannelId(" "));
		store.save(sessionId, "shared-channel");
		UUID other = UUID.randomUUID();
		store.save(other, "shared-channel");
		assertThrows(IllegalStateException.class, () -> store.findSessionIdByChannelId("shared-channel"));
		store.remove(other);
		assertEquals(sessionId, store.findSessionIdByChannelId("shared-channel").orElseThrow());
	}

	@Test
	void concurrentMappingsAllowOnlyOneWinner() throws Exception {
		CountDownLatch startGate = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				String channelId = "channel-" + i;
				results.add(executor.submit(() -> {
					startGate.await();
					try {
						store.save(sessionId, channelId);
						return true;
					} catch (IllegalStateException duplicate) {
						return false;
					}
				}));
			}
			startGate.countDown();
			int successes = 0;
			for (Future<Boolean> result : results) {
				if (result.get(5, TimeUnit.SECONDS)) successes++;
			}
			assertEquals(1, successes);
			assertTrue(store.findBySessionId(sessionId).isPresent());
		}
	}
}
