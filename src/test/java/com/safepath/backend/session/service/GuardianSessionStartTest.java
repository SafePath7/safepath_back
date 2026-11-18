package com.safepath.backend.session.service;

import com.safepath.backend.session.exception.SessionChannelException;
import com.safepath.backend.session.model.MonitoringPermissionType;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.LatestLocationStore;
import com.safepath.backend.session.store.SelectedGuardianStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import com.safepath.backend.websocket.dto.LocationUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

class GuardianSessionStartTest {
	private final ActiveSessionStore sessions = new ActiveSessionStore();
	private final SelectedGuardianStore selected = spy(new SelectedGuardianStore(sessions));
	private final SessionChannelStore mappings = spy(new SessionChannelStore());
	private final InMemoryChannelRegistry channels = spy(new InMemoryChannelRegistry());
	private final LatestLocationStore locations = spy(new LatestLocationStore(sessions));
	private final GuardianPermissionService permissions = mock(GuardianPermissionService.class);
	private final GuardianSelectionService selection = new GuardianSelectionService(sessions, selected, permissions);
	private final SafeReturnSessionService service = new SafeReturnSessionService(
			sessions, selected, mappings, channels, locations, selection);

	@Test
	void oneGuardianStartsWithMatchingSelectionChannelAndMapping() {
		allow(43L);
		var session = service.startSession(42L, List.of(43L));
		String channel = mappings.findBySessionId(session.id()).orElseThrow();
		assertEquals(Set.of(43L), selection.getSelectedGuardians(session.id(), 42L));
		assertTrue(channels.isParticipant(channel, 42L));
		assertTrue(channels.isParticipant(channel, 43L));
		assertFalse(channels.isLocationPublisher(channel, 43L));
		verify(channels, times(1)).open(42L, Set.of(43L));
	}

	@Test
	void multipleGuardiansAreAllRegisteredAndDuplicatesAreNormalized() {
		allow(43L);
		allow(44L);
		var session = service.startSession(42L, List.of(43L, 44L, 43L));
		String channel = mappings.findBySessionId(session.id()).orElseThrow();
		assertEquals(Set.of(43L, 44L), selected.findBySessionId(session.id()));
		assertTrue(channels.isParticipant(channel, 43L));
		assertTrue(channels.isParticipant(channel, 44L));
		verify(permissions, times(1)).hasAcceptedRelationship(42L, 43L);
		verify(channels, times(1)).open(42L, Set.of(43L, 44L));
	}

	@Test
	void invalidListsNeverCreateResources() {
		assertThrows(IllegalArgumentException.class, () -> service.startSession(42L, null));
		assertThrows(IllegalArgumentException.class, () -> service.startSession(42L, List.of()));
		assertThrows(IllegalArgumentException.class, () -> service.startSession(42L, Arrays.asList(43L, null)));
		assertThrows(IllegalArgumentException.class, () -> service.startSession(42L, List.of(0L)));
		assertThrows(AccessDeniedException.class, () -> service.startSession(42L, List.of(42L)));
		assertNoResources();
	}

	@Test
	void missingOrUnacceptedGuardianRejectsStart() {
		assertThrows(AccessDeniedException.class, () -> service.startSession(42L, List.of(43L)));
		assertNoResources();
	}

	@Test
	void statusOnlyGuardianCannotStart() {
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenReturn(true);
		when(permissions.hasPermission(42L, 43L, MonitoringPermissionType.STATUS)).thenReturn(true);
		assertThrows(AccessDeniedException.class, () -> service.startSession(42L, List.of(43L)));
		assertNoResources();
	}

	@Test
	void oneDeniedGuardianRejectsWholeStart() {
		allow(43L);
		assertThrows(AccessDeniedException.class, () -> service.startSession(42L, List.of(43L, 44L)));
		assertNoResources();
	}

	@Test
	void lookupFailureDoesNotCreateResources() {
		when(permissions.hasAcceptedRelationship(42L, 43L)).thenThrow(new IllegalStateException("Unavailable"));
		assertThrows(IllegalStateException.class, () -> service.startSession(42L, List.of(43L)));
		assertNoResources();
	}

	@Test
	void selectionSaveFailureAfterWriteIsCompensatedBeforeChannelCreation() {
		allow(43L);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("Selection failed");
		}).when(selected).save(any(), any());
		assertThrows(SessionChannelException.class, () -> service.startSession(42L, List.of(43L)));
		var id = org.mockito.ArgumentCaptor.forClass(UUID.class);
		verify(selected).save(id.capture(), any());
		assertTrue(sessions.findByUserId(42L).isEmpty());
		assertTrue(selected.findBySessionId(id.getValue()).isEmpty());
		verify(channels, never()).open(any(), any());
	}

	@Test
	void channelCreationFailureRemovesSessionAndSelection() {
		allow(43L);
		doThrow(new IllegalStateException("Channel failed")).when(channels).open(any(), any());
		assertThrows(SessionChannelException.class, () -> service.startSession(42L, List.of(43L)));
		var id = org.mockito.ArgumentCaptor.forClass(UUID.class);
		verify(selected).save(id.capture(), any());
		assertTrue(sessions.findByUserId(42L).isEmpty());
		assertTrue(selected.findBySessionId(id.getValue()).isEmpty());
		assertTrue(mappings.findBySessionId(id.getValue()).isEmpty());
	}

	@Test
	void mappingFailureAfterWriteCleansAllCreatedResources() {
		allow(43L);
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("Mapping failed");
		}).when(mappings).save(any(), any());
		assertThrows(SessionChannelException.class, () -> service.startSession(42L, List.of(43L)));
		var id = org.mockito.ArgumentCaptor.forClass(UUID.class);
		var channel = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(mappings).save(id.capture(), channel.capture());
		assertTrue(sessions.findByUserId(42L).isEmpty());
		assertTrue(selected.findBySessionId(id.getValue()).isEmpty());
		assertTrue(mappings.findBySessionId(id.getValue()).isEmpty());
		assertFalse(channels.exists(channel.getValue()));
	}

	@Test
	void duplicateAndConcurrentStartsCreateOnlyOneChannel() throws Exception {
		allow(43L);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				results.add(executor.submit(() -> {
					start.await();
					try {
						service.startSession(42L, List.of(43L));
						return true;
					} catch (IllegalStateException duplicate) {
						return false;
					}
				}));
			}
			start.countDown();
			int successes = 0;
			for (var result : results) if (result.get(5, TimeUnit.SECONDS)) successes++;
			assertEquals(1, successes);
		}
		assertThrows(IllegalStateException.class, () -> service.startSession(42L, List.of(43L)));
		verify(channels, times(1)).open(42L, Set.of(43L));
	}

	@Test
	void startAndEndRaceCannotDeleteReplacementSessionResources() throws Exception {
		allow(43L);
		allow(44L);
		var original = service.startSession(42L, List.of(43L));
		String originalChannel = mappings.findBySessionId(original.id()).orElseThrow();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var replacement = executor.submit(() -> {
				start.await();
				try {
					return service.startSession(42L, List.of(44L));
				} catch (IllegalStateException duplicate) {
					return null;
				}
			});
			var end = executor.submit(() -> {
				start.await();
				service.endSession(original.id(), 42L);
				return true;
			});
			start.countDown();
			var next = replacement.get(5, TimeUnit.SECONDS);
			end.get(5, TimeUnit.SECONDS);
			assertTrue(selected.findBySessionId(original.id()).isEmpty());
			assertTrue(mappings.findBySessionId(original.id()).isEmpty());
			assertFalse(channels.exists(originalChannel));
			if (next == null) {
				assertTrue(sessions.findByUserId(42L).isEmpty());
			} else {
				assertEquals(next, sessions.findByUserId(42L).orElseThrow());
				assertEquals(Set.of(44L), selected.findBySessionId(next.id()));
				assertTrue(channels.isParticipant(mappings.findBySessionId(next.id()).orElseThrow(), 44L));
			}
		}
	}

	@Test
	void failedNewStartLeavesExistingOtherSessionUntouched() {
		allowFor(45L, 46L);
		var other = service.startSession(45L, List.of(46L));
		String otherChannel = mappings.findBySessionId(other.id()).orElseThrow();
		selected.save(other.id(), Set.of(46L));
		allow(43L);
		doThrow(new IllegalStateException("Channel failed")).when(channels).open(42L, Set.of(43L));
		assertThrows(SessionChannelException.class, () -> service.startSession(42L, List.of(43L)));
		assertEquals(other, sessions.findById(other.id()).orElseThrow());
		assertEquals(Set.of(46L), selected.findBySessionId(other.id()));
		assertTrue(channels.exists(otherChannel));
		assertEquals(otherChannel, mappings.findBySessionId(other.id()).orElseThrow());
	}

	@Test
	void endRemovesSelectionLocationChannelAndMapping() {
		allow(43L);
		var session = service.startSession(42L, List.of(43L));
		String channel = mappings.findBySessionId(session.id()).orElseThrow();
		locations.updateLocation(session.id(), 42L, new LocationUpdateRequest(37, 127, null, Instant.now()));
		service.endSession(session.id(), 42L);
		assertTrue(sessions.findById(session.id()).isEmpty());
		assertTrue(selected.findBySessionId(session.id()).isEmpty());
		assertTrue(mappings.findBySessionId(session.id()).isEmpty());
		assertFalse(channels.exists(channel));
		verify(locations).remove(session.id());
	}

	private void allow(Long guardianId) {
		allowFor(42L, guardianId);
	}

	private void allowFor(Long ownerId, Long guardianId) {
		when(permissions.hasAcceptedRelationship(ownerId, guardianId)).thenReturn(true);
		when(permissions.hasPermission(ownerId, guardianId, MonitoringPermissionType.LOCATION)).thenReturn(true);
	}

	private void assertNoResources() {
		assertTrue(sessions.findByUserId(42L).isEmpty());
		verify(channels, never()).open(any(), any());
		verify(selected, never()).save(any(), any());
		verify(mappings, never()).save(any(), any());
	}
}
