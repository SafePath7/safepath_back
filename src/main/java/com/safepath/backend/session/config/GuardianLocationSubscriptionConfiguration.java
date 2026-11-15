package com.safepath.backend.session.config;

import com.safepath.backend.session.websocket.GuardianLocationSubscriptionInterceptor;
import com.safepath.backend.websocket.StompAuthenticationChannelInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration(proxyBeanMethods = false)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GuardianLocationSubscriptionConfiguration implements WebSocketMessageBrokerConfigurer {
	private final GuardianLocationSubscriptionInterceptor subscriptionInterceptor;

	public GuardianLocationSubscriptionConfiguration(GuardianLocationSubscriptionInterceptor subscriptionInterceptor) {
		this.subscriptionInterceptor = subscriptionInterceptor;
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(subscriptionInterceptor);
	}

	@Bean
	SmartInitializingSingleton verifyGuardianSubscriptionInterceptorOrder(
			@Qualifier("clientInboundChannel") ObjectProvider<AbstractMessageChannel> inboundChannel) {
		return () -> {
			var interceptors = inboundChannel.getObject().getInterceptors();
			int subscriptionIndex = interceptors.indexOf(subscriptionInterceptor);
			int authenticationIndex = -1;
			for (int index = 0; index < interceptors.size(); index++) {
				if (interceptors.get(index) instanceof StompAuthenticationChannelInterceptor) {
					authenticationIndex = index;
					break;
				}
			}
			if (subscriptionIndex < 0 || authenticationIndex < 0 || subscriptionIndex >= authenticationIndex) {
				throw new IllegalStateException("Guardian subscription validation must precede channel join");
			}
		};
	}
}
