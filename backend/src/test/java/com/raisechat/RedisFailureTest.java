package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** 稼働中にRedisへ繋がらなくなっても、APIを止めずに続けること(Redisなしで確認できる単体テスト) */
class RedisFailureTest {
	private static final RuntimeException DOWN = new RedisConnectionFailureException("Redisに繋がらない");

	@Test
	void relayPublishFailureDoesNotBreakTheRequest() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		doThrow(DOWN).when(redis).convertAndSend(anyString(), anyString());
		RedisMessageRelay relay = new RedisMessageRelay(redis, mock(SimpMessagingTemplate.class), new ObjectMapper(), "t");

		assertDoesNotThrow(() -> relay.toTopic("/topic/channels/1", Map.of("type", "created")));
		assertDoesNotThrow(() -> relay.toUser("7", "/queue/overview", Map.of("workspaceId", 1)));
	}

	@Test
	void relayDeliversReceivedEventsToLocalClientsAndIgnoresGarbage() {
		SimpMessagingTemplate local = mock(SimpMessagingTemplate.class);
		RedisMessageRelay relay = new RedisMessageRelay(mock(StringRedisTemplate.class), local, new ObjectMapper(), "t");

		relay.deliver("{\"kind\":\"topic\",\"user\":null,\"destination\":\"/topic/channels/1\",\"payload\":{\"type\":\"created\",\"messageId\":5}}");
		verify(local).convertAndSend(eq("/topic/channels/1"), eq(Map.of("type", "created", "messageId", 5)));

		relay.deliver("{\"kind\":\"user\",\"user\":\"7\",\"destination\":\"/queue/overview\",\"payload\":{\"workspaceId\":2}}");
		verify(local).convertAndSendToUser(eq("7"), eq("/queue/overview"), eq(Map.of("workspaceId", 2)));

		assertDoesNotThrow(() -> relay.deliver("これはJSONではない"));
		verifyNoMoreInteractions(local);
	}
}
