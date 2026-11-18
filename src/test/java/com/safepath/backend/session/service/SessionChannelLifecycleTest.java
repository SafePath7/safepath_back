package com.safepath.backend.session.service;

import com.safepath.backend.session.exception.SessionChannelException;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SessionChannelLifecycleTest {
	private final ActiveSessionStore sessions = new ActiveSessionStore();
	private final SelectedGuardianStore guardians = new SelectedGuardianStore(sessions);
	private final SessionChannelStore mappings = spy(new SessionChannelStore());
	private final InMemoryChannelRegistry channels = spy(new InMemoryChannelRegistry());
	private final GuardianPermissionService permissions = org.mockito.Mockito.mock(GuardianPermissionService.class);
	private final SafeReturnSessionService service = new SafeReturnSessionService(sessions, guardians, mappings, channels, new GuardianSelectionService(sessions, guardians, permissions));

	@Test
	void startsGuardianChannelAndEndsItWithoutAffectingAnotherSession() {
		ActiveSession session = startSession(42L);
		String channelId = mappings.findBySessionId(session.id()).orElseThrow();
		verify(channels).open(42L, Set.of(1042L));
		assertTrue(channels.isLocationPublisher(channelId, 42L));
		assertFalse(channels.isParticipant(channelId, 43L));
		assertTrue(channels.isParticipant(channelId, 1042L));
		ActiveSession other = startSession(43L);
		String otherChannelId = mappings.findBySessionId(other.id()).orElseThrow();
		guardians.save(session.id(), Set.of(44L));

		service.endSession(session.id(), 42L);

		assertFalse(channels.exists(channelId));
		assertTrue(mappings.findBySessionId(session.id()).isEmpty());
		assertTrue(sessions.findById(session.id()).isEmpty());
		assertTrue(guardians.findBySessionId(session.id()).isEmpty());
		assertTrue(channels.exists(otherChannelId));
		assertEquals(otherChannelId, mappings.findBySessionId(other.id()).orElseThrow());
	}

	@Test
	void duplicateStartDoesNotCreateAnotherChannel() {
		ActiveSession session = startSession(42L);
		String channelId = mappings.findBySessionId(session.id()).orElseThrow();
		assertThrows(IllegalStateException.class, () -> startSession(42L));
		verify(channels, times(1)).open(anyLong(), any());
		assertTrue(channels.exists(channelId));
		assertSame(session, sessions.findByUserId(42L).orElseThrow());
	}

	@Test
	void wrongOwnerAndMissingSessionCannotCloseExistingChannel() {
		ActiveSession session = startSession(42L);
		String channelId = mappings.findBySessionId(session.id()).orElseThrow();
		assertThrows(SecurityException.class, () -> service.endSession(session.id(), 43L));
		assertThrows(java.util.NoSuchElementException.class, () -> service.endSession(UUID.randomUUID(), 42L));
		verify(channels, times(0)).close(any());
		assertSame(session, sessions.findById(session.id()).orElseThrow());
		assertEquals(channelId, mappings.findBySessionId(session.id()).orElseThrow());
		assertTrue(channels.exists(channelId));
	}

	@Test
	void channelCreationFailureRemovesOnlyNewSessionAndAllowsRetry() {
		ActiveSession other = startSession(43L);
		String otherChannelId = mappings.findBySessionId(other.id()).orElseThrow();
		doThrow(new IllegalStateException("Channel unavailable")).when(channels).open(42L, Set.of(1042L));
		assertThrows(SessionChannelException.class, () -> startSession(42L));
		assertTrue(sessions.findByUserId(42L).isEmpty());
		assertTrue(channels.exists(otherChannelId));
		assertSame(other, sessions.findById(other.id()).orElseThrow());
	}

	@Test
	void mappingFailureClosesNewChannelAndRemovesNewSession() {
		doThrow(new IllegalStateException("Mapping unavailable")).when(mappings).save(any(), any());
		assertThrows(SessionChannelException.class, () -> startSession(42L));
		assertTrue(sessions.findByUserId(42L).isEmpty());
		var channelId = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(channels).close(channelId.capture());
		assertFalse(channels.exists(channelId.getValue()));
	}

	@Test
	void failedEndPreservesResourcesUntilSuccessfulRetry() {
		ActiveSession session = startSession(42L);
		String channelId = mappings.findBySessionId(session.id()).orElseThrow();
		guardians.save(session.id(), Set.of(44L));
		doThrow(new IllegalStateException("Close unavailable")).when(channels).close(channelId);
		assertThrows(SessionChannelException.class, () -> service.endSession(session.id(), 42L));
		assertSame(session, sessions.findById(session.id()).orElseThrow());
		assertEquals(channelId, mappings.findBySessionId(session.id()).orElseThrow());
		assertEquals(Set.of(44L), guardians.findBySessionId(session.id()));
		assertTrue(channels.exists(channelId));
		org.mockito.Mockito.doCallRealMethod().when(channels).close(channelId);
		service.endSession(session.id(), 42L);
		assertFalse(channels.exists(channelId));
		assertTrue(mappings.findBySessionId(session.id()).isEmpty());
		assertTrue(sessions.findById(session.id()).isEmpty());
	}

	@Test
	void mappingFailureAfterWriteAlsoRemovesOnlyNewMapping() {
		org.mockito.Mockito.doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("Mapping completion failed");
		}).when(mappings).save(any(), any());
		assertThrows(SessionChannelException.class, () -> startSession(42L));
		var sessionId = org.mockito.ArgumentCaptor.forClass(UUID.class);
		var channelId = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(mappings).save(sessionId.capture(), channelId.capture());
		assertTrue(sessions.findById(sessionId.getValue()).isEmpty());
		assertTrue(mappings.findBySessionId(sessionId.getValue()).isEmpty());
		assertFalse(channels.exists(channelId.getValue()));
	}

	@Test
	void failedRollbackClosePreservesSessionAndChannelForEndRetry() {
		org.mockito.Mockito.doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("Mapping completion failed");
		}).when(mappings).save(any(), any());
		doThrow(new IllegalStateException("Close unavailable")).when(channels).close(any());
		assertThrows(SessionChannelException.class, () -> startSession(42L));
		ActiveSession session = sessions.findByUserId(42L).orElseThrow();
		String channelId = mappings.findBySessionId(session.id()).orElseThrow();
		assertTrue(channels.exists(channelId));
		org.mockito.Mockito.doCallRealMethod().when(channels).close(channelId);
		service.endSession(session.id(), 42L);
		assertTrue(sessions.findById(session.id()).isEmpty());
		assertTrue(mappings.findBySessionId(session.id()).isEmpty());
		assertFalse(channels.exists(channelId));
	}

	@Test
	void concurrentStartsCreateOnlyOneChannel() throws Exception {
		CountDownLatch startGate = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				results.add(executor.submit(() -> {
					startGate.await();
					try {
						startSession(42L);
						return true;
					} catch (IllegalStateException duplicate) {
						return false;
					}
				}));
			}
			startGate.countDown();
			int successfulStarts = 0;
			for (Future<Boolean> result : results) {
				if (result.get(5, TimeUnit.SECONDS)) successfulStarts++;
			}
			assertEquals(1, successfulStarts);
			verify(channels, times(1)).open(42L, Set.of(1042L));
			ActiveSession session = sessions.findByUserId(42L).orElseThrow();
			assertTrue(channels.exists(mappings.findBySessionId(session.id()).orElseThrow()));
		}
	}

	private ActiveSession startSession(Long userId) {
		Long guardianId = userId + 1000;
		org.mockito.Mockito.when(permissions.hasAcceptedRelationship(userId, guardianId)).thenReturn(true);
		org.mockito.Mockito.when(permissions.hasPermission(userId, guardianId,
				com.safepath.backend.session.model.MonitoringPermissionType.LOCATION)).thenReturn(true);
		return service.startSession(userId, List.of(guardianId));
	}
}
