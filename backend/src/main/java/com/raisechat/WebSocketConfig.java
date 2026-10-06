package com.raisechat;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
	private final StompAuthInterceptor auth;

	/** 許可するOrigin(カンマ区切り)。空なら、同じオリジンからの接続だけ許可する */
	private final String[] allowedOrigins;

	public WebSocketConfig(StompAuthInterceptor auth, @Value("${app.websocket.allowed-origins:}") String allowedOrigins) {
		this.auth = auth;
		this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(o -> !o.isEmpty())
				.toArray(String[]::new);
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// Originのないクライアント(ブラウザ以外)は通る。どちらもJWTで認証する
		var endpoint = registry.addEndpoint("/ws");
		if (allowedOrigins.length > 0) {
			endpoint.setAllowedOrigins(allowedOrigins);
		}
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic", "/queue");
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(auth);
	}
}
