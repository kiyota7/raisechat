package com.raisechat;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 複数サーバー用(app.cluster.mode=redis)。他のサーバーが発行した通知を受信して、このサーバーに繋がっている人へ転送する */
@Configuration
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "redis")
public class RedisClusterConfig {
	@Bean
	RedisMessageListenerContainer redisEventListener(RedisConnectionFactory factory, RedisMessageRelay relay,
			@Value("${app.cluster.key-prefix:raisechat}") String prefix) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(factory);
		container.addMessageListener((message, pattern) -> relay.deliver(new String(message.getBody(), StandardCharsets.UTF_8)),
				new ChannelTopic(prefix + ":events"));
		return container;
	}
}
