package com.raisechat;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "spring.datasource.url=jdbc:sqlite:target/test-${random.uuid}.db?foreign_keys=true")
class RealtimeTest {
	@LocalServerPort
	int port;
	@Autowired
	TestRestTemplate rest;
	final ObjectMapper om = new ObjectMapper();

	/** 接続結果(成功/エラー)を保持するハンドラ */
	static class Handler extends StompSessionHandlerAdapter {
		final CompletableFuture<StompSession> connected = new CompletableFuture<>();
		final CompletableFuture<String> error = new CompletableFuture<>();

		@Override
		public void afterConnected(StompSession session, StompHeaders headers) {
			connected.complete(session);
		}

		@Override
		public void handleFrame(StompHeaders headers, Object payload) {
			error.complete(String.valueOf(headers.getFirst("message")));
		}

		@Override
		public void handleTransportError(StompSession session, Throwable ex) {
			error.complete(String.valueOf(ex));
		}

		@Override
		public void handleException(StompSession session, org.springframework.messaging.simp.stomp.StompCommand command,
				StompHeaders headers, byte[] payload, Throwable ex) {
			error.complete(String.valueOf(ex));
		}
	}

	Handler connect(String token) {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		client.setMessageConverter(new MappingJackson2MessageConverter());
		StompHeaders h = new StompHeaders();
		if (token != null) h.add("Authorization", "Bearer " + token);
		Handler handler = new Handler();
		client.connectAsync("ws://localhost:" + port + "/ws", new org.springframework.web.socket.WebSocketHttpHeaders(), h, handler);
		return handler;
	}

	BlockingQueue<Map<String, Object>> subscribe(StompSession s, String dest) {
		BlockingQueue<Map<String, Object>> q = new LinkedBlockingQueue<>();
		s.subscribe(dest, new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return Map.class;
			}

			@Override
			@SuppressWarnings("unchecked")
			public void handleFrame(StompHeaders headers, Object payload) {
				q.add((Map<String, Object>) payload);
			}
		});
		return q;
	}

	JsonNode call(String method, String path, String token, String body) throws Exception {
		HttpHeaders h = new HttpHeaders();
		h.setContentType(MediaType.APPLICATION_JSON);
		if (token != null) h.setBearerAuth(token);
		String res = rest.exchange("/api" + path, org.springframework.http.HttpMethod.valueOf(method),
				new HttpEntity<>(body, h), String.class).getBody();
		return res == null || res.isEmpty() ? null : om.readTree(res);
	}

	String register(String name) throws Exception {
		return call("POST", "/auth/register", null, "{\"username\":\"" + name + "\",\"password\":\"password1\"}").get("token").asText();
	}

	@Test
	void connectRequiresValidJwt() throws Exception {
		Handler none = connect(null);
		assertNotNull(none.error.get(5, TimeUnit.SECONDS));
		assertFalse(none.connected.isDone());

		Handler bad = connect("not-a-jwt");
		assertNotNull(bad.error.get(5, TimeUnit.SECONDS));
		assertFalse(bad.connected.isDone());
	}

	@Test
	void messageEventsReachMembersOnly() throws Exception {
		String sfx = UUID.randomUUID().toString().substring(0, 6);
		String owner = "own_" + sfx, guest = "gst_" + sfx;
		String ot = register(owner), gt = register(guest);
		long ws = call("POST", "/workspaces", ot, "{\"name\":\"WS\"}").get("id").asLong();
		call("POST", "/workspaces/" + ws + "/invite", ot, "{\"username\":\"" + guest + "\"}");
		JsonNode ov = call("GET", "/workspaces/" + ws, ot, null);
		long general = ov.get("channels").get(0).get("id").asLong();
		long priv = call("POST", "/workspaces/" + ws + "/channels", ot, "{\"name\":\"secret\",\"isPrivate\":true}").get("id").asLong();

		// メンバーは購読でき、投稿イベントとサイドバー更新の合図を受け取る
		Handler g = connect(gt);
		StompSession gs = g.connected.get(5, TimeUnit.SECONDS);
		BlockingQueue<Map<String, Object>> topic = subscribe(gs, "/topic/channels/" + general);
		BlockingQueue<Map<String, Object>> overview = subscribe(gs, "/user/queue/overview");
		Thread.sleep(300);
		long msg = call("POST", "/channels/" + general + "/messages", ot, "{\"content\":\"hi\"}").get("id").asLong();

		Map<String, Object> ev = topic.poll(5, TimeUnit.SECONDS);
		assertNotNull(ev);
		assertEquals("created", ev.get("type"));
		assertEquals(msg, ((Number) ev.get("messageId")).longValue());
		Map<String, Object> ovEv = overview.poll(5, TimeUnit.SECONDS);
		assertNotNull(ovEv);
		assertEquals(ws, ((Number) ovEv.get("workspaceId")).longValue());

		// 非メンバーのプライベートチャンネル購読は拒否される
		Handler g2 = connect(gt);
		StompSession gs2 = g2.connected.get(5, TimeUnit.SECONDS);
		subscribe(gs2, "/topic/channels/" + priv);
		assertNotNull(g2.error.get(5, TimeUnit.SECONDS));

		// クライアントからのSENDは拒否される
		Handler g3 = connect(gt);
		StompSession gs3 = g3.connected.get(5, TimeUnit.SECONDS);
		gs3.send("/topic/channels/" + general, Map.of("type", "fake"));
		assertNotNull(g3.error.get(5, TimeUnit.SECONDS));
	}
}
