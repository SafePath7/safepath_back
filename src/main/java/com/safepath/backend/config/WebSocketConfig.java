package com.safepath.backend.config;

import com.safepath.backend.websocket.StompAuthenticationChannelInterceptor;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthenticationChannelInterceptor stompAuthenticationChannelInterceptor;

    public WebSocketConfig(StompAuthenticationChannelInterceptor stompAuthenticationChannelInterceptor) {
        this.stompAuthenticationChannelInterceptor = stompAuthenticationChannelInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthenticationChannelInterceptor);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }
}
