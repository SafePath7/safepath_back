package com.safepath.backend.session.websocket;

import com.safepath.backend.auth.FirebaseTokenService;
import com.safepath.backend.config.FirebaseConfig;
import com.safepath.backend.guardian.entity.GuardianRegistration;
import com.safepath.backend.guardian.entity.RegistrationStatus;
import com.safepath.backend.guardian.repository.GuardianRegistrationRepository;
import com.safepath.backend.session.model.ActiveSession;
import com.safepath.backend.session.service.GuardianMonitoringService;
import com.safepath.backend.session.service.SafeReturnSessionService;
import com.safepath.backend.session.store.ActiveSessionStore;
import com.safepath.backend.session.store.SessionChannelStore;
import com.safepath.backend.user.UserService;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import com.safepath.backend.websocket.StompAuthenticationChannelInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuardianSubscriptionStompIntegrationTest {
	@LocalServerPort int port;
	@Autowired SafeReturnSessionService lifecycle;
	@Autowired ActiveSessionStore sessions;
	@Autowired SessionChannelStore mappings;
	@Autowired @Qualifier("clientInboundChannel") AbstractMessageChannel inbound;
	@Autowired @Qualifier("simpleBrokerMessageHandler") SimpleBrokerMessageHandler broker;
	@MockitoSpyBean InMemoryChannelRegistry channels;
	@MockitoSpyBean GuardianMonitoringService monitoring;
	@MockitoSpyBean GuardianLocationSubscriptionInterceptor subscriptions;
	@MockitoBean FirebaseConfig firebaseConfig;
	@MockitoBean FirebaseTokenService tokens;
	@MockitoBean UserService users;
	@MockitoBean GuardianRegistrationRepository repository;
	private final List<StompSocket> sockets = new ArrayList<>();
	private GuardianRegistration registration;
	private ActiveSession session;
	private String channelId;

	@BeforeEach
	void prepareActiveSessionAndVerifiedAccounts() {
		registration = BeanUtils.instantiateClass(GuardianRegistration.class);
		registration.changeStatus(RegistrationStatus.ACCEPTED);
		registration.changePermissions(true, false, false);
		for (long guardianId : new long[]{102L, 103L, 104L}) {
			when(repository.findByUserIdAndGuardianId(101L, guardianId)).thenReturn(Optional.of(registration));
		}
		for (long id : new long[]{101L, 102L, 103L, 104L}) {
			when(tokens.verifyIdToken("token-" + id)).thenReturn("verified-uid-" + id);
			when(users.findUserIdByFirebaseUid("verified-uid-" + id)).thenReturn(Optional.of(id));
		}
		session = lifecycle.startSession(101L, List.of(102L, 103L));
		channelId = mappings.findBySessionId(session.id()).orElseThrow();
		clearInvocations(channels, monitoring, subscriptions, tokens, repository);
	}

	@AfterEach
	void cleanupMemoryAndConnections() {
		sockets.forEach(StompSocket::close);
		if (sessions.findById(session.id()).isPresent()) lifecycle.endSession(session.id(), 101L);
	}

	@Test
	void actualInboundOrderAuthenticatesConnectThenValidatesSubscribeBeforeSingleJoin() throws Exception {
		var interceptors = inbound.getInterceptors();
		int guardIndex = interceptors.indexOf(subscriptions);
		int existingIndex = -1;
		for (int index = 0; index < interceptors.size(); index++) {
			if (interceptors.get(index) instanceof StompAuthenticationChannelInterceptor) existingIndex = index;
		}
		assertTrue(guardIndex >= 0 && guardIndex < existingIndex);
		StompSocket guardian = connect(102L);
		guardian.subscribe(channelId);
		awaitSubscriptions(1);
		var order = inOrder(subscriptions, tokens, users, monitoring, channels);
		order.verify(subscriptions).preSend(argThat(message -> command(message) == StompCommand.CONNECT), any());
		order.verify(tokens).verifyIdToken("token-102");
		order.verify(users).findUserIdByFirebaseUid("verified-uid-102");
		order.verify(subscriptions).preSend(argThat(message -> command(message) == StompCommand.SUBSCRIBE), any());
		order.verify(monitoring).validateGuardianLocationSubscription(channelId, 102L);
		order.verify(channels).join(eq(channelId), eq(102L), anyString());
		verify(channels, times(1)).join(eq(channelId), eq(102L), anyString());
		ArgumentCaptor<Message<?>> messages = ArgumentCaptor.forClass(Message.class);
		verify(subscriptions, times(2)).preSend(messages.capture(), any());
		var accessor = StompHeaderAccessor.wrap(messages.getAllValues().get(1));
		String identityKey = StompAuthenticationChannelInterceptor.class.getName() + ".authenticatedUserId";
		assertEquals(102L, accessor.getSessionAttributes().get(identityKey));
		assertEquals("999", accessor.getFirstNativeHeader("guardianId"));
		assertNull(accessor.getUser());
	}

	@Test
	void ownerPublishesOneSnapshotToBothSelectedGuardians() throws Exception {
		StompSocket owner = connect(101L);
		StompSocket guardianB = connect(102L);
		StompSocket guardianC = connect(103L);
		owner.subscribe(channelId);
		guardianB.subscribe(channelId);
		guardianC.subscribe(channelId);
		awaitSubscriptions(3);
		owner.publish(channelId);
		String first = guardianB.await("MESSAGE");
		String second = guardianC.await("MESSAGE");
		assertEquals(payload(first), payload(second));
		assertTrue(first.contains("\"senderId\":\"101\""));
		assertTrue(first.contains("\"latitude\":37.5665"));
		verify(monitoring, never()).validateGuardianLocationSubscription(channelId, 101L);
		verify(monitoring).validateGuardianLocationSubscription(channelId, 102L);
		verify(monitoring).validateGuardianLocationSubscription(channelId, 103L);
	}

	@Test
	void acceptedButUnselectedGuardianIsDeniedBeforeJoin() throws Exception {
		assertSubscriptionDenied(104L, channelId);
	}

	@Test
	void unacceptedAndMissingRelationshipAreDeniedBeforeJoin() throws Exception {
		registration.changeStatus(RegistrationStatus.PENDING);
		assertSubscriptionDenied(102L, channelId);
		when(repository.findByUserIdAndGuardianId(101L, 103L)).thenReturn(Optional.empty());
		assertSubscriptionDenied(103L, channelId);
	}

	@Test
	void statusOnlyPermissionCannotAuthorizeLocationSubscription() throws Exception {
		registration.changePermissions(false, true, false);
		assertSubscriptionDenied(102L, channelId);
	}

	@Test
	void endedSessionCannotBeSubscribed() throws Exception {
		lifecycle.endSession(session.id(), 101L);
		assertSubscriptionDenied(102L, channelId);
	}

	@Test
	void unknownChannelCannotBeSubscribed() throws Exception {
		assertSubscriptionDenied(102L, UUID.randomUUID().toString());
	}

	@Test
	void databaseFailureCannotReachRegistryJoin() throws Exception {
		when(repository.findByUserIdAndGuardianId(101L, 102L))
				.thenThrow(new DataAccessResourceFailureException("Permission lookup unavailable"));
		assertSubscriptionDenied(102L, channelId);
	}

	@Test
	void invalidConnectTokenIsStillRejectedByExistingAuthentication() throws Exception {
		when(tokens.verifyIdToken("token-999")).thenThrow(new BadCredentialsException("Invalid token"));
		StompSocket socket = open();
		socket.send(connectFrame(999L));
		socket.await("ERROR");
		verify(tokens).verifyIdToken("token-999");
		verify(channels, never()).join(anyString(), any(), anyString());
	}

	private void assertSubscriptionDenied(long userId, String targetChannel) throws Exception {
		StompSocket socket = connect(userId);
		socket.subscribe(targetChannel);
		socket.await("ERROR");
		verify(channels, never()).join(eq(targetChannel), eq(userId), anyString());
	}

	private StompSocket connect(long userId) throws Exception {
		StompSocket socket = open();
		socket.send(connectFrame(userId));
		socket.await("CONNECTED");
		return socket;
	}

	private StompSocket open() throws Exception {
		FrameListener listener = new FrameListener();
		WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
				.buildAsync(URI.create("ws://localhost:" + port + "/ws"), listener).get(5, TimeUnit.SECONDS);
		StompSocket connection = new StompSocket(socket, listener);
		sockets.add(connection);
		return connection;
	}

	private void awaitSubscriptions(int expected) throws Exception {
		var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		headers.setDestination("/topic/connections/" + channelId + "/locations");
		Message<?> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			int count = broker.getSubscriptionRegistry().findSubscriptions(message).values().stream()
					.mapToInt(List::size).sum();
			if (count == expected) return;
			Thread.sleep(10);
		}
		throw new AssertionError("Broker subscriptions were not registered");
	}

	private static StompCommand command(Message<?> message) {
		return StompHeaderAccessor.wrap(message).getCommand();
	}

	private static String payload(String frame) {
		return frame.substring(frame.indexOf("\n\n") + 2).replace("\0", "");
	}

	private static String connectFrame(long id) {
		return "CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer token-" + id + "\n\n\0";
	}

	private record StompSocket(WebSocket socket, FrameListener listener) {
		void send(String frame) throws Exception {
			socket.sendText(frame, true).get(5, TimeUnit.SECONDS);
		}

		void subscribe(String channelId) throws Exception {
			send("SUBSCRIBE\nid:location\ndestination:/topic/connections/" + channelId
					+ "/locations\nguardianId:999\nuserId:999\n\n\0");
		}

		void publish(String channelId) throws Exception {
			send("SEND\ndestination:/app/connections/" + channelId + "/locations\ncontent-type:application/json\n\n"
					+ "{\"latitude\":37.5665,\"longitude\":126.9780,\"accuracyMeters\":8.5,"
					+ "\"capturedAt\":\"2026-11-15T10:00:00Z\"}\0");
		}

		String await(String command) throws Exception {
			return listener.await(command);
		}

		void close() {
			socket.abort();
		}
	}

	private static final class FrameListener implements WebSocket.Listener {
		private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket socket) {
			socket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				frames.add(buffer.toString());
				buffer.setLength(0);
			}
			socket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		String await(String command) throws Exception {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline) {
				String frame = frames.poll(5, TimeUnit.SECONDS);
				if (frame != null && frame.startsWith(command)) return frame;
				if (frame != null && frame.startsWith("ERROR")) throw new AssertionError("Unexpected STOMP ERROR");
			}
			throw new AssertionError("Missing STOMP " + command + " frame");
		}
	}
}
