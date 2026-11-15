package com.safepath.backend.session.websocket;

import com.safepath.backend.session.service.GuardianMonitoringService;
import com.safepath.backend.websocket.InMemoryChannelRegistry;
import com.safepath.backend.websocket.StompAuthenticationChannelInterceptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class GuardianLocationSubscriptionInterceptor implements ChannelInterceptor {
	private static final Pattern LOCATION_TOPIC = Pattern.compile("/topic/connections/([^/]+)/locations");
	// Server-only session attribute written by the existing CONNECT interceptor.
	private static final String AUTHENTICATED_USER_ID =
			StompAuthenticationChannelInterceptor.class.getName() + ".authenticatedUserId";
	private final GuardianMonitoringService monitoringService;
	private final InMemoryChannelRegistry channelRegistry;

	public GuardianLocationSubscriptionInterceptor(GuardianMonitoringService monitoringService,
			InMemoryChannelRegistry channelRegistry) {
		this.monitoringService = monitoringService;
		this.channelRegistry = channelRegistry;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
		if (accessor.getCommand() != StompCommand.SUBSCRIBE || accessor.getDestination() == null) {
			return message;
		}
		var destination = LOCATION_TOPIC.matcher(accessor.getDestination());
		if (!destination.matches()) {
			return message;
		}
		var attributes = accessor.getSessionAttributes();
		Object identity = attributes == null ? null : attributes.get(AUTHENTICATED_USER_ID);
		if (!(identity instanceof Long participantId) || participantId <= 0
				|| accessor.getSessionId() == null || accessor.getSessionId().isBlank()) {
			throw new BadCredentialsException("Authenticated WebSocket session is required");
		}
		if (accessor.getUser() != null && (!(accessor.getUser() instanceof Authentication authentication)
				|| !authentication.isAuthenticated() || !participantId.equals(authentication.getPrincipal()))) {
			throw new BadCredentialsException("WebSocket session identity does not match");
		}
		String channelId = destination.group(1);
		if (!channelRegistry.isLocationPublisher(channelId, participantId)) {
			monitoringService.validateGuardianLocationSubscription(channelId, participantId);
		}
		return message;
	}
}
