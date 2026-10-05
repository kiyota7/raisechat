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

		relay.deliver("{\"kind\":\"user\",\"user\":\"7\",\"destination\":\"/queue/presence\",\"payload\":{\"userId\":2,\"online\":true}}");
		verify(local).convertAndSendToUser(eq("7"), eq("/queue/presence"), eq(Map.of("userId", 2, "online", true)));

		assertDoesNotThrow(() -> relay.deliver("これはJSONではない"));
		verifyNoMoreInteractions(local);
	}

	@Test
	void loginStoreFailsOpenWhenRedisIsDown() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForZSet()).thenThrow(DOWN);
		when(redis.delete(anyString())).thenThrow(DOWN);
		RedisLoginAttemptStore store = new RedisLoginAttemptStore(redis, "t");

		assertEquals(List.of(), store.recent("u:alice|1.1.1.1", 1000, 900_000)); // 読めないときは、制限をかけずに続ける
		assertDoesNotThrow(() -> store.add("u:alice|1.1.1.1", 1000, 900_000));
		assertDoesNotThrow(() -> store.remove("u:alice|1.1.1.1"));
	}

	@Test
	void limiterDoesNotBlockLoginsWhileTheStoreIsDown() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForZSet()).thenThrow(DOWN);
		LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, 10, java.time.Duration.ofMinutes(15), java.time.Clock.systemUTC(),
				new RedisLoginAttemptStore(redis, "t"));

		for (int i = 0; i < 10; i++) {
			limiter.recordFailure("alice", "1.1.1.1");
		}
		assertDoesNotThrow(() -> limiter.check("alice", "1.1.1.1"));
	}

	private static PresenceService service(PresenceStore store) {
		return new PresenceService(store, mock(MessageRelay.class), mock(JdbcTemplate.class), 10);
	}

	private static StompHeaderAccessor headers(StompCommand cmd, String sessionId) {
		StompHeaderAccessor h = StompHeaderAccessor.create(cmd);
		h.setSessionId(sessionId);
		return h;
	}

	@Test
	void presenceKeepsWorkingWhenItsStoreIsDown() {
		PresenceStore store = mock(PresenceStore.class);
		when(store.connect(anyLong(), anyString())).thenThrow(DOWN);
		when(store.disconnect(anyString())).thenThrow(DOWN);
		when(store.onlineAmong(any())).thenThrow(DOWN);
		when(store.isOnline(anyLong())).thenThrow(DOWN);
		PresenceService svc = service(store);

		Principal user = () -> "7";
		StompHeaderAccessor h = headers(StompCommand.CONNECTED, "s1");
		var connected = new SessionConnectedEvent(this, MessageBuilder.createMessage(new byte[0], h.getMessageHeaders()), user);
		assertDoesNotThrow(() -> svc.onConnected(connected)); // 接続を記録できなくても、WebSocketの接続は続く

		var disconnect = new SessionDisconnectEvent(this, MessageBuilder.createMessage(new byte[0], headers(StompCommand.DISCONNECT, "s1").getMessageHeaders()),
				"s1", org.springframework.web.socket.CloseStatus.NORMAL, user);
		assertDoesNotThrow(() -> svc.onDisconnected(disconnect));

		assertEquals(List.of(), svc.onlineAmong(List.of(1L, 2L))); // 概要のAPIは、全員オフラインとして返す
		assertFalse(svc.isOnline(1L));
		svc.shutdown();
	}
}
